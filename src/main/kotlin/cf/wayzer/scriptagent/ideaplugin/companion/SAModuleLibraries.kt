package cf.wayzer.scriptagent.ideaplugin.companion

import cf.wayzer.scriptAgent.define.SAInternalApi
import cf.wayzer.scriptAgent.define.SAScript
import cf.wayzer.scriptAgent.define.ScriptInfo
import cf.wayzer.scriptAgent.define.ScriptSource
import cf.wayzer.scriptAgent.impl.ManagerImpl
import cf.wayzer.scriptAgent.impl.SACompiledScript
import cf.wayzer.scriptAgent.impl.config.dependenciesFromClass
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlin.script.experimental.jvm.util.KotlinJars
import kotlin.script.experimental.jvm.util.classpathFromClass
import java.io.File

/**
 * Phase 5：让 companion `.kt` 在 IDE 里有正确的 classpath。
 *
 * 范围限定为 `module.kts` 同目录的 `lib`（其余 companion 形式见 IMPLEMENTATION §10.2 的粒度约束）。
 * 每个这样的目录一个 IntelliJ Module：content root = SA 模块目录，source root = `lib`，
 * 模块级 library = 该 module.kts 解析出的依赖 + 框架 classpath。
 */
object SAModuleLibraries {
    private val LOG = Logger.getInstance(SAModuleLibraries::class.java)
    private const val LIBRARY_NAME = "SA Scripts Libraries"
    private const val JAVA_MODULE_TYPE = "JAVA_MODULE"

    private data class ModuleLib(val id: String, val contentDir: VirtualFile, val sourceDir: VirtualFile)

    suspend fun install(project: Project) {
        if (project.isDisposed) return
        val moduleLibs = readAction {
            SAIndexerService.getAllIds(project).mapNotNull { id ->
                val file = SAIndexerService.findScript(project, id) ?: return@mapNotNull null
                if (file.nameWithoutExtension != "module") return@mapNotNull null
                val contentDir = file.parent ?: return@mapNotNull null
                val lib = contentDir.findChild("lib") ?: return@mapNotNull null
                if (!lib.isDirectory || !containsKotlin(lib)) return@mapNotNull null
                ModuleLib(id, contentDir, lib)
            }
        }
        if (moduleLibs.isEmpty()) return
        val framework = frameworkRoots()
        for (entry in moduleLibs) {
            val roots = linkedSetOf<VirtualFile>()
            scriptLibraries(project, entry.id).forEach { toVirtualFile(it)?.let(roots::add) }
            roots.addAll(framework)
            ensureModule(project, "SA-" + entry.id.replace('/', '-'), entry.contentDir, entry.sourceDir, roots.toList())
        }
        // 工程模型刚变过：让 daemon 重新分析，避免已打开的文件继续用旧 classpath 报错。
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                DaemonCodeAnalyzer.getInstance(project).restart("ScriptAgent companion modules updated")
            }
        }
    }

    @OptIn(SAInternalApi::class)
    private suspend fun scriptLibraries(project: Project, id: String): Set<File> {
        val data = readAction {
            val file = SAIndexerService.findScript(project, id) ?: return@readAction null
            val info = SAIndexerService.getInfo(project, file) ?: return@readAction null
            info to SAIndexerService.getDepends(project, file)
        } ?: return emptySet()
        val script = SACompiledScript(ScriptSource.Dummy(ScriptInfo.getOrCreate(id))).apply {
            importData = data.first.imports.toSet()
            dependsData = data.second.toSet()
        }
        return ManagerImpl.importToLibraries(script, offline = true) { _, _, _ -> }
    }

    private fun ensureModule(
        project: Project,
        name: String,
        contentDir: VirtualFile,
        sourceDir: VirtualFile,
        roots: List<VirtualFile>,
    ) {
        val application = ApplicationManager.getApplication()
        val action = {
            WriteAction.run<Exception> {
                val moduleManager = ModuleManager.getInstance(project)
                val module = moduleManager.findModuleByName(name) ?: run {
                    val model = moduleManager.getModifiableModel()
                    val iml = File(project.basePath, ".idea/" + name + ".iml")
                    val created = model.newModule(iml.path, JAVA_MODULE_TYPE)
                    model.commit()
                    created
                }
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                val content = rootModel.contentEntries.firstOrNull { it.url == contentDir.url }
                    ?: rootModel.addContentEntry(contentDir)
                if (content.sourceFolders.none { it.file == sourceDir }) {
                    content.addSourceFolder(sourceDir, false)
                }
                val table = rootModel.moduleLibraryTable
                val library = table.getLibraryByName(LIBRARY_NAME) ?: table.createLibrary(LIBRARY_NAME)
                val libraryModel = library.modifiableModel
                library.getFiles(OrderRootType.CLASSES).forEach {
                    libraryModel.removeRoot(it.url, OrderRootType.CLASSES)
                }
                roots.forEach { libraryModel.addRoot(libraryRootUrl(it), OrderRootType.CLASSES) }
                libraryModel.commit()
                if (rootModel.findLibraryOrderEntry(library) == null) {
                    rootModel.addLibraryEntry(library)
                }
                rootModel.inheritSdk()
                rootModel.commit()
                LOG.info("[SA] companion module " + module.name + ": " + roots.size + " classpath roots")
            }
        }
        if (application.isDispatchThread) action() else application.invokeAndWait(action)
    }

    /**
     * library 里 jar 的 root 必须是 `jar://<path>!/`（archive root）。写成 `file://<path>.jar` 时
     * IntelliJ 会当成普通文件/目录，classpath 实际为空 —— 表现是「library 显示正常，但依赖全红」。
     */
    private fun libraryRootUrl(file: VirtualFile): String =
        if (file.isDirectory) file.url else "jar://" + file.path + "!/"

    private fun containsKotlin(dir: VirtualFile): Boolean {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (containsKotlin(child)) return true
            } else if (child.extension == "kt") {
                return true
            }
        }
        return false
    }

    private fun toVirtualFile(file: File): VirtualFile? =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

    /** 与脚本看到的 framework classpath 对齐（SAConfiguration.baseConfiguration + IDEMode）。 */
    private fun frameworkRoots(): List<VirtualFile> {
        val files = linkedSetOf<File>()
        files.addAll(classpathFromClass<SAScript>().orEmpty())
        files.addAll(KotlinJars.kotlinScriptStandardJarsWithReflect)
        files.addAll(dependenciesFromClass(CoroutineScope::class))
        return files.mapNotNull(::toVirtualFile)
    }
}
