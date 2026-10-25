rootProject.name = "idePlugin"

// 本地开发：同级目录存在 core 仓库（ScriptAgent）时用 composite build 直接编译源码，
// 不需要先把 core 发布到私服。CI/发布：设置 CI=true（或不放同级 core），改用私服上的 core 制品，
// 通过 -PsaVersion=<version> 指定版本。
val coreRepo = File(settingsDir.parentFile, "ScriptAgent")
if (System.getenv("CI") == null && coreRepo.isDirectory && startParameter.projectProperties["noCompositeCore"] == null) {
    includeBuild(coreRepo)
}
