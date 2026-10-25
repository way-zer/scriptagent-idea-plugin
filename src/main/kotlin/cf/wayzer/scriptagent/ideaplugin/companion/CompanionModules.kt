package cf.wayzer.scriptagent.ideaplugin.companion

/**
 * companion 模块的命名与判定 —— 全工程唯一约定（别处只引用，不要各写一份）。
 *
 * 点分命名（SA.mySite.map.saveKeader）让 Project 视图在 qualified module names 模式下自动成树；
 * 根模块 SA 的 content root = scripts 根，框架依赖放在共享库 SA Framework 里。
 */
const val ROOT_MODULE_NAME = "SA"
const val FRAMEWORK_LIBRARY_NAME = "SA Framework"
const val LIBRARY_NAME_PREFIX = "SA Scripts Libraries"

fun companionModuleName(scriptId: String) = ROOT_MODULE_NAME + "." + scriptId.replace('/', '.')

fun isCompanionModuleName(name: String) = name == ROOT_MODULE_NAME || name.startsWith("$ROOT_MODULE_NAME.")
