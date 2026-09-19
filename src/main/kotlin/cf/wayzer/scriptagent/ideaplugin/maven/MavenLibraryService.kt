package cf.wayzer.scriptagent.ideaplugin.maven

import cf.wayzer.scriptAgent.define.SAIDEModeApi
import cf.wayzer.scriptAgent.define.SAInternalApi
import cf.wayzer.scriptAgent.define.ScriptInfo
import cf.wayzer.scriptAgent.define.ScriptSource
import cf.wayzer.scriptAgent.impl.ManagerImpl
import cf.wayzer.scriptAgent.impl.SACompiledScript
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service responsible for downloading dependencies declared in scripts.
 * Supports downloading for a single script and its transitive dependencies.
 * Shows progress in IDE status bar via withBackgroundProgress.
 * After download completes, ScriptConfiguration should be refreshed to pick up new JARs.
 */
@Service(Service.Level.PROJECT)
class MavenLibraryService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val downloading = AtomicBoolean(false)

    val isDownloading: Boolean get() = downloading.get()

    /**
     * Downloads dependencies for a script and all its transitive dependencies.
     * Uses the same [ManagerImpl.importToLibraries] as runtime.
     * Shows background progress in the IDE.
     */
    @OptIn(SAInternalApi::class, SAIDEModeApi::class)
    fun downloadForScript(scriptId: String, onComplete: (success: Boolean, message: String) -> Unit = { _, _ -> }) {
        if (!downloading.compareAndSet(false, true)) {
            onComplete(false, "Download already in progress")
            return
        }
        scope.launch {
            try {
                val (errors, visited) = withBackgroundProgress(project, "Downloading Script Dependencies") {
                    val errors = mutableListOf<String>()
                    val visited = mutableSetOf<String>()
                    downloadRecursive(scriptId, visited, errors)
                    errors to visited
                }
                val message = if (errors.isEmpty()) {
                    "Dependencies resolved for ${visited.size} scripts"
                } else {
                    "Resolved with ${errors.size} errors:\n${errors.joinToString("\n")}"
                }
                onComplete(errors.isEmpty(), message)
            } catch (e: Exception) {
                onComplete(false, "Download failed: ${e.message}")
            } finally {
                downloading.set(false)
            }
        }
    }

    @OptIn(SAInternalApi::class)
    private suspend fun downloadRecursive(
        scriptId: String,
        visited: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        if (!visited.add(scriptId)) return
        val (info, depends) = readAction {
            val file = SAIndexerService.findScript(project, scriptId) ?: return@readAction null
            val info = SAIndexerService.getInfo(project, file) ?: return@readAction null
            info to SAIndexerService.getDepends(project, file)
        } ?: return

        val script = SACompiledScript(ScriptSource.Dummy(ScriptInfo.getOrCreate(scriptId))).apply {
            importData = info.imports.toSet()
            dependsData = depends.toSet()
        }

        ManagerImpl.importToLibraries(script) { msg, key, e ->
            errors.add("$msg: $key${e?.let { " (${it.message})" } ?: ""}")
        }

        for (dep in depends) {
            if (dep.soft) continue
            downloadRecursive(dep.name, visited, errors)
        }
    }

    companion object {
        fun getInstance(project: Project): MavenLibraryService = project.service()
    }
}
