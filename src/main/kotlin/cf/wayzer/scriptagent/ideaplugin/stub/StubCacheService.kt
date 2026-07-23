package cf.wayzer.scriptagent.ideaplugin.stub

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

/**
 * Caches stub signatures per script ID.
 * Supports async refresh via Analysis API (must be called outside resolve context).
 */
@Service(Service.Level.PROJECT)
class StubCacheService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val cache = ConcurrentHashMap<String, List<String>>()

    fun getCached(scriptId: String): List<String>? = cache[scriptId]

    /**
     * Asynchronously refresh the stub for the given script ID using Analysis API.
     * This must NOT be called from within a resolve extension.
     */
    fun refreshAsync(scriptId: String) {
        scope.launch {
            val signatures = readAction { generateSignatures(scriptId) }
            if (signatures != null) {
                cache[scriptId] = signatures
            }
        }
    }

    /**
     * Synchronously refresh the stub. Must be called within a read action.
     * Suitable for tool windows and other non-resolve contexts.
     */
    fun refreshSync(scriptId: String) {
        val signatures = generateSignatures(scriptId)
        if (signatures != null) {
            cache[scriptId] = signatures
        }
    }

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

    fun invalidate(scriptId: String) {
        cache.remove(scriptId)
    }

    fun invalidateAll() {
        cache.clear()
    }

    companion object {
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
