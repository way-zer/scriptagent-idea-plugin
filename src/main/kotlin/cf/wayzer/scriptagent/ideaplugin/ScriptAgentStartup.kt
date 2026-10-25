package cf.wayzer.scriptagent.ideaplugin

import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.define.SAIDEModeApi
import cf.wayzer.scriptAgent.define.ScriptInfo
import cf.wayzer.scriptAgent.define.ScriptSource
import cf.wayzer.scriptAgent.impl.SACompiledScript
import cf.wayzer.scriptAgent.impl.config.IDEMode
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import cf.wayzer.scriptagent.ideaplugin.companion.SAModuleLibraries
import cf.wayzer.scriptagent.ideaplugin.stub.StubCacheService
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.backend.workspace.impl.WorkspaceModelInternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.name.NameUtils
import java.io.File

@OptIn(SAIDEModeApi::class)
class ScriptAgentStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // 条件 import：IDE 环境
        Config.environments += "ide"
        // Fixed convention: the Maven library cache lives in <projectRoot>/libs (matches testProject).
        // libraryDir is the only framework global IDE analysis truly needs. Config.rootDir is
        // intentionally left unset so any incidental use fails loudly. Set libraryDir before the
        // metadata hook: every findMetadata/analysis call then implies it is already initialized.
        project.basePath?.let { Config.libraryDir = File(it, "libs").toPath() }
        // 尽早注册：globalDependency 是解析依赖的 baseline，得排在任何依赖解析之前。
        // libs 路径刚设好，这是能解析的最早时刻。（.kts 自己那条 refine 里也会再确保一次。）
        IDEMode.resolveKotlinRuntime(offline = true)
        IDEMode.ideMetadataSource = ::findMetadata
        // Force creation so the stub cache's PSI change listener is registered from project open.
        StubCacheService.getInstance(project)
        installCompanionModules(project)
    }

    /**
     * 给 companion .kt 建专用 IntelliJ Module。平台在工程打开后会异步做一次 JPS→workspace 同步
     * （日志 "Apply JPS storage (iml files)"），早于它的工程模型改动会被整轮覆盖（模块「出现一下
     * 又消失」），必须先等同步完成；Dispatchers.Default 保证恢复时不落在同步所在的线程/事务里。
     */
    private suspend fun installCompanionModules(project: Project) = withContext(Dispatchers.Default) {
        (WorkspaceModel.getInstance(project) as WorkspaceModelInternal).awaitSynchronizationWithJpsModel()
        SAModuleLibraries.install(project)
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
