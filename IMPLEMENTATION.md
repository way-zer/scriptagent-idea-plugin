# ScriptAgent IDE Plugin - 实现设计

## 一、背景知识

### SA 框架核心架构

- 每个 `.kts` 文件有独立的 `ScriptClassLoader`
- ClassLoader 链：自己的 JAR → 父脚本 → 祖脚本 → ... → Library JAR
- 依赖通过 `@file:Depends` 声明，library 通过 `@file:Import` 声明
- 脚本间类型互通通过 inject（`@Depends(name, inject="prop")`）实现
- 每个模块目录有 `module.kts` 作为依赖/导入的声明中心，子脚本自动继承

### 关键注解语义

```kotlin
@file:Depends("coreLibrary")                    // 硬依赖，影响 classpath
@file:Depends("optional", soft = true)          // 软依赖，仅影响加载顺序
@file:Depends("ban", inject = "banService")     // 注入依赖脚本实例为属性
@file:Import("com.guava:guava:30.1", mavenDepends = true)  // Maven 依赖
@file:Import("arc.Core", libraryByClass = true)            // 按类名反查 JAR
@file:Import("wayzer.lib.*", defaultImport = true)         // 默认 import
```

`@Import` 类型标志（互斥，只能设一个为 true）：
- `libraryByClass` — 按类名反查 JAR
- `libraryByClassLoader` — 按 ClassLoader 查找
- `libraryByName` — 按库名查找
- `libraryByFile` — 按文件路径查找
- `sourceFile` — 源文件依赖
- `mavenDepends` — Maven 坐标依赖
- `mavenDependsSingle` — 单个 Maven 依赖（不传递）
- `mavenRepository` — Maven 仓库地址
- `defaultImport` — 自动 import 的包/类
- `compileArg` — 编译参数

### IntelliJ 中 .kts 的特殊地位

```
常规 .kt：Project → Module → SourceSet → classpath（模块级共享）
.kts 脚本：Project → Module 提供基线(JDK + stdlib)
         ↘ ScriptConfiguration（平行层，每文件可不同）
               ├── ScriptDefinition（SAScript 模板）
               ├── ScriptDependencies（per-file 动态依赖）
               └── ImplicitImports（来自 @Import(defaultImport=true)）
```

## 二、架构概览

插件采用 **FileBasedIndex + ScriptConfiguration 双通道** 架构：
- FileBasedIndex 负责持久化索引注解数据（轻量、增量）
- ScriptConfiguration（IDEMode）负责传递依赖递归解析、classpath/imports 注入
- K2 KaResolveExtension 负责 inject 属性的 Stub 合成

### 数据流

```
项目打开 → IntelliJ 从磁盘加载 SAScriptIndex（不重扫）
         → 文件内容变化时 FileBasedIndex 增量重索引该文件

用户编辑 .kts 文件
  │
  ├→ 通道 1：ScriptConfiguration（传递依赖 + imports + classpath）
  │    ├→ IDEMode.beforeCompilingIDEMode 触发
  │    ├→ ideMetadataSource hook → SAIndexerService 从 FileBasedIndex 读
  │    ├→ refineForDepsIDEMode() 递归展开传递依赖
  │    └→ refineForImportSources() 解析 library classpath
  │
  ├→ 通道 2：KaResolveExtension（inject stub 合成）
  │    ├→ SAResolveExtensionProvider 为当前脚本的 inject 依赖生成 stub
  │    └→ StubCacheService 提供 @Export 成员签名
  │
  └→ IntelliJ 类型解析引擎综合信息 → 补全、导航、错误检查
```

### ideMetadataSource hook 机制

`ScriptAgentStartup`（ProjectActivity）启动时设置 `IDEMode.ideMetadataSource = ::findMetadata`。

`findMetadata(root: File, info: ScriptInfo)` 的查找流程：
1. 通过 `LocalFileSystem` 找到 root 对应的 VirtualFile
2. 遍历打开的 Project，找到 root 在其 content 中的那个
3. 通过 `SAIndexerService.findScript` + `getInfo` 获取索引数据
4. 构造 `SACompiledScript`，填充 dependsData/importData/fqName
5. IDEMode 拿到后自动递归处理传递依赖

## 三、模块结构

```
src/main/kotlin/cf/wayzer/scriptagent/ideaplugin/
├── index/                     # 索引基础设施
│   ├── ScriptIndexer.kt       # FileBasedIndexExtension 实现
│   ├── SAIndexerService.kt    # 索引查询服务 + ScriptIndexInfo 数据结构
│   ├── PsiAnnotationExtractor.kt  # PSI 注解提取器
│   └── util.kt                # resolveRootAndId + ScriptFileFilter
├── navigation/                # 导航与校验
│   ├── ScriptDependsReferenceContributor.kt  # @Depends 字符串引用（Ctrl+Click + 补全）
│   ├── ScriptAnnotationAnnotator.kt         # @Depends/@Import 校验
│   └── ExportLineMarkerProvider.kt          # @Export 标记 gutter icon
├── stub/                      # Stub 合成（inject 属性）
│   ├── SAResolveExtensionProvider.kt  # K2 KaResolveExtension，生成 stub 类
│   ├── StubCacheService.kt            # Analysis API 渲染 @Export 成员签名缓存
│   └── StubRefreshLineMarkerProvider.kt  # inject 行 gutter 刷新按钮
├── toolwindow/
│   └── ScriptIndexToolWindow.kt  # 调试用 ToolWindow，展示索引和 stub
├── SAScriptProvider.kt        # ScriptDefinitionsProvider，注册 SA 到 IDE
├── SAScriptDependenciesProvider.kt  # ScriptAdditionalIdeaDependenciesProvider（预留）
└── ScriptAgentStartup.kt     # ProjectActivity，挂载 ideMetadataSource hook
```

## 四、核心设计选型

### 4.1 索引方案：PSI 提取（非 regex）

通过 `KtFile.fileAnnotationList` 的 PSI 树提取注解参数。原因：
- 不会因字符串格式变化、注释、换行、多行参数而误匹配
- `FileContent.psiFile` 在索引期间可用，性能开销可接受
- 直接复用框架定义的 `DependsData` / `ImportData` 类型，避免重复维护

### 4.2 依赖图：复用 ScriptConfiguration 递归（无独立依赖图）

不维护独立的 `DependencyGraph` 缓存层。传递依赖、defaultImport、library classpath 全部由 IDEMode 已有的 `refineForDepsIDEMode` 递归逻辑处理。插件仅提供索引数据源（`ideMetadataSource` hook）。

### 4.3 Stub 合成方案：KaResolveExtension + @Export

采用 K2 Analysis API 的 `KaResolveExtensionProvider` 机制，为带 `inject` 的依赖脚本生成虚拟 stub 类文件。Stub 内容基于目标脚本中标注 `@Export` 的成员声明。

关键决策：
- 使用 `@Export` 注解标识脚本的 public API
- Stub 类 FQN 格式为 `scripts.<模块>.<类名>`，如 `math/testUtil` 脚本 → `scripts.math.TestUtil`
  - 由 `deriveScriptFqName(scriptId)` 计算：package = `"scripts/$scriptId"` 去掉最后一段后 `/` 换 `.`，类名 = `NameUtils.getScriptNameForFile(scriptId).identifier`（首字母大写）
- Stub 内容 = 目标脚本中标注 `@Export` 的成员声明，渲染为 abstract 签名
- 成员签名通过 `StubCacheService` 使用 Analysis API `KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES` 渲染，无 body/initializer，加 `abstract` 前缀
- Stub 缓存需手动刷新（gutter icon 触发 `refreshAsync`）
- 注意：`SAResolveExtension` 从当前脚本的 PSI 直接提取 inject 依赖（`PsiAnnotationExtractor.extractDepends`），不经过 FileBasedIndex

### 4.4 SAScriptDependenciesProvider：当前为空实现

实现 v1 版 `ScriptAdditionalIdeaDependenciesProvider`（`org.jetbrains.kotlin.idea.core.script.v1` 包），接口方法返回 `Module` 和 `Library`，当前均返回空列表。原因：
- SA 脚本在单 IntelliJ module 内，跨脚本可见性由 ScriptConfiguration 处理
- 保留接口作为后续多 module 布局的扩展点

### 4.5 脚本根目录识别

`resolveRootAndId`（util.kt）通过向上遍历目录查找标志来确定脚本根：
- 目录名为 `scripts`
- 目录名以 `@` 开头
- 目录下存在 `.SARoot` 标记文件

脚本 ID 由相对路径计算，使用 `Config.idSeparator`（`/`）连接，去除 `Config.scriptSuffix`（`.kts`）和 `Config.moduleIdSuffix`（`/module`）。

### 4.6 Module 自动推断

`SAIndexerService.getDepends()` 自动附加最近的 `module.kts` 依赖：通过 `Config.getModulesById` 获取路径层级中的所有 module 候选，取 `asReversed()` 后找第一个存在的作为隐式依赖；同名时显式 `@Depends` 覆盖隐式 module 项（与运行时 `handleOnCollectingData` 一致）。

`ScriptAgentStartup.findMetadata` 必须走 `getDepends()` 而不是 `ScriptIndexInfo.depends`，否则经 `ideMetadataSource` 解析出来的依赖脚本（`refineForDepsIDEMode` 的递归对象）会丢掉 module，导致继承不到 module 的 defaultImport/compileArg/Maven 依赖 —— 这正是 `.metadata` 路径（含 module）与插件路径不一致的点。module 在查询期解析而非索引期写入索引值：判断 module 脚本是否存在需要索引本身，索引期间不可重入。

## 五、Extension Points 注册

| Extension Point | 实现类 | 说明 |
|----------------|--------|------|
| `com.intellij.fileBasedIndex` | `ScriptIndexer` | 索引 @Depends/@Import/@Implement |
| `com.intellij.postStartupActivity` | `ScriptAgentStartup` | 挂载 ideMetadataSource |
| `com.intellij.toolWindow` | `ScriptIndexToolWindow` | 调试面板 |
| `com.intellij.psi.referenceContributor` | `ScriptDependsReferenceContributor` | @Depends 导航+补全 |
| `com.intellij.annotator` | `ScriptAnnotationAnnotator` | @Depends/@Import 校验 |
| `com.intellij.codeInsight.lineMarkerProvider` | `ExportLineMarkerProvider` | @Export gutter icon |
| `com.intellij.codeInsight.lineMarkerProvider` | `StubRefreshLineMarkerProvider` | inject 刷新 icon |
| `org.jetbrains.kotlin.scriptDefinitionsProvider` | `SAScriptProvider` | 注册 SA 脚本定义 |
| `org.jetbrains.kotlin.scriptAdditionalIdeaDependenciesProvider` | `SAScriptDependenciesProvider` | 预留 |
| `org.jetbrains.kotlin.fir.extensions.firExtensionRegistrar` | `MyFirRegistrar` | @Implement FIR 检查（来自框架） |
| `org.jetbrains.kotlin.kaResolveExtensionProvider` | `SAResolveExtensionProvider` | inject stub 合成 |

## 六、索引数据结构

```
Key:   String（脚本 ID，如 "math/calc"）
Value: ScriptIndexInfo
         ├── id: String
         ├── depends: List<DependsData>   // 框架类型，含 name/desc/inject/soft
         ├── imports: List<ImportData>     // 框架类型，含 type/value
         ├── implements: List<String>      // @Implement 的接口 FQN
         └── isModule: Boolean            // 文件名为 module.kts
```

序列化：Java `ObjectOutputStream`（通过 kotlin-plugin 的 `writeObject`/`readObject` 辅助）。版本号当前为 9。

`PsiAnnotationExtractor.inferType`：在无显式类型标志时启发式推断 `ImportData.Type`：
- 含 2+ 个 `:` → `MavenDepends`
- `http://`/`https://` 开头 → `MavenRepository`
- 含 `.*` → `DefaultImport`
- `-` 开头 → `CompileArg`
- 其他 → `LibraryByClass`

## 七、Stub 合成流程

1. `SAResolveExtensionProvider.provideExtensionsFor` 对每个 `KaScriptModule` 触发
2. 从当前脚本 PSI 的 `@file:Depends(..., inject="xxx")` 注解提取有 inject 的依赖 ID
3. 为每个依赖 ID 创建 `SAStubFile`（`KaResolveExtensionFile`）
4. `SAStubFile.buildFileText()` 从 `StubCacheService` 获取缓存的 @Export 成员签名
5. 生成格式：`package <pkg>\nabstract class <ClassName> {\n    abstract <member>\n}`
6. 导航：`SANavigationTargetsProvider` 按名称将 stub 成员映射回原始脚本文件的对应声明

### StubCacheService 工作方式

- 使用 `ConcurrentHashMap<scriptId, List<String>>` 缓存
- `refreshAsync`：CoroutineScope launch 异步 readAction 生成签名
- `refreshSync`：同步生成（用于 ToolWindow）
- 签名生成：找 `@Export` 标注的 KtNamedFunction/KtProperty → Analysis API `render(STUB_RENDERER)` → 加 `abstract` 前缀、去 `public`/`internal` 前缀

## 八、实现状态

### Phase 1：替代 .metadata + Per-file 依赖解析 — ✅ 已完成

- ScriptIndexer（FileBasedIndex）
- ScriptIndexInfo + Externalizer
- PsiAnnotationExtractor
- SAIndexerService
- ScriptAgentStartup + ideMetadataSource hook
- SAScriptProvider（ScriptDefinitionsProvider）
- ScriptIndexToolWindow

### Phase 2：导航与检查 — ✅ 已完成

- ScriptDependsReferenceContributor（Ctrl+Click + 补全）
- ScriptAnnotationAnnotator（@Depends 校验 + @Import 标签检查）
- ExportLineMarkerProvider（@Export gutter icon）
- ImportTypeQuickFix：未实现（低优先级）

### Phase 3：inject Stub 合成 — ✅ 基本完成

- SAResolveExtensionProvider（KaResolveExtension）
- StubCacheService（Analysis API 签名渲染，project 级持久化）
- 首屏后台生成：resolve 时缓存未命中（`getCached == null`）→ `requestRefresh` 后台生成（一次/会话）；当前分析保持未解析（红字），生成完成后重分析。缓存为 `emptyList` 表示已生成且无 `@Export`，不重复请求；`attempted` 集合防止 resolve 期间回环
- 自动失效：project 级 `PsiTreeChangeListener`（`ScriptAgentStartup` 强制创建 service 以尽早注册）监听 `.kts` 变化 → debounce 300ms → `invalidate` + `requestRefresh`
- 刷新后重分析：`scheduleRestart` debounce 150ms → `DaemonCodeAnalyzer.restart()`，让消费方重读 stub
- StubRefreshLineMarkerProvider（手动强制刷新）
- Sync 有序刷新：`DownloadDepsAction` 用 `refreshAndAwait` 等 stub 生成完成再 `reloadScriptData`，消除 fire-and-forget 竞态
- inject 参数值补全：未实现

### Phase 4：Maven 依赖解析 — ✅ 已完成

- IDE classpath 收集：`IDEMode.refineForDepsIDEMode` 对每个依赖递归调用 `ManagerImpl.importToLibraries(script, offline = true)`，只把本地已存在的 JAR `addClassPath`（不联网）
- `MavenLibraryService`：Project-level service，Sync 时下载该脚本及其传递（非 soft）依赖的 Maven 依赖
- 入口：编辑器浮窗 "Sync Script Dependencies" 按钮（`DownloadDepsAction` + `DepsFloatingToolbarProvider`）
- `ScriptAgentStartup.execute` 直接设置 `Config.libraryDir = <projectRoot>/libs`；`Config.rootDir` 故意不设，IDE 一旦用到会立刻抛错暴露依赖

### Phase 5：companionSources kt 模块支持 — 待实现

- 为 companion `.kt` 文件建立正确的 IDE 模块/classpath 配置

### Phase 6：高级特性 — 待实现

- 依赖图可视化 ToolWindow
- 脚本 ID 重命名级联
- ScriptStructureView

### Phase 7：运行时 @Export Interface 生成 — 待实现

- `deriveScriptFqName` 移至框架 main 源集
- KCP `ExportInterfaceIrExtension`：IR 阶段生成 interface + script class implements
- `SAConfiguration` 中 `providedProperties` 类型改为 interface FQN
- ABI hash 机制（metadata 字段 + cache 失效判断）
- 热重载时级联重载 inject 消费方

## 九、运行时 @Export Interface 方案设计

### 9.0 概述

IDE 和运行时的 inject 机制统一使用 interface 类型。脚本中 `@Export` 标注的成员会被提取为一个 interface，脚本类在编译时 implements 该 interface。消费方通过 `providedProperties` 拿到 interface 类型的引用。

**FQN 约定**：`scripts.<module>.<ClassName>`（由 `deriveScriptFqName(scriptId)` 计算），IDE 和运行时共用同一算法。

### 9.0.1 编译流程（产出方 A，有 @Export）

```
FIR 阶段: 正常编译，无特殊处理
IR 阶段 (ScriptLoweringExtension 先执行): IrScript → IrClass(origin=SCRIPT_CLASS)
IR 阶段 (ExportInterfaceIrExtension 后执行):
  1. 找到 IrClass(origin=SCRIPT_CLASS)
  2. 扫描 declarations 中标了 @Export 注解的函数/属性
  3. 创建 IrClass(kind=INTERFACE, FQN=deriveScriptFqName(scriptId))
     - 为每个 @Export 成员生成 abstract 签名
  4. 创建 IrFile 容纳 interface，加入 moduleFragment.files
  5. scriptClass.superTypes += interfaceType
  6. 为匹配成员设置 overriddenSymbols → 指向 interface 的 abstract 方法
  7. JVM 后端 codegen → 输出 interface .class + script .class（带 implements + bridge）
  8. 一起写入 cache JAR
```

**关键**：
- 注册顺序保证：scripting plugin 的 `ScriptLoweringExtension` 总是先于用户 KCP 执行（scripting isolated compilation path 中 `ScriptingK2CompilerPluginRegistrar` 显式先注册）
- `overriddenSymbols` 的设置使 JVM 后端自动生成 bridge method（泛型擦除、协变返回等）
- 不需要 `FirDeclarationGenerationExtension`（scripting 编译路径不调用 `createFilesWithGeneratedDeclarations`，FIR 生成的声明不会进入 IR/输出）

### 9.0.2 编译流程（消费方 B）

```
A 的 JAR 已在 classpath → interface .class 直接可见（正常 classpath 解析）
providedProperties: "propName" -> KotlinType("scripts.math.TestUtil")
FIR 正常解析类型，无需额外 KCP 处理
正常编译完成
```

### 9.0.3 运行时加载

```
ScriptClassLoader(A): 加载 A 的 JAR → 脚本 class implements interface ✓
ScriptClassLoader(B): 引用 interface 类型 → 通过 parentClassloaders(A) 解析
instantiate(B): 传入 A 实例（已 implements interface），JVM 直接 virtual dispatch ✓
```

- `ManagerImpl.instantiate()` 逻辑不变：仍然传入 `Script` 实例，但构造函数参数类型为 interface，JVM 类型兼容

### 9.0.4 ABI Hash 与缓存失效

- **产出方**：编译完成后，根据 interface 成员签名（函数名+参数类型+返回类型，属性名+类型+mutability，排序后）计算 hash，存入 metadata `ABI_HASH` 字段
- **消费方**：编译时记录所有 inject 依赖的 ABI hash（`DEPS_ABI: depName=hash`）
- **缓存判断**：`isCacheUpdate` 增加检查——遍历 `DEPS_ABI`，与依赖当前 `ABI_HASH` 比对，不匹配则 cache 失效触发重编译
- ABI hash 可整合到现有 `sourceMd5` 体系中

### 9.0.5 热重载

- A 重载但 @Export 签名没变（ABI hash 不变）→ B 不需要重编译，但 ClassLoader 重建使旧 interface Class 对象失效
  - A 的 JAR 包含 interface .class，A 的 ClassLoader 重建后 interface 是新 Class 对象
  - B 持有旧 interface 引用 → 需要 B 也重载
  - **结论**：当 A 重载时，所有依赖 A（且有 inject）的脚本 B 也需要重载（但不需要重编译）
- A 的 @Export 签名变了 → ABI hash 变 → B 需要重编译 + 重载

### 9.0.6 IDE 侧对齐

- IDE `SAResolveExtensionProvider` 生成的 stub FQN 已经是 `scripts.<module>.<ClassName>`，与运行时 interface FQN 一致
- IDE `providedProperties` 类型也用同一 FQN（`IDEMode.beforeCompilingIDEMode` 中 `findScriptMetadata(it.name).fqName`）
  - 需要确保 `fqName` 在 IDE metadata 中也使用 interface FQN（`deriveScriptFqName`）—— 当前已是如此（`ScriptAgentStartup.findMetadata` 设置 `fqName = deriveScriptFqName(...)`）
- Stub 内容（成员签名）由 `StubCacheService` 提供，格式为 `abstract fun/val ...`，与运行时 interface 内容语义一致

### 9.0.7 实现变更清单

| 组件 | 变更 |
|------|------|
| `deriveScriptFqName` | 从 idePlugin 移至框架 `main` 源集（如 `Config` 或 `util`），KCP 和 IDE 共用 |
| KCP `MyRegistrar` | 新增 `ExportInterfaceIrExtension`（`IrGenerationExtension`）|
| `ExportInterfaceIrExtension` | IR 阶段生成 interface + 设置 script class implements + overriddenSymbols |
| `SAConfiguration.handleCompile()` | `providedProperties` 类型从 `compiled.fqName`（脚本类 FQN）改为 `deriveScriptFqName(depId)` 得到的 interface FQN |
| `ScriptCache` metadata | 新增 `ABI_HASH` 字段（产出方）、`DEPS_ABI` 字段（消费方）|
| `ScriptCache.isCacheUpdate` | 增加 DEPS_ABI 比对逻辑 |
| `ScriptManager` 热重载 | A 重载时级联重载有 inject 依赖的消费方 |
| IDE 侧 | 无需改动（FQN 已对齐）|

### 9.0.8 没有 @Export 成员的脚本

如果脚本没有 @Export 成员：
- 不生成 interface
- 不允许对该脚本使用 inject（或 inject 类型 fallback 为 `Script` 基类）
- 需要在编译时报错或警告

---

## 十、其他待实现功能设计

### 10.1 Maven 依赖解析（Phase 4）— ✅ 已实现

**方案**：
- IDE classpath 收集：`IDEMode.refineForDepsIDEMode` 对依赖递归调用 `ManagerImpl.importToLibraries(script, offline = true)`（走 `DependencyBuilder.offline`，cache-only，不联网），把已存在的 JAR `addClassPath`
- `MavenLibraryService`：Project-level service，`DownloadDepsAction` 触发，下载该脚本及其传递（非 soft）依赖的 Maven 依赖
- 入口：编辑器浮窗 "Sync Script Dependencies"（`DepsFloatingToolbarProvider`），不是 ToolWindow 按钮
- JAR 缓存位置：`<projectRoot>/libs`（固定约定）。只有当运行时的 scripts 根就在 project root 下时，才与运行时共享同一缓存目录
- `ScriptAgentStartup.execute`：固定 `Config.libraryDir = <projectRoot>/libs`（Maven 解析链 `DependencyManager`/`Dependency`/`MavenResolver` 的硬依赖），不做 scripts 根扫描。`Config.rootDir` 不初始化——IDE 分析不应依赖它，若某条路径意外访问，`lateinit` 抛错即可发现该依赖。`libraryDir` 在安装 `ideMetadataSource` hook **之前**设置，故分析被调用时必然已就绪。简化假设：`libs` 与 project root 同级（与 testProject 布局一致），scripts 根嵌套更深时不适用。

**剩余 TODO**：
- [x] 下载后触发 ScriptConfiguration 重新分析：`DownloadDepsAction` → `ReloadScriptConfigurationService.reloadScriptData`

### 10.2 companionSources kt 模块支持（Phase 5）

**问题**：运行时 `SAConfiguration.registerCompanionSource()` 将以下 `.kt` 文件作为 companion sources 与宿主脚本一起编译：
- 同目录下 `<scriptBaseName>.<xxx>.kt` 格式的文件（如 `calc.helper.kt` 对应 `calc.kts`）
- module.kts 的情况下，同目录 `lib/` 下所有 `.kt` 文件

这些 `.kt` 文件在运行时与宿主脚本共享 classpath（包括宿主的传递依赖和 library JAR）。但在脱离 Gradle 的纯插件模式下，这些 `.kt` 文件是离散的——IDE 不知道它们属于哪个编译单元，也不知道它们的 classpath 应该是什么。

**需求**：
- 这些 `.kt` 文件需要能看到与宿主 `.kts` 一样的 libraries（Maven JAR、libraryByClass 等）
- 需要能看到 Stubs（inject 合成的 abstract class）
- 补全、导航、错误检查应正常工作

**方案方向**：
- 可能需要为 scripts 目录创建一个 IDEA Module（通过 `AdditionalLibraryRootsProvider` 或程序化创建 `ModuleEntity`），将 companion `.kt` 纳入其 source root
- 该 Module 的 classpath 需要动态配置，与宿主脚本的 ScriptConfiguration 对齐
- 或者利用 `ScriptAdditionalIdeaDependenciesProvider` 让 companion `.kt` 关联到正确的依赖

**TODO**：
- [ ] 调研 IntelliJ 中为离散 .kt 文件提供自定义 classpath 的机制（Module vs Library roots vs 其他）
- [ ] 确定 companion .kt 如何发现其宿主脚本（文件名约定 / 目录约定）
- [ ] 确保 companion .kt 能访问 Stubs 和 Maven libraries
- [ ] 与 Maven 依赖解析（9.1）协调——companion .kt 的 classpath 依赖 Maven JAR 已下载

## 十一、已知限制与 TODO

1. **Stub 刷新策略**：`.kts` 变化后 debounce 300ms 自动失效并重新生成，完成后重启 daemon 让消费方重读；即使 `@Export` 未变也会重算（未做签名 diff）。无 `@Export` 的脚本缓存为空列表，不会反复请求。

2. **SAScriptDependenciesProvider 为空**：跨脚本源码关联完全依赖 ScriptConfiguration 通道。如果 K2 分析引擎未正确识别跨脚本类型引用，可能需要启用此通道。

3. **Import 类型推断启发式**：`PsiAnnotationExtractor.inferType` 在无显式标志时按字符串格式推断，需与框架运行时行为保持一致。

4. **companion .kt 文件无 IDE 支持**：脱离 Gradle 后这些文件将变成离散文件，IDE 无法为其提供正确的 classpath 和类型解析。

5. **slf4j 被错误加入 classpath**：IDE 插件当前把 IDEA 自带的 slf4j 意外暴露到脚本 classpath 中，需要排查 `IDEMode` 中 `classpathFromClassloader` 的过滤逻辑。

6. **不存在的 @Depends 导致整个文件分析失败**（上游限制）：`FirScriptConfiguratorExtensionImpl.configure` 中 `getOrLoadConfiguration` 的结果通过 `valueOr` 处理——`Failure` 会 `addErrorElement + return` 导致整个文件停止分析，而 `Success` 带的 diagnostics 会被完全忽略。因此无法通过 ScriptConfiguration 通道报告部分错误（如"依赖 X 未找到"）同时保持文件可用。当前 `ScriptAnnotationAnnotator` 已独立检查不存在的 `@Depends` 并报错，但 `refineForDepsIDEMode` 中 FATAL 仍会导致文件分析中断。可能的解决路径：
   - 等待上游（Kotlin scripting 框架）支持 Success diagnostics 的展示
   - 或自行注册 `FirScriptConfiguratorExtension`（`firstOrNull { accepts }` 机制允许替代默认实现），在其中正确处理 Failure/diagnostics
   - 相关源码：`FirScriptConfigurationExtensionImpl.kt:88`、`k2RefinementUtil.kt`、`AbstractRawFirBuilder.kt:1417`

## 十二、与 Gradle 的关系

插件消除了对 `.metadata` 的依赖。IDE 依赖视图完全由注解驱动，不再需要 Gradle `implementation(project(...))` 源集依赖。

长期目标是完全脱离 Gradle 进行开发（包括 companion .kt 文件），由插件独立管理所有依赖和编译配置。

如 IDE 插件不可用（CI 环境），下游项目仍可保留 `.metadata` 手动维护能力作为后备。
