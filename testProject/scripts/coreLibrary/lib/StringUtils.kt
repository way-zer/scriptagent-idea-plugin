package coreLibrary.lib

import com.google.gson.Gson

// companionSources 测试: 这是 module.kts 的 lib/ 下的 .kt 文件
// 运行时与 coreLibrary/module.kts 一起编译，共享其 classpath
// [Phase5] Gson 来自 module.kts 的 @file:Import(mavenDepends)，
//          用于验证 lib 下的 companion 是否继承了模块的 classpath

object StringUtils {
    private val gson = Gson()

    fun capitalize(s: String): String = s.replaceFirstChar { it.uppercaseChar() }
    fun repeat(s: String, n: Int): String = s.repeat(n)
    fun toJson(value: Any): String = gson.toJson(value)
}
