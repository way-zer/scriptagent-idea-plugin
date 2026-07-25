# IDE Plugin 手动测试项目

用于验证 ScriptAgent IDE 插件各功能是否正常工作。

## 前置条件

1. 在 IntelliJ 中以 Gradle 项目打开 `idePlugin/` 并运行 `runIde` 任务
2. 在启动的沙箱 IDE 中打开本目录（`testProject/`）作为项目
3. 等待索引构建完成（底部状态栏 "Indexing" 消失）

## 测试步骤

主要测试文件：**`math/calc.kts`**（打开这一个文件即可验证大部分功能）

### 打开 `math/calc.kts` 后检查：

| # | 功能 | 验证方式 | 预期 |
|---|------|---------|------|
| 1 | @Depends 导航 | Ctrl+Click `"math"` | 跳转到 `math/module.kts` |
| 2 | @Depends 导航 | Ctrl+Click `"math/testUtil"` | 跳转到 `math/testUtil.kts` |
| 3 | @Depends 校验 | 观察 `"nonexistent/script"` | 标红，提示 "未找到脚本" |
| 4 | @Import 校验 | 观察第 5 行 `@Import("bad", ...)` | 警告 "多个导入类型" |
| 5 | inject stub | 输入 `mathModule.` 触发补全 | 列出 `add`, `multiply` |
| 6 | inject stub | 输入 `util.` 触发补全 | 列出 `hello` |
| 7 | defaultImport | 观察 `sqrt` 调用 | 不标红（来自 math/module 的 `kotlin.math.*`） |
| 8 | Maven 依赖 | 观察 `Gson` 引用 | 下载前标红，下载后正常 |
| 9 | gutter icon | 观察 inject 行左侧 | 有刷新 stub 按钮 |
| 10 | @Depends 补全 | 新增 `@file:Depends("")`，光标在引号内按 Ctrl+Space | 列出所有脚本 ID |

### 补充检查（其他文件）：

| # | 文件 | 功能 | 预期 |
|---|------|------|------|
| 11 | `math/module.kts` | @Export gutter icon | `add` 和 `multiply` 旁有标记 |
| 12 | `math/testUtil.kts` | Show Stub (ToolWindow) | 显示 `abstract fun hello(name: String)` |
| 13 | ToolWindow | "Download Maven Deps" 按钮 | 异步下载 gson JAR → 刷新后第 8 步通过 |

### companionSources（Phase 5，待实现）：

| 文件 | 说明 |
|------|------|
| `math/calc.helper.kt` | 应与 `calc.kts` 共享 classpath |
| `coreLibrary/lib/StringUtils.kt` | 应与 `coreLibrary/module.kts` 共享 classpath |

当前这些 `.kt` 文件可能缺少正确的 IDE 支持，标红是预期的。

## 项目结构

```
scripts/
├── .SARoot                        # 脚本根目录标记
├── core/
│   └── module.kts                 # @Export, defaultImport
├── math/
│   ├── module.kts                 # @Depends("core"), @Export, defaultImport
│   ├── testUtil.kts               # 简单 @Export
│   ├── calc.kts                   # ★ 主测试文件（inject/导航/校验/Maven）
│   └── calc.helper.kt             # companionSources (待实现)
├── coreLibrary/
│   ├── module.kts                 # companionSources (lib/ 目录)
│   └── lib/
│       └── StringUtils.kt         # module 的 companion source (待实现)
└── libs/                          # Maven JAR 缓存目录（自动生成）
```

## 清理 Maven 缓存

删除 `scripts/libs/` 目录可重置 Maven 下载状态，用于测试"下载前标红"场景。
