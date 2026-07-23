@file:Depends("math", inject = "mod")
@file:Depends("math/testUtil", inject = "util")

package math


name = "基础计算器"

// 测试: 依赖链 math/module + core → 能否看到 add() 和 UserData
fun calculate(user: UserData, a: Int, b: Int): String {
    val sum = mod.add(a, b)
    val product = mod.multiply(sum, b)
    return "${user.name}: sum=$sum, product=$product"
}

util.hello("util")

scripts.math.TestUtil::hello