@file:Depends("math", inject = "mod")
@file:Depends("math/testUtil", inject = "util")
package math

import scripts.UserData

name = "基础计算器"

// 测试: 依赖链 math/module + core → 能否看到 add() 和 UserData
fun calculate(user: UserData, a: Int, b: Int): String {
    val sum = mod.add(a,b)
    val product = mod.multiply(a, b)
    return "${user.name}: sum=$sum, product=$product"
}

command("calc", "基础计算") {
    val user = UserData("test", 1L)
    reply(calculate(user, 10, 20))
}

util.hello("util")