package cf.wayzer.scriptagent.ideaplugin

import cf.wayzer.scriptagent.ideaplugin.companion.companionModuleName
import cf.wayzer.scriptagent.ideaplugin.companion.isCompanionModuleName
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.core.script.v1.ScriptAdditionalIdeaDependenciesProvider

/**
 * Provides per-file script dependency information to the K2 Kotlin IDE analysis engine.
 *
 * K2 的 KaScriptModuleImpl.getDirectRegularDependencies 里 Module → KaSourceModule（**只有源码根**，
 * lib/ 里的类靠这条可见），Library → KaLibraryModule（jar）。两条注入缝都是平铺的，不会顺着 IDE
 * module 依赖递归 —— 所以这里只负责把「脚本实际能看到的模块」算全：自己所在模块 +
 * @Depends 链上脚本所属模块 + 递归它们的 module 依赖。
 * jar（脚本 @file:Import 的 maven、被依赖模块的库）仍由 IDEMode.refineForDepsIDEMode 递归解析。
 */
class SAScriptDependenciesProvider : ScriptAdditionalIdeaDependenciesProvider {

    override fun getRelatedModules(file: VirtualFile, project: Project): List<Module> {
        val moduleManager = ModuleManager.getInstance(project)
        // K2 会把返回的每个 Module 转成 KaSourceModule 加进脚本依赖，所以这里要给出
        // 「脚本实际能看到类的所有模块」：自己所在模块 + 脚本 @Depends 链上各脚本所属的模块，
        // 再递归它们的 module 依赖（module.kts 的 @Depends 已落成 exported module 依赖）。
        val result = LinkedHashSet<Module>()
        fun collect(m: Module) {
            if (!result.add(m)) return
            ModuleRootManager.getInstance(m).moduleDependencies
                .filter { isCompanionModuleName(it.name) }
                .forEach(::collect)
        }

        ProjectFileIndex.getInstance(project).getModuleForFile(file)
            ?.takeIf { isCompanionModuleName(it.name) }
            ?.let(::collect)

        // 只看“最近的 module”不够：普通脚本可能通过 @file:Depends 用到别的模块的 lib 类。
        // BFS 只负责走脚本依赖图；id → 模块名 → 是否存在，统一在下面一步做（不存在的自然被过滤）。
        runCatching { SAIndexerService.getAllDepends(project, file) }
            .getOrDefault(emptyList())
            .mapNotNull { moduleManager.findModuleByName(companionModuleName(it)) }
            .forEach(::collect)
        return result.toList()
    }
}
