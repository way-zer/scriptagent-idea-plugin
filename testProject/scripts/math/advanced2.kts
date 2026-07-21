package scripts.math


name = "高级计算"

// 测试: inject=mathModule → IDE 应知道 mathModule 的类型(来自 math/module.kts)
// 此处故意写一个不存在的依赖 "croe" 测试红线

onEnable {
    logger.info("Advanced math loaded")
}

fun calcPower(x: Int, n: Int): Int {
    var result = 1
    repeat(n) { result = result * x }
    return result
}

ScriptSave.toString()
Services.provide()

@ExperimentalAtomicApi
fun test(){

}