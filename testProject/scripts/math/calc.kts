@file:Depends("math", inject = "mathModule")
@file:Depends("math/testUtil", inject = "util")
//@file:Depends("nonexistent/script")
@file:Import("com.google.code.gson:gson:2.10.1", mavenDepends = true)
//@file:Import("bad", libraryByClass = true, mavenDepends = true)

package math

// === 综合测试文件 ===
//
// 1. @Depends 导航: Ctrl+Click "math" → math/module.kts
// 2. @Depends 校验: "nonexistent/script" 应标红
// 3. @Import 校验: 第5行同时设两个 true → 警告
// 4. inject stub 补全: mathModule.add(), mathModule.multiply()
// 5. inject stub 补全: util.hello()
// 6. Maven 依赖: 下载前 Gson 标红，下载后正常
// 7. defaultImport 传递: kotlin.math.* 来自 math/module → sqrt 不标红
// 8. @Export gutter icon: 无（本文件没有 @Export）
// 9. Stub 刷新 gutter icon: inject 行旁应有刷新按钮

import com.google.gson.Gson

val gson = Gson()

fun calculate(a: Int, b: Int): String {
    val sum = mathModule.add(a, b)
    val product = mathModule.multiply(a, b)
    val root = sqrt(sum.toDouble()) // 来自 kotlin.math.* defaultImport
    util.hello("calc")
    return gson.toJson(mapOf("sum" to sum, "product" to product, "root" to root))
}
