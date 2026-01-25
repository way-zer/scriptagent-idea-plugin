plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "cf.wayzer.scriptAgent"

repositories {
    mavenCentral()
    maven(url = "https://www.jitpack.io")

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform{
//        intellijIdea("2025.3.1")
        local("C:\\Users\\q1048\\AppData\\Local\\Programs\\IntelliJ IDEA Ultimate")
        bundledPlugin("org.jetbrains.kotlin")
//        bundledLibrary("kotlin.base.analysis-api")
//        bundledLibrary("kotlin.base.scripting")
    }
    implementation(rootProject)?.apply {
        this as ModuleDependency
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
}
kotlin { jvmToolchain(17) }
intellijPlatform {
    pluginConfiguration {
        name.set("ScriptAgent")
        ideaVersion {
            sinceBuild.set("253") // 调整为更通用的版本，如 2024.1
            untilBuild.set("253.*")
        }
    }
}