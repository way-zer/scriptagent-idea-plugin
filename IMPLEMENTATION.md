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

**调研结论（2025.3 / K2）**：
- `KaScriptModule` 只代表单个 `.kts`（`KaScriptModuleImpl.getFile()`）；`KotlinScriptEntity` 只有 `virtualFileUrl` + `configurationId`，配置里的 `addSources` 在 IDE 不参与 → companion `.kt` 只是所在 IntelliJ 模块的普通源文件。
- `ScriptAdditionalIdeaDependenciesProvider` 仅被 `KaScriptModuleImpl` 消费 → 只能给脚本加依赖，给不了普通 `.kt`。
- `AdditionalLibraryRootsProvider`/`SyntheticLibrary` 的库不进入 `OrderEnumerator`（模块 classpath）→ 给不了 classpath。
- **可用钩子**：EP `org.jetbrains.kotlin.k2KaModuleFactory`（`FirKaModuleFactory.createKaModuleByPsiFile`）。`CandidateCollector` 遍历所有实现，返回非 null 即成为该文件的模块候选。

**已尝试并失败：KA module hook（勿重复）**
- EP `org.jetbrains.kotlin.k2KaModuleFactory` 的 `createKaModuleByPsiFile` 确实是「按文件定模块」的入口（`CandidateCollector` 遍历它，非空即成候选），但 KA 内部要求**具体实现类**：
  - 返回自定义 `KaModule` → `does not have a corresponding resolution strategy`；
  - 返回自定义 `KaSourceModule` wrapper → `K2IDEProjectStructureProvider.getOpenapiModule` 里 `Expected class KaSourceModuleBase, but got CompanionModule`；
  - 返回宿主 `KaScriptModule` 的 wrapper → 同一文件被两个模块拥有（split-brain），自身分析出现 `INVISIBLE_REFERENCE`（私有跨模块）。
- 结论：**插件层无法给普通 `.kt` 注入自定义 `KaModule`**；只有 Kotlin 插件自带的脚本工厂能造出 `KaScriptModuleImpl`/`KaSourceModuleBase` 这些被内部硬转换依赖的具体类。

**已实现：工程模型建 IntelliJ Module（Phase 5 选项 b）**
- 实现（`companion/SAModuleLibraries`）：扫描 `module.kts` 同目录 `lib/`（含 `.kt`），用 `ModuleManager.getModifiableModel().newModule(<project>/.idea/SA-<id>.iml, "JAVA_MODULE")` 建模块，content root = SA 模块目录、source root = `lib`，模块级 Library 挂宿主脚本的 Maven/`libraryByClass` 依赖 + 框架 classes，再 `inheritSdk()`。
- **时机是关键**：平台在工程打开后会异步把 JPS 模型同步进 workspace model（`JpsProjectModelSynchronizer - Attempt 1: Apply JPS storage (iml files)` → `ProjectSynchronizerUtilImpl.applyJpsModelToProjectModel`）。这次同步以 JPS storage（`.iml` + `.idea/modules.xml`）为准，**早于它的工程模型改动会被整轮覆盖**——现象就是模块「出现一下又消失」（连续 3 个 session 都在 attach 后 ~5s 被清掉）。
- 因此 `ScriptAgentStartup`（`postStartupActivity`）先 `(WorkspaceModel.getInstance(project) as WorkspaceModelInternal).awaitSynchronizationWithJpsModel()` 等同步完成，再 `SAModuleLibraries.install()`。这个等待只是协程挂起，不阻塞任何东西：平台的 `postStartupActivity` `ProjectActivity` 是 `launch` 出去的（`StartupManagerImpl` 只把 Job 记进 `runningProjectActivities`，从不 join），`allActivitiesPassed` 在所有活动**派发完毕**时就 complete。`JpsProjectLoadingManager.jpsProjectLoaded` 已废弃（废弃信息指向的就是上面这个 API），勿用。
- 佐证：`testProject/.idea/modules.xml` 曾被平台改写（git diff 只少一个行尾换行）却没有 `SA-coreLibrary` 条目；磁盘上不存在 `SA-coreLibrary.iml`。
- 代价：会向用户工程写入 `SA-*.iml` + `modules.xml` 条目（本仓库 `testProject/.idea/modules.xml` 受版本控制），是对用户工程的**持久副作用**。
- **第二关：模块建对了、library 也在，但依赖全红**。模块/内容根/源码根/module-library 全部正确（indexing diagnostic 显示 `Module 'SA-coreLibrary' (coreLibrary)` 扫到 2 个文件），但 `lib/StringUtils.kt` 仍报 `Unresolved reference 'google' / 'Gson' / 'replaceFirstChar' / 'repeat'`。根因：**library 的 CLASSES root 写成了 `file://<path>.jar`，正确写法是 `jar://<path>!/`（archive root）**。`file://...jar` 被 IntelliJ 当成普通文件/目录，classpath 实际为空 —— 所以连 stdlib（本机打包在 `lib/util-8.jar`）都解析不到。修法：`libraryRootUrl()` 里 jar 用 `jar://` + path + `!/`，目录才用 `file://`。
- 装完 `DaemonCodeAnalyzer.restart` 一次。曾给模块补过默认 `KotlinFacetType` facet，真因是 root URL；已去掉并验证不需要（facet 只承载"这个 Module 是 Kotlin 模块 + 编译设置"，不是 classpath 容器）。
- framework classpath 与脚本对齐（对应 `SAConfiguration.baseConfiguration` + `IDEMode` 的几条 `updateClasspath`）：`classpathFromClass<SAScript>()` + `KotlinJars.kotlinScriptStandardJarsWithReflect` + `dependenciesFromClass(CoroutineScope::class)`。这些 File 可能不在 VFS 中，`findFileByIoFile` 会静默返回 null，要用 `refreshAndFindFileByIoFile`。
- **为什么会带进 IDEA 内部 jar（已定位到行）**：framework classpath 逐条对得上——

  | 模块 library 里的 jar | 来源 |
  |---|---|
  | `ScriptAgent-*.jar` | `classpathFromClass<SAScript>()`（含该类的 classpath root） |
  | `lib/util-8.jar` | `KotlinJars.stdlibOrNull` = `getLib("kotlin.java.stdlib.jar", "kotlin-stdlib.jar", JvmStatic::class)` → 含 `kotlin/jvm/JvmStatic` 的 jar |
  | `lib/intellij.libraries.kotlin.reflect.jar` | `KotlinJars.reflectOrNull` → 含 `kotlin/reflect/full/KClasses` 的 jar |
  | `plugins/Kotlin/lib/kotlin-plugin.jar` | `KotlinJars.scriptRuntimeOrNull` → 含 `kotlin/script/templates/standard/ScriptTemplateWithArgs` 的 jar |
  | `lib/intellij.libraries.kotlinx.coroutines.core.jar` | `dependenciesFromClass(CoroutineScope::class)` → 含 `kotlinx/coroutines/CoroutineScope` 的 jar |

  机制：`KotlinJars`（`jvmClasspathUtil.kt`，随 `kotlin-plugin.jar` 发行）是 **host 侧定位器** —— "找出包含类 X 的 jar"，并允许用系统属性 `kotlin.java.stdlib.jar` / `kotlin.java.reflect.jar` / `kotlin.script.runtime.jar` 覆盖。它假设 Kotlin 以独立 jar 发行（standalone scripting host 就是这样），而**本机 IDE 发行版根本没有独立的 `kotlin-stdlib.jar`**：stdlib 被合并进 `lib/util-8.jar`，reflect 在 `intellij.libraries.kotlin.reflect.jar`，script runtime 在 `kotlin-plugin.jar`。所以它没选错，是"如实回答"。
- **关键结论：靠过滤剔不掉**。`lib/util-8.jar` 里同时有 `kotlin/jvm/JvmStatic` 和 `org/slf4j/Logger`（它并不含 `com/intellij/**`，是 IDEA 捆绑的**第三方** jar 杂烩）—— stdlib 与 slf4j 在同一个 jar 里，按 jar 过滤无法分离。这正是 §11.5 "slf4j 被错误加入 classpath" 的真正来源：不是过滤逻辑写错，而是 IDE 模式把 stdlib 定位到了 util-8.jar。要干净，只能不再向 IDE 的 classloader 要 Kotlin（companion module 已按此实现，见下方 TODO）。

**粒度约束：module 只能按目录归属（决定 sibling companion 怎么处理）**
- IntelliJ 工程模型的「归属半径」是 content root（目录）：`ProjectFileIndex.getModuleForFile` 按 content root 判定，最深的赢；content root / source root 本质是目录级索引，**没有「某个文件属于某个 module」这种粒度**。
- 能挂单个文件的地方只有 library roots 和 `SyntheticLibrary`/`AdditionalLibraryRootsProvider`：前者不进模块 `OrderEnumerator`（见上）；后者会让文件变成「库文件」而不是工程源码。都换不到「给单个 `.kt` 独立 classpath」。
- 同一个目录不能同时作为两个 module 的 content root（Project Structure 会报 content root 冲突，归属也不确定）→ **「每个宿主脚本一个 module」在脚本同目录共存时不成立**。这正是早期 `calc.kts`/`calc.helper.kt` 方案走不通的根本原因。
- 结论：**classpath 的粒度上限 = 目录**。sibling `<base>.<xxx>.kt` 只能用「按目录建 module + 该目录脚本依赖的并集」支持（只会多看到别的脚本的依赖，不会误报红）。

**备选（未定）**：
- (a) `HighlightInfoFilter` 屏蔽 companion `.kt` 里的未解析/不可见引用假红：小、不可见、零工程改动；但没有补全/跳转，且会掩盖真实错误。
- (b) 工程模型改动（建专用 Module 配 classpath）：已按上述实现；用户可见、写受版本控制的 `.idea` 文件——与「小 + 不可见」冲突。
- (c) 暂不做，接受 companion 里 classpath 相关的红。
- (d) 在 SA 运行时侧让 companion 成为一等 script（例如统一为 `.kts`）：IDE 侧复用已有的 `KaScriptModule` 机制，零工程模型改动 —— 需改 SPEC，待确认。
- (e) 把 companion 支持从「`module.kts` 的 `lib/`」扩到「按目录」：content root = 脚本目录，classpath = 该目录所有脚本依赖的并集，sibling `.kt` 即可解析（复用现有 `SAModuleLibraries`，只改扫描规则）。

**TODO**：
- [x] 调研 IDE 侧机制（结论见上）
- [x] companion .kt 宿主发现规则（文件名 + lib 目录约定）
- [x] 试做选项 (b)：建 IntelliJ Module（结论：必须等 JPS→workspace 同步完成后再建）
- [x] 验证时机：模块能稳定留存（runIde 23:42 起）
- [x] 验证 classpath：`lib/StringUtils.kt` 的 Gson / stdlib 解析正常（修 `jar://...!/` 后）
- [x] framework classpath 统一：`.kts` 与 companion `.kt` 取同一份。Kotlin runtime = `kotlin-stdlib` / `kotlin-reflect` / `kotlinx-coroutines-core-jvm`（版本取 `Config.kotlinVersion` / `Config.kotlinCoroutineVersion`），走 `DependencyManager` 落到 `<libs>`（`loadOrStore` 已存在即跳过）。**没有** `kotlin-script-runtime`：CLI 由脚本宿主提供、IDE 由 Kotlin 插件提供（`.kts` 若因此报错就补一条 `require`）。
      - 根因回顾：`baseConfiguration` 里本来就有 `updateClasspath(DependencyManager.globalDependency.values...)`，但那一组**只在 CLI 里**被 `ScriptAgent.afterStdLib()`（`addAsGlobal()`）填充；IDE 下 `globalDependency` 为空 → `IDEMode` 只好用 `KotlinJars` + `getResourcePathForClass(CoroutineScope)` 补 Kotlin，两边就此分叉（Kotlin 被定位到 IDE 自带 jar 的来源见上面的表）。
      - 统一形态：`baseConfiguration` 保持原样（那两条 `updateClasspath` 服务 CLI）；`IDEMode` 删掉那两条补丁，改调独立一步 `refineFrameworkClasspath()`；companion 侧取 `IDEMode[ScriptCompilationConfiguration.dependencies] → JvmDependency.classpath`（SA jar）+ 同一个 `resolveKotlinRuntime`。classpath 是读时解析、不吃 `baseConfiguration` 的一次性快照，所以 Sync 完 `reloadScriptData` 当场生效。
      - 落地（最终形态）：**classpath 不再吃 `baseConfiguration` 的快照；但 `globalDependency` 仍要注册**。`baseConfiguration` 保持原样（那两条 `updateClasspath` 服务 CLI 的 globalDependency + SA jar）；`IDEMode` 删掉 `KotlinJars` / `getResourcePathForClass(CoroutineScope)` 两条补丁，改为在 `beforeCompilingIDEMode` 里调独立一步 `refineFrameworkClasspath()`（成员扩展，紧挨着已有的 `scripts/classes` 那条 framework classpath），内部 `addClassPath(runBlocking { resolveKotlinRuntime(offline = true) })`；`resolveKotlinRuntime(offline = false)` 是 `IDEMode` 的成员，按 `offline` 从 `<libs>` 解析并 `addAsGlobal()` 注册 —— **注册这一半必须保留**：`globalDependency` 同时是 `DependencyBuilder.parentDependency`，决定"已 loaded"的依赖在脚本解析时被跳过还是报版本冲突，不填就和 runtime 对不上；`offline` 策略：**没有用户显性操作不允许联网** —— 启动注册（`ScriptAgentStartup`）、refine 分析、companion install 三条隐式路径都显式传 `true`；只有用户点 Sync 的 `MavenLibraryService` 传 `false`。默认值是 `false`（按“suspend 侧本就允许下载”定的），但隐式路径不该依赖它。
      - **时序**：注册这一半必须早于任何依赖解析 —— `ScriptAgentStartup.execute` 里 `Config.libraryDir` 设好之后第一件事就调它（早于 companion install / 其它解析路径）；`.kts` 自己那条 refine 里 `refineFrameworkClasspath()` 又排在 `refineForDepsIDEMode(...)` 之前，所以每条脚本的依赖解析之前都保证注册过。坑：`beforeCompilingIDEMode` 的 body 会被 inline 进 object 的初始化式，直接调成员会报 `Cannot access '<this>' before the instance has been initialized` —— 要像文件里其它 helper 一样 `import IDEMode.xxx` 之后再调用。companion 侧 `SAModuleLibraries.frameworkRoots()` = `IDEMode[ScriptCompilationConfiguration.dependencies] → JvmDependency.classpath`（SA jar）+ `IDEMode.resolveKotlinRuntime(offline = true)`。
      - **联网**：`MavenLibraryService.downloadForScript`（用户点 Sync）在同一 `withBackgroundProgress` 块里**先**调 `IDEMode.resolveKotlinRuntime()`（默认 `offline = false`）**再** `downloadRecursive(...)` —— 注册 baseline 必须排在解析脚本依赖之前；**这是唯一允许联网的调用点**。runtime 部分尽力而为：失败 `try/catch` 后 `errors += "Kotlin runtime: ..."`，不中断脚本依赖。下完由 `reloadScriptData` + `SAModuleLibraries.install` 当场生效。**runtime 引导流程（`ScriptAgent.afterStdLib`）保持原样不动** —— 引导阶段不引用其它类更安全。
      - **Sync 按钮**：浮动工具条上那个 action 按上下文改名 —— `module.kts` 显示 **"Sync Module"**（下载模块依赖 / 刷 stub / 重建 companion 模块 classpath / 重新分析），其它 `.kts` 仍是 "Sync Script Dependencies"。`ScriptIndexToolWindow` 是调试面板，不放用户按钮。
      - 补充：插件已 bundled `kotlin-stdlib-2.3.20.jar`（= `Config.BundleKotlinVersion`，在 `plugins/idePlugin/lib/`），本可直接复用；当前统一走 `DependencyManager` 解析（只有 Sync 会真正下载）
      - 未采用：`-Dkotlin.java.stdlib.jar=...` 等系统属性可让 `KotlinJars` 返回指定 jar，但 JVM 全局、影响整个 IDE
- [x] **（已修）global 里已注册的依赖在文件缺失时 Sync 不会重新下载**（实测重启也无效）：
      - 机理：`DependencyBuilder.require` 撞上 `parentDependency`（= `globalDependency`）里的**同版本**就直接 return（`String.older` 对相等版本返回 true）→ 该依赖进不了 `dependencies` → `getFiles()` / `downloadAll` 轮不到它。启动那步 `addAsGlobal()` 又会把同版本注册回去（`putAll` 在 `resolved()` 之后；offline 下 `resolved()` 的失败被吞，所以照样注册），因此重启无效。
      - 修法（不动 CLI 语义）：`DependencyBuilder` 增加 `skipLoaded: Boolean = true`（与 `offline` 同级的解析策略开关），把"parent 里的同版本必然已在当前 classpath 上"这条隐含假设显式化。CLI 成立（global 来自 `afterStdLib` 实际加载进 classloader 的库）；IDE 的 registration 只是解析 baseline —— offline 解析失败被吞，文件可以压根不存在。因此 `IDEMode.resolveKotlinRuntime` 置 `skipLoaded = false`：依赖照常进 `dependencies`，参与 `resolved()`/`downloadAll`。注意 `skipLoaded = false` **不是忽略 parent** —— 版本仍以 parent（global）里那一条为准，只是不再跳过，好让缺失的文件被下载。
      - 顺带修掉一个更隐蔽的问题：`ScriptAgentStartup` 注册过 global 之后，refine / companion install 再调 `resolveKotlinRuntime(offline = true)` 本来**恒返回空**（三个坐标全被 skip），于是 `refineFrameworkClasspath()` 与 companion 的 framework classpath 实际什么都没加，Kotlin 只能靠宿主 classpath 兜底。现在两条路径都真的拿到 `<libs>` 里的 runtime 文件。
- [x] library `SOURCES` 对接 —— 见 §10.3
- [x] 验证去掉 Kotlin facet 后 companion 仍正常（需先删掉带 facet 的旧 `SA-*.iml`）
- [ ] 决定 sibling companion 走哪条路：(c) 不做 / (d) 改 `.kts` / (e) 按目录建 module

### 10.3 library SOURCES 对接 — ✅ 已实现

**目标**：companion 模块（`module.kts` 同目录 `lib/`）的 module library 除 `OrderRootType.CLASSES` 外补上 `OrderRootType.SOURCES`，Ctrl+Click 直接进源码而不是反编译。

- **触发方式**：不预下载，也没有常驻按钮。打开 `libs/*.jar` 里一个**没有源码的类**（反编译视图）时，平台横幅会列出我们的 **"Download sources (SA)"**，点一下只下这一个依赖。带 "(SA)" 后缀是因为 Maven 插件对同一个类也会摆一个 "Download Sources"（且它解析不了我们的 library），平台自己的 "Choose Sources…" 则无法去掉。
- **SOURCES root 组成**（`SAModuleLibraries`）：对每个 classpath jar 取**同目录同名兄弟** `<name>-sources.jar`（只挂已存在的），不需要知道依赖坐标；jar root 与 CLASSES 一样必须是 `jar://<path>!/`。
- **反向**（jar → 坐标）：`DependencyManager` 在 `load()` 解析完成后把 `dependencies` 记进内部索引（`Artifact`：jar **文件名** → 坐标 + 解析它用的仓库），暴露 `dependencyOfJar(fileName)` / `ensureSources(dep)`。按文件名而不是路径建索引，跨 project 天然一致，插件侧不再维护任何映射表。
- **下载实现**：横幅 action 从 `PsiFile` 取 jar 文件名 → `MavenLibraryService.downloadSources(dependency, callback)` → `DependencyManager.ensureSources`（下载实现只有 `DependencyBuilder.downloadSources` 一份），成功后 `install` 重挂 SOURCES 让横幅消失。
- **平台机制**（不用自己画 UI）：EP `com.intellij.attachSourcesProvider`（`com.intellij.codeInsight.AttachSourcesProvider`，动作实现 `LightAttachSourcesAction`）+ `attachSourcesProviderFilter`；横幅由 `AttachSourcesNotificationProvider`（`plugins/java/lib/modules/intellij.java.ui.jar`）统一渲染 —— 它只对 `JavaClassFileType`（`.class`）生效，按"打开的文件 → 所属 `LibraryEntity`"找库，再调 `getLibrariesActions` / `perform(entities, project)`。Maven 就是这样接的（`MavenAttachSourcesProvider`），Gradle 用 filter 排掉别人的 provider。
- **失败处理**：拉不到的坐标记进 `MavenLibraryService.sourcesUnavailable`（会话内），横幅不再为它出现；已存在的 sources jar 由 `loadOrStore` 直接跳过。
- **未覆盖 / TODO**：
      - `.kts` 自己的 classpath 走 `ScriptCompilationConfiguration`，没有 sources 通道；但它的依赖 jar 同时是 companion library 的 root，所以在 `.kts` 里 Ctrl+Click 到这些类时横幅**可能**同样出现（待 runIde 验证）。
      - 待 runIde 验证：module 级 library 能否被 `findLibraryEntitiesForFile` 命中（Maven 也是 module/project 级，大概率可以）。

## 十一、已知限制与 TODO

1. **Stub 刷新策略**：`.kts` 变化后 debounce 300ms 自动失效并重新生成，完成后重启 daemon 让消费方重读；即使 `@Export` 未变也会重算（未做签名 diff）。无 `@Export` 的脚本缓存为空列表，不会反复请求。

2. **SAScriptDependenciesProvider 为空**：跨脚本源码关联完全依赖 ScriptConfiguration 通道。如果 K2 分析引擎未正确识别跨脚本类型引用，可能需要启用此通道。

3. **Import 类型推断启发式**：`PsiAnnotationExtractor.inferType` 在无显式标志时按字符串格式推断，需与框架运行时行为保持一致。

4. **companion .kt 文件支持范围有限**：目前只处理 `module.kts` 同目录 `lib/` 下的 `.kt`（§10.2 选项 b，靠专用 IntelliJ Module 提供 classpath）；同目录 `<base>.<xxx>.kt` 及其它形式暂不支持。

5. **slf4j 被错误加入 classpath** —— 已随 Kotlin runtime 统一而消失：旧实现用 `KotlinJars` 向 IDE 要 Kotlin，stdlib 被定位到 `lib/util-8.jar`，而该 jar 同时打包了 `org/slf4j/Logger`（按 jar 过滤无法分离）；不是过滤逻辑写错。现在 `.kts` 与 companion 的 Kotlin 都来自 `<libs>`（`IDEMode.resolveKotlinRuntime`），代码里已无 `KotlinJars` / `getResourcePathForClass` 引用（见 §10.2 的根因表，保留作历史记录）。

6. **不存在的 @Depends 导致整个文件分析失败**（上游限制）：`FirScriptConfiguratorExtensionImpl.configure` 中 `getOrLoadConfiguration` 的结果通过 `valueOr` 处理——`Failure` 会 `addErrorElement + return` 导致整个文件停止分析，而 `Success` 带的 diagnostics 会被完全忽略。因此无法通过 ScriptConfiguration 通道报告部分错误（如"依赖 X 未找到"）同时保持文件可用。当前 `ScriptAnnotationAnnotator` 已独立检查不存在的 `@Depends` 并报错，但 `refineForDepsIDEMode` 中 FATAL 仍会导致文件分析中断。可能的解决路径：
   - 等待上游（Kotlin scripting 框架）支持 Success diagnostics 的展示
   - 或自行注册 `FirScriptConfiguratorExtension`（`firstOrNull { accepts }` 机制允许替代默认实现），在其中正确处理 Failure/diagnostics
   - 相关源码：`FirScriptConfigurationExtensionImpl.kt:88`、`k2RefinementUtil.kt`、`AbstractRawFirBuilder.kt:1417`

## 十二、与 Gradle 的关系

插件消除了对 `.metadata` 的依赖。IDE 依赖视图完全由注解驱动，不再需要 Gradle `implementation(project(...))` 源集依赖。

长期目标是完全脱离 Gradle 进行开发（包括 companion .kt 文件），由插件独立管理所有依赖和编译配置。

如 IDE 插件不可用（CI 环境），下游项目仍可保留 `.metadata` 手动维护能力作为后备。
