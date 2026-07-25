package coreLibrary.lib

// companionSources 测试: 这是 module.kts 的 lib/ 下的 .kt 文件
// 运行时与 coreLibrary/module.kts 一起编译，共享其 classpath
// IDE 中应能看到正确的类型解析

object StringUtils {
    fun capitalize(s: String): String = s.replaceFirstChar { it.uppercaseChar() }
    fun repeat(s: String, n: Int): String = s.repeat(n)
}
