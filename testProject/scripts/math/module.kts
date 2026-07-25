@file:Depends("core")
@file:Import("kotlin.math.*", defaultImport = true)

package math

// Math module：依赖 core，提供 @Export 数学函数

@Export
fun add(a: Int, b: Int) = a + b

@Export
fun multiply(a: Int, b: Int) = a * b
