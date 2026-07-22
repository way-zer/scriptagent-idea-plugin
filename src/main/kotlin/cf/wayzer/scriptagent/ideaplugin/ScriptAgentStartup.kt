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
import java.io.File

@OptIn(SAIDEModeApi::class)
class ScriptAgentStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
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
            dependsData = entry.depends.toSet()
            importData = entry.imports.toSet()
            fqName = deriveScriptFqName(info.id)
        }
    }

    /**
     * Derives the script class FQN from script ID.
     * Script ID "wayzer/user/ban" -> class name "Ban"
     */
    private fun deriveScriptFqName(scriptId: String): String {
        val fileName = scriptId.substringAfterLast(Config.idSeparator)
        return fileName.replaceFirstChar { it.uppercaseChar() }
    }
}
