@file:Import("core.lib.*", defaultImport = true)

package core

// 基础模块：提供 @Export 成员和 defaultImport

@Export
fun greet(name: String): String = "Hello, $name!"

@Export
val version: String = "1.0.0"
