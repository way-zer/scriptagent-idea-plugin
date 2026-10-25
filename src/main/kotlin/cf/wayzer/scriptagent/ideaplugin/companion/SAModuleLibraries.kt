package cf.wayzer.scriptagent.ideaplugin.companion

import cf.wayzer.scriptAgent.define.SAInternalApi
import cf.wayzer.scriptAgent.define.ScriptInfo
import cf.wayzer.scriptAgent.define.ScriptSource
import cf.wayzer.scriptAgent.impl.ManagerImpl
import cf.wayzer.scriptAgent.impl.SACompiledScript
import cf.wayzer.scriptAgent.impl.config.IDEMode
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import cf.wayzer.scriptagent.ideaplugin.index.withIndexInSmartMode
import cf.wayzer.scriptagent.ideaplugin.index.resolveRootAndId
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.jvm.JvmDependency

/**
 * Phase 5：让 companion .kt 在 IDE 里有正确的 classpath（范围见 IMPLEMENTATION §10.2，只处理 lib/）。
 *
 * - 每个 module.kts 一个 IntelliJ Module：content root = module.kts 所在目录，lib 按 URL 配成
 *   source root（不要求存在、也不创建）；模块自己的 module library 只放它 module.kts 解析出的依赖；
 * - framework（core jar + Kotlin runtime）是项目级共享库 SA Framework，各模块引用同一份；
 * - 顶层根模块 SA（content root = scripts 根）让脚本树有真实根模块，而不是纯虚拟节点；
 * - @Depends 落成 exported module 依赖，模块间库通过依赖链传递。模块命名见 companionModuleName
 *   （点分形式，Project 视图在 qualified module names 下自动成树）。
 */
object SAModuleLibraries {
    private val LOG = Logger.getInstance(SAModuleLibraries::class.java)
    private const val JAVA_MODULE_TYPE = "JAVA_MODULE"

    private data class ModuleLib(
        val id: String,
        val contentDir: VirtualFile,
        val libUrl: String,
        val root: VirtualFile,
    )

    private data class ResolvedClasspath(val libraries: Set<File>, val moduleDeps: List<String>)

    suspend fun install(project: Project) {
        if (project.isDisposed) return
        // getAllIds/findScript/getInfo 都在查索引：等 smart 模式，期间索引被重建会自动重试。
        val moduleLibs = project.withIndexInSmartMode {
            SAIndexerService.getAllIds(project).mapNotNull { id ->
                val file = SAIndexerService.findScript(project, id) ?: return@mapNotNull null
                if (file.nameWithoutExtension != "module") return@mapNotNull null
                val contentDir = file.parent ?: return@mapNotNull null
                // 该 script 所属的 scripts 根（与 SAIndexerService 的 id 归属一致）
                val root = resolveRootAndId(file)?.first ?: return@mapNotNull null
                ModuleLib(id, contentDir, contentDir.url + "/lib", root)
            }
        }
        if (moduleLibs.isEmpty()) return
        val framework = frameworkRoots()
        // framework（core jar + Kotlin runtime）做成项目级共享库：全工程只定义一份，各模块引用它
        val frameworkLibrary = ensureFrameworkLibrary(project, framework, framework.mapNotNull(::sourcesJarOf))
        // 顶层根模块：content root = scripts 根，只挂共享 framework 库。
        // 它把整棵脚本树收进一个模块，点分名让它成为所有 SA.* 的树根。
        val roots = moduleLibs.map { it.root }.distinctBy { it.path }
        (roots.firstOrNull { it.name == "scripts" } ?: roots.singleOrNull())?.let { root ->
            ensureModule(
                project, ROOT_MODULE_NAME, root, null, emptyList(), emptyList(),
                extraLibraries = listOf(frameworkLibrary)
            )
        }
        val moduleIds = moduleLibs.mapTo(mutableSetOf()) { it.id }
        val companionDeps = mutableMapOf<String, List<String>>()
        for (entry in moduleLibs) {
            val resolved = resolveCompanionClasspath(project, entry.id, moduleIds)
            // 本模块只挂自己的依赖；framework 走共享库
            val classpath = resolved.libraries.mapNotNull(::toVirtualFile).distinct()
            val moduleName = companionModuleName(entry.id)
            companionDeps[moduleName] = resolved.moduleDeps
            // sources 只挂已经落盘的；缺失的由横幅的 "Download sources" 按需补。
            ensureModule(
                project, moduleName, entry.contentDir, entry.libUrl,
                classpath, classpath.mapNotNull(::sourcesJarOf),
                extraLibraries = listOf(frameworkLibrary)
            )
        }
        // 第二阶段：模块都建齐后再连依赖（module 依赖会连带传递被依赖模块的 library）
        for ((moduleName, depNames) in companionDeps) {
            wireDependencies(project, moduleName, depNames)
        }
        // 工程模型刚变过：让 daemon 重新分析，避免已打开的文件继续用旧 classpath 报错。
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                DaemonCodeAnalyzer.getInstance(project).restart("ScriptAgent companion modules updated")
            }
        }
    }

    /**
     * 收集 companion 模块的 IDE classpath：递归规则与运行时一致（依赖的库会进子脚本编译路径），
     * 但"依赖已经有 companion 模块"时不再重复解析它的 maven 依赖 —— 交给 exported module 依赖传递，
     * 那条链路由它自己的 install 收集。没有模块的依赖（普通脚本）照旧把库并进来并继续展开。
     *
     * defaultImport 的递归不在这里：那属于 .kts 的编译配置，由 IDEMode.refineForDepsIDEMode 负责。
     */
    @OptIn(SAInternalApi::class)
    private suspend fun resolveCompanionClasspath(
        project: Project,
        id: String,
        moduleIds: Set<String>,
    ): ResolvedClasspath {
        val libraries = linkedSetOf<File>()
        val moduleDeps = linkedSetOf<String>()
        val visited = mutableSetOf<String>()

        suspend fun walk(scriptId: String) {
            if (!visited.add(scriptId)) return
            val data = project.withIndexInSmartMode {
                val file = SAIndexerService.findScript(project, scriptId) ?: return@withIndexInSmartMode null
                SAIndexerService.getInfo(project, file) to SAIndexerService.getDepends(project, file)
            } ?: return
            if (scriptId != id && scriptId in moduleIds) {
                // 有 module 的依赖：只连 module 依赖，停止展开
                moduleDeps += companionModuleName(scriptId)
                return
            }
            val (info, depends) = data
            val script = SACompiledScript(ScriptSource.Dummy(ScriptInfo.getOrCreate(scriptId))).apply {
                importData = info?.imports?.toSet().orEmpty()
                dependsData = depends.toSet()
            }
            libraries += ManagerImpl.importToLibraries(script, offline = true) { _, _, _ -> }
            for (dep in depends) {
                if (dep.soft) continue
                walk(dep.name)
            }
        }

        walk(id)
        return ResolvedClasspath(libraries, moduleDeps.toList())
    }

    /**
     * 把脚本的 @Depends 落成 IntelliJ 的 module 依赖（exported）：被依赖模块的 lib 类、以及它挂的
     * maven library 都会进当前模块的 classpath。与运行时对齐 —— SAConfiguration.refineForDeps 会把
     * 依赖的 libraries 加到子脚本的编译 classpath 上。只增删 SA 前缀的依赖，不动用户自己的模块依赖。
     */
    private fun wireDependencies(project: Project, moduleName: String, depModuleNames: List<String>) {
        val moduleManager = ModuleManager.getInstance(project)
        val module = moduleManager.findModuleByName(moduleName) ?: return
        val wanted = depModuleNames.mapNotNull { moduleManager.findModuleByName(it) }.toSet()
        val application = ApplicationManager.getApplication()
        val action = {
            WriteAction.run<Exception> {
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                val changed = mutableListOf<String>()
                rootModel.orderEntries.filterIsInstance<ModuleOrderEntry>()
                    .filter { entry ->
                        val dep = entry.module ?: return@filter false
                        isCompanionModuleName(dep.name) && dep !in wanted
                    }
                    .forEach { entry ->
                        changed += "-" + (entry.module?.name ?: "?")
                        rootModel.removeOrderEntry(entry)
                    }
                val existingEntries = rootModel.orderEntries.filterIsInstance<ModuleOrderEntry>()
                wanted.sortedBy { it.name }.forEach { dep ->
                    val entry = existingEntries.firstOrNull { it.module == dep }
                    if (entry == null) {
                        changed += "+" + dep.name
                        rootModel.addModuleOrderEntry(dep).apply {
                            scope = DependencyScope.COMPILE
                            isExported = true
                        }
                    } else if (!entry.isExported) {
                        // 早期版本建成过非 exported 的依赖：修正，否则下游看不到传递的 library
                        entry.isExported = true
                        changed += "~" + dep.name
                    }
                }
                if (changed.isEmpty()) {
                    rootModel.dispose()
                } else {
                    rootModel.commit()
                    LOG.info("[SA] module deps of " + module.name + ": " + changed)
                }
            }
        }
        if (application.isDispatchThread) action() else application.invokeAndWait(action)
    }

    private fun ensureModule(
        project: Project,
        name: String,
        contentDir: VirtualFile,
        libUrl: String?,
        classes: List<VirtualFile>,
        sources: List<VirtualFile>,
        extraLibraries: List<Library> = emptyList(),
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
                if (libUrl != null && content.sourceFolders.none { it.url == libUrl }) {
                    content.addSourceFolder(libUrl, false)
                }
                val table = rootModel.moduleLibraryTable
                val libraryName = LIBRARY_NAME_PREFIX + " [" + name + "]"
                table.libraries
                    .filter { it.name != null && it.name!!.startsWith(LIBRARY_NAME_PREFIX) && it.name != libraryName }
                    .forEach { table.removeLibrary(it) }
                if (classes.isEmpty() && sources.isEmpty()) {
                    // 没有自己的依赖（如根模块）：把上一版留下的空库摘掉
                    table.getLibraryByName(libraryName)?.let { oldLib ->
                        rootModel.orderEntries.filterIsInstance<LibraryOrderEntry>()
                            .firstOrNull { it.library == oldLib }
                            ?.let(rootModel::removeOrderEntry)
                        table.removeLibrary(oldLib)
                    }
                } else {
                    val library = table.getLibraryByName(libraryName) ?: table.createLibrary(libraryName)
                    val libraryModel = library.modifiableModel
                    // 只在内容真的变了时才改 root：每次 install 都 remove/add 会白白触发
                    // 工程模型变更 → PSI/索引重建，和正在跑的分析撞车（Invalid PSI / dumb 竞争）
                    fun replaceRoots(type: OrderRootType, files: List<VirtualFile>): Boolean {
                        val wanted = files.map { libraryRootUrl(it) }
                        if (library.getFiles(type).map { it.url } == wanted) return false
                        library.getFiles(type).forEach { libraryModel.removeRoot(it.url, type) }
                        files.forEach { libraryModel.addRoot(libraryRootUrl(it), type) }
                        return true
                    }
                    val changed = replaceRoots(OrderRootType.CLASSES, classes) or
                            replaceRoots(OrderRootType.SOURCES, sources)
                    if (changed) libraryModel.commit() else libraryModel.dispose()
                    val libraryEntry = rootModel.findLibraryOrderEntry(library) ?: rootModel.addLibraryEntry(library)
                    // 本模块的库要 exported，依赖本模块的 companion 模块才能通过 module 依赖看到这些 maven jar
                    libraryEntry.isExported = true
                }
                // 共享 framework 库：每个模块引用同一个 Library（内容只有一份）
                for (library in extraLibraries) {
                    if (rootModel.findLibraryOrderEntry(library) == null) rootModel.addLibraryEntry(library)
                }
                rootModel.inheritSdk()
                rootModel.commit()
                LOG.info(
                    "[SA] companion module " + module.name + ": " +
                            classes.size + " classpath roots, " + sources.size + " source roots"
                )
            }
        }
        if (application.isDispatchThread) action() else application.invokeAndWait(action)
    }

    /** 项目级共享的 framework 库：core jar + Kotlin runtime，全工程只定义一份，各模块引用它。 */
    private fun ensureFrameworkLibrary(
        project: Project,
        classes: List<VirtualFile>,
        sources: List<VirtualFile>,
    ): Library {
        val application = ApplicationManager.getApplication()
        var result: Library? = null
        val action = {
            WriteAction.run<Exception> {
                val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                val library = table.getLibraryByName(FRAMEWORK_LIBRARY_NAME)
                    ?: table.createLibrary(FRAMEWORK_LIBRARY_NAME)
                val model = library.modifiableModel
                fun replaceRoots(type: OrderRootType, files: List<VirtualFile>): Boolean {
                    val wanted = files.map { libraryRootUrl(it) }
                    if (library.getFiles(type).map { it.url } == wanted) return false
                    library.getFiles(type).forEach { model.removeRoot(it.url, type) }
                    files.forEach { model.addRoot(libraryRootUrl(it), type) }
                    return true
                }
                val changed = replaceRoots(OrderRootType.CLASSES, classes) or
                        replaceRoots(OrderRootType.SOURCES, sources)
                if (changed) model.commit() else model.dispose()
                result = library
            }
        }
        if (application.isDispatchThread) action() else application.invokeAndWait(action)
        return result!!
    }

    /**
     * library 里 jar 的 root 必须是 `jar://<path>!/`（archive root）。写成 `file://<path>.jar` 时
     * IntelliJ 会当成普通文件/目录，classpath 实际为空 —— 表现是「library 显示正常，但依赖全红」。
     */
    private fun libraryRootUrl(file: VirtualFile): String =
        if (file.isDirectory) file.url else "jar://" + file.path + "!/"

    private fun toVirtualFile(file: File): VirtualFile? =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

    /** sources jar 就是同名兄弟文件；非 jar / 不存在的一律返回 null。 */
    private fun sourcesJarOf(jar: VirtualFile): VirtualFile? {
        if (jar.isDirectory || jar.extension != "jar") return null
        val file = VfsUtilCore.virtualToIoFile(jar)
        return toVirtualFile(File(file.parentFile, file.nameWithoutExtension + "-sources.jar"))
    }

    /** 与 `.kts` 完全同源：config 里 updateClasspath 的结果（SA jar）+ `IDEMode.resolveKotlinRuntime(offline = true)`（隐式路径，不联网）。 */
    private suspend fun frameworkRoots(): List<VirtualFile> {
        val files = linkedSetOf<File>()
        files.addAll(IDEMode.resolveKotlinRuntime(offline = true))
        IDEMode[ScriptCompilationConfiguration.dependencies].orEmpty()
            .filterIsInstance<JvmDependency>()
            .flatMapTo(files) { it.classpath }
        return files.mapNotNull(::toVirtualFile)
    }
}
