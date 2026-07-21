@file:Import("kotlinx.coroutines.*", defaultImport = true)
@file:Import("cf.wayzer.scriptAgent.*", defaultImport = true)
@file:Import("com.google.code.gson:gson:2.9.0", mavenDepends = true)

package scripts

name = "Core Library"

data class UserData(
    val name: String,
    val id: Long,
)

fun greet(user: UserData): String = "Hello, ${user.name}!"

onEnable {
    logger.info("Core loaded")
}
