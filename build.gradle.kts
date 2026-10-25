import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "cf.wayzer.scriptAgent"

// 公测 alpha 版本号：Marketplace 要求语义化版本，可用 -PpluginVersion=... 覆盖
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0-alpha.1")

// 本地开发默认用已安装的 IDE（快）；发布/CI 加 -PuseLocalIde=false 走远程下载的 2026.2，确保可复现
val useLocalIde = providers.gradleProperty("useLocalIde").orNull?.toBoolean() ?: true

repositories {
    mavenCentral()
    maven(url = "https://www.jitpack.io")
    mavenLocal()
    saRepository()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
//        intellijIdea("2025.3.1")
        if (useLocalIde) {
            local("C:\\Users\\q1048\\AppData\\Local\\Programs\\IntelliJ IDEA Ultimate")
        } else {
            intellijIdea("2026.2")
        }
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        bundledModule("intellij.kotlin.analysis.platform")
    }
    // core（SA）制品：版本默认跟 SA 发布对齐，可用 -PsaVersion=<version> 覆盖；
    // composite build 时该依赖会被同级 ScriptAgent 源码工程替换，无需先发布。
    implementation("cf.wayzer:ScriptAgent:" + (findProperty("saVersion") ?: "2.3.4")).apply {
        this as ModuleDependency
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
}
kotlin {
    jvmToolchain(25)
}
intellijPlatform {
    pluginConfiguration {
        name.set("ScriptAgent")
        description.set(
            """
            ScriptAgent Kotlin script IDE support: dependency resolution and navigation, @Depends/@Import annotations,
            companion module source linking, stub navigation for exported members, one-click dependency sync.
            为 ScriptAgent 脚本工程提供与 CLI 运行时一致的 IDE 支持。
            """.trimIndent()
        )
        changeNotes.set(
            """
            Alpha public beta: scripts now link to their SA module tree for source visibility; fixed duplicated
            script definition loading (second Config broke dependency resolution); index queries run in smart mode;
            fixed Invalid PSI errors after PSI rebuilds.
            中文：脚本分析按 SA 模块树关联模块；修复脚本定义被加载两次；索引查询统一走 smart 模式；修复 PSI 重建后的异常。
            """.trimIndent()
        )
        vendor {
            name.set("WayZer")
            email.set("himc.wicp@gmail.com")
            url.set("https://github.com/WayZer/ScriptAgent")
        }
        ideaVersion {
            // 插件按 JBR 25 编译（2026.2），旧 IDE 的 JBR 21 加载不了
            sinceBuild.set("262")
            untilBuild.set("262.*")
        }
    }
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
        channels.set(listOf("alpha"))
    }
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-XXLanguage:+ContextParameters"))
}

/**
 * core（SA）仓库：私服 artifact 只取 cf.wayzer:ScriptAgent。
 * 国内走 aliyun 私服（需要凭据），海外走 Cloudflare 镜像（无凭据，方便公开 CI）。
 */
fun RepositoryHandler.saRepository() {
    val inChina = System.getProperty("user.timezone") in arrayOf("Asia/Shanghai", "GMT+08:00")
    maven {
        if (!inChina) {
            url = uri("https://maven.tinylake.top/") // cloudflare mirror
        } else {
            url = uri("https://packages.aliyun.com/maven/repository/2102713-release-0NVzQH/")
            credentials {
                username = System.getenv("SA_MAVEN_USER") ?: "609f6fb4aa6381038e01fdee"
                password = System.getenv("SA_MAVEN_PASS") ?: "h(7NRbbUWYrN"
            }
        }
        content {
            includeModule("cf.wayzer", "ScriptAgent")
        }
    }
}
