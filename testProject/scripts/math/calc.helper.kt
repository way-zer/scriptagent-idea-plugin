package math

// companionSources 测试: 这是 calc.kts 的 companion source
// 文件名格式: calc.helper.kt → 与 calc.kts 一起编译
// 运行时共享 calc.kts 的 classpath

object CalcHelper {
    fun format(result: String): String = "[ $result ]"
}
