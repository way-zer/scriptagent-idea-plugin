@file:Depends("core")
@file:Depends("math/calc")
@file:Depends("math/advanced")
@file:Import("org.slf4j:slf4j-simple:2.0", mavenDependsSingle = true)

package scripts.app

// 测试: 多依赖链 + 多种 Import 类型
// 依赖不存在的脚本 "xyz/nope" → 应该标红

name = "Application Entry"

fun runApp(): String {
    val result = calculate(UserData("App", 100L), 5, 3)
    // ↓ 应能调用 math/calc 中定义的 calculate
    // ↓ UserData 应能解析自 module.kts
    return "App result: $result"
}

onEnable {
    logger.info("App started: ${runApp()}")
}
