package cf.wayzer.scriptagent.ideaplugin.maven

import cf.wayzer.scriptAgent.define.annotations.ImportData
import cf.wayzer.scriptAgent.util.DependencyManager
import cf.wayzer.scriptAgent.util.maven.Dependency
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service responsible for downloading Maven dependencies declared in scripts.
 * Downloads are asynchronous and non-blocking.
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
     * Triggers async download of all Maven dependencies found in indexed scripts.
     * [onComplete] is called on completion with (success, message).
     */
    fun downloadAll(onComplete: (success: Boolean, message: String) -> Unit = { _, _ -> }) {
        if (!downloading.compareAndSet(false, true)) {
            onComplete(false, "Download already in progress")
            return
        }
        scope.launch {
            try {
                val result = doDownload()
                onComplete(true, result)
            } catch (e: Exception) {
                onComplete(false, "Download failed: ${e.message}")
            } finally {
                downloading.set(false)
            }
        }
    }

    private suspend fun doDownload(): String {
        // Collect all Maven dependencies from all indexed scripts
        val allImports = readAction {
            val allIds = SAIndexerService.getAllIds(project)
            allIds.flatMap { id ->
                val file = SAIndexerService.findScript(project, id) ?: return@flatMap emptyList()
                val info = SAIndexerService.getInfo(project, file) ?: return@flatMap emptyList()
                info.imports
            }
        }

        val group = allImports.groupBy { it.type }
        val mavenDeps = group[ImportData.Type.MavenDepends].orEmpty() +
                group[ImportData.Type.MavenDependsSingle].orEmpty()

        if (mavenDeps.isEmpty()) return "No Maven dependencies found"

        // Use the framework's DependencyManager to resolve and download
        val downloadedFiles = DependencyManager(null) {
            group[ImportData.Type.MavenRepository]?.forEach { addRepository(it.value) }
            group[ImportData.Type.MavenDependsSingle]?.forEach {
                require(Dependency.parse(it.value), resolveChild = false)
            }
            group[ImportData.Type.MavenDepends]?.forEach {
                require(Dependency.parse(it.value))
            }
            getFiles()
        }

        return "Downloaded ${downloadedFiles.size} JARs"
    }

    companion object {
        fun getInstance(project: Project): MavenLibraryService = project.service()
    }
}
