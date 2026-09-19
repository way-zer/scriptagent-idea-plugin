package cf.wayzer.scriptagent.ideaplugin.stub

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import cf.wayzer.scriptagent.ideaplugin.index.resolveRootAndId
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.KaRendererAnnotationsFilter
import org.jetbrains.kotlin.analysis.api.renderer.declarations.bodies.KaFunctionLikeBodyRenderer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.bodies.KaVariableInitializerRenderer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Caches stub signatures per script ID, persisted under .idea via PersistentStateComponent.
 *
 * Lifecycle:
 * - generation is triggered lazily on first resolve (SAResolveExtensionProvider.buildFileText),
 *   manually from the gutter icon, or eagerly by the Sync action;
 * - .kts changes invalidate the affected script (debounced) and trigger regeneration;
 * - every completed (re)generation schedules a debounced DaemonCodeAnalyzer restart so consumers
 *   re-read the stub.
 *
 * Analysis API calls must NOT run inside a resolve context, hence everything here is dispatched to
 * the project coroutine scope.
 */
@Service(Service.Level.PROJECT)
@State(name = "ScriptAgentStubCache", storages = [Storage("scriptAgentStubs.xml")])
class StubCacheService(
    private val project: Project,
    private val scope: CoroutineScope,
) : PersistentStateComponent<StubCacheService.State>, Disposable {

    data class State(
        var stubs: MutableMap<String, List<String>> = ConcurrentHashMap<String, List<String>>()
    )

    private var state = State()

    /** Scripts already requested this session; cleared by invalidate, prevents resolve-context loops. */
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    /** Signature generation is expensive and must be serialized. */
    private val refreshMutex = Mutex()

    private val dirtyScripts = ConcurrentHashMap.newKeySet<String>()
    private val invalidateScheduled = AtomicBoolean(false)
    private val restartScheduled = AtomicBoolean(false)

    private val treeListener = object : PsiTreeChangeAdapter() {
        override fun childAdded(event: PsiTreeChangeEvent) = handle(event)
        override fun childRemoved(event: PsiTreeChangeEvent) = handle(event)
        override fun childReplaced(event: PsiTreeChangeEvent) = handle(event)
        override fun childrenChanged(event: PsiTreeChangeEvent) = handle(event)
        override fun propertyChanged(event: PsiTreeChangeEvent) = handle(event)

        private fun handle(event: PsiTreeChangeEvent) {
            val vFile = event.file?.virtualFile ?: return
            val id = resolveRootAndId(vFile)?.second ?: return
            markDirty(id)
        }
    }

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(treeListener, this)
    }

    override fun getState(): State = state

    override fun loadState(state: State) {
        // Normalize to a concurrent map: mutations run on background coroutines while the platform
        // may serialize the state.
        this.state = State(ConcurrentHashMap(state.stubs))
    }

    fun getCached(scriptId: String): List<String>? = state.stubs[scriptId]

    /**
     * Fire-and-forget generation, at most once per script per session (until invalidated).
     * Safe from a resolve extension or PSI change event: it only launches a coroutine.
     */
    fun requestRefresh(scriptId: String) {
        if (!attempted.add(scriptId)) return
        scope.launch { doRefresh(scriptId) }
    }

    /** Manual refresh (gutter icon): regenerate even if already requested. */
    fun refreshAsync(scriptId: String) {
        attempted.add(scriptId)
        scope.launch { doRefresh(scriptId) }
    }

    /** Awaitable refresh used by the Sync action so stubs are ready before reanalysis. */
    suspend fun refreshAndAwait(scriptId: String) {
        attempted.add(scriptId)
        doRefresh(scriptId)
    }

    /** Synchronous refresh (tool window). Must be called within a read action. */
    fun refreshSync(scriptId: String) {
        val signatures = generateSignatures(scriptId)
        if (signatures != null) state.stubs[scriptId] = signatures
    }

    fun invalidate(scriptId: String) {
        state.stubs.remove(scriptId)
        attempted.remove(scriptId)
    }

    fun invalidateAll() {
        state.stubs.clear()
        attempted.clear()
    }

    private suspend fun doRefresh(scriptId: String) {
        try {
            refreshMutex.withLock {
                val signatures = readAction { generateSignatures(scriptId) }
                if (signatures != null) state.stubs[scriptId] = signatures
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Generation can fail (analysis unavailable, broken declaration, ...). Keep the
            // previous/empty cache and carry on instead of failing the project scope.
        } finally {
            scheduleRestart()
        }
    }

    private fun markDirty(scriptId: String) {
        dirtyScripts.add(scriptId)
        if (!invalidateScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(INVALIDATE_DEBOUNCE_MS)
            invalidateScheduled.set(false)
            for (id in dirtyScripts.toList().also { dirtyScripts.clear() }) {
                invalidate(id)
                requestRefresh(id)
            }
        }
    }

    /** Debounced daemon restart so consumers re-read freshly generated stubs. */
    private fun scheduleRestart() {
        if (!restartScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(RESTART_DEBOUNCE_MS)
            restartScheduled.set(false)
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) {
                    DaemonCodeAnalyzer.getInstance(project).restart("ScriptAgent stub cache updated")
                }
            }
        }
    }

    override fun dispose() {}

    @OptIn(KaExperimentalApi::class)
    private fun generateSignatures(scriptId: String): List<String>? {
        val vFile = SAIndexerService.findScript(project, scriptId) ?: return null
        val ktFile = vFile.toPsiFile(project) as? KtFile ?: return null
        val declarations = ktFile.script?.declarations ?: ktFile.declarations
        val exportDecls = declarations.filter {
            it.annotationEntries.any { a -> a.shortName?.asString() == "Export" }
        }
        if (exportDecls.isEmpty()) return emptyList()

        return analyze(ktFile) {
            exportDecls.mapNotNull { decl ->
                when (decl) {
                    is KtNamedFunction, is KtProperty -> {
                        val symbol = (decl as KtDeclaration).symbol as? KaCallableSymbol
                            ?: return@mapNotNull null
                        val rendered = symbol.render(STUB_RENDERER)
                        "abstract " + rendered
                            .removePrefix("public ")
                            .removePrefix("internal ")
                    }

                    else -> null
                }
            }
        }
    }

    companion object {
        private const val INVALIDATE_DEBOUNCE_MS = 300L
        private const val RESTART_DEBOUNCE_MS = 150L

        fun getInstance(project: Project): StubCacheService = project.service()

        /**
         * Renderer: FQN types, no body, no initializer, no annotations on the declaration itself.
         * Keeps annotations on parameters.
         */
        @OptIn(KaExperimentalApi::class)
        private val STUB_RENDERER = KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES.with {
            functionLikeBodyRenderer = KaFunctionLikeBodyRenderer.NO_BODY
            variableInitializerRenderer = KaVariableInitializerRenderer.NO_INITIALIZER
            annotationRenderer = annotationRenderer.with {
                annotationFilter = KaRendererAnnotationsFilter { _, owner ->
                    owner !is KaCallableSymbol
                }
            }
        }
    }
}
