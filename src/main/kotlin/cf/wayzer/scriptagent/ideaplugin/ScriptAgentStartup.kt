package cf.wayzer.scriptagent.ideaplugin

import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.define.SAIDEModeApi
import cf.wayzer.scriptAgent.define.ScriptInfo
import cf.wayzer.scriptAgent.define.ScriptSource
import cf.wayzer.scriptAgent.impl.SACompiledScript
import cf.wayzer.scriptAgent.impl.config.IDEMode
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.kotlin.name.NameUtils
import java.io.File

@OptIn(SAIDEModeApi::class)
class ScriptAgentStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Fixed convention: the Maven library cache lives in <projectRoot>/libs (matches testProject).
        // libraryDir is the only framework global IDE analysis truly needs. Config.rootDir is
        // intentionally left unset so any incidental use fails loudly. Set libraryDir before the
        // metadata hook: every findMetadata/analysis call then implies it is already initialized.
        project.basePath?.let { Config.libraryDir = File(it, "libs").toPath() }
        IDEMode.ideMetadataSource = ::findMetadata
    }

    private fun findMetadata(root: File, info: ScriptInfo): SACompiledScript? = runReadActionBlocking {
        val project = LocalFileSystem.getInstance().findFileByIoFile(root)?.let { vRoot ->
            ProjectManager.getInstance().openProjects.firstOrNull { project ->
                !project.isDisposed && ProjectFileIndex.getInstance(project).isInContent(vRoot)
            }
        } ?: return@runReadActionBlocking null

        val vFile = SAIndexerService.findScript(project, info.id) ?: return@runReadActionBlocking null
        val entry = SAIndexerService.getInfo(project, vFile) ?: return@runReadActionBlocking null

        SACompiledScript(ScriptSource.Dummy(info)).apply {
            dependsData = SAIndexerService.getDepends(project, vFile).toSet()
            importData = entry.imports.toSet()
            fqName = deriveScriptFqName(info.id).let { (pkg, name) -> "$pkg.$name" }
        }
    }

}

fun deriveScriptFqName(scriptId: String): Pair<String, String> {
    val padded = "scripts/$scriptId"
    val p = padded.substringBeforeLast(Config.idSeparator).replace(Config.idSeparator, '.')
    val name = NameUtils.getScriptNameForFile(scriptId).identifier
    return p to name
}
