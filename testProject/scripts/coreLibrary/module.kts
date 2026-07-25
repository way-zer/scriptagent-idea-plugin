@file:Import("coreLibrary.lib.*", defaultImport = true)

package coreLibrary

// coreLibrary module: 带 companionSources (lib/ 目录)
// 测试: lib/StringUtils.kt 应该能被编译并使用
// 测试: defaultImport "coreLibrary.lib.*" 让子脚本能直接用 StringUtils

@Export
fun formatName(name: String): String = StringUtils.capitalize(name)
