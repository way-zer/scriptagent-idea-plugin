@file:Depends("core")
@file:Import("math.lib.*", defaultImport = true)
//@file:Import("cf.wayzer.scriptAgent.util.ScriptSave", defaultImport = true)
@file:Import("kotlin.concurrent.atomics.ExperimentalAtomicApi", defaultImport = true)

package math


@Export
fun add(a: Int, b: Int) = a + b
@Export
fun multiply(a: Int, b: Int) = a * b


onEnable {
    logger.info("Math Module loaded")
}

Services.toString()

@ExperimentalAtomicApi
fun test(){
}