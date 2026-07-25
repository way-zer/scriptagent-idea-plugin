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

`SAIndexerService.getDepends()` 自动附加最近的 `module.kts` 依赖：通过 `Config.getModulesById` 获取路径层级中的所有 module 候选，取 `asReversed()` 后找第一个存在的作为隐式依赖。与运行时行为一致。

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
- StubCacheService（Analysis API 签名渲染）
- StubRefreshLineMarkerProvider（手动刷新）
- 自动刷新/失效机制：未实现（当前需手动触发）
- inject 参数值补全：未实现

### Phase 4：Maven 依赖解析 — 待实现

- Maven JAR 异步下载与 ScriptConfiguration 刷新

### Phase 5：companionSources kt 模块支持 — 待实现

- 为 companion `.kt` 文件建立正确的 IDE 模块/classpath 配置

### Phase 6：高级特性 — 待实现

- 依赖图可视化 ToolWindow
- 脚本 ID 重命名级联
- ScriptStructureView

## 九、待实现功能设计

### 9.1 Maven 依赖解析（Phase 4）

**问题**：`@file:Import("group:artifact:version", mavenDepends=true)` 在运行时由 `ManagerImpl.importToLibraries` 通过 `DependencyManager` 下载 JAR 并注入 classpath。但 IDE 模式下 `IDEMode.refineForDepsIDEMode` 不处理 Maven 依赖——当前这些 JAR 不会出现在 ScriptConfiguration 的 classpath 中，导致 IDE 标红。

**约束**：ScriptConfiguration 的 `beforeCompiling` 回调不能阻塞下载（会卡死 IDE）。

**方案**：
- 提供按钮（ToolWindow 或 gutter action）触发异步 Maven 依赖下载
- 下载完成后，将 JAR 路径持久化到本地缓存（按脚本 ID + 依赖坐标索引）
- 触发 ScriptConfiguration refresh（通过 `ScriptConfigurationManager.updateScriptDependenciesIfNeeded` 或类似 API），使 `beforeCompiling` 再次执行时能从缓存读取已下载的 JAR 路径并 `updateClasspath`
- 需要在 `ideMetadataSource` hook 返回的 `SACompiledScript` 中填充 `libraries` 字段，或在 IDEMode 中增加 library 解析路径

**TODO**：
- [ ] 确定 Maven 下载结果的缓存位置和格式
- [ ] 实现异步下载 Action/按钮
- [ ] 在 IDEMode 或 ideMetadataSource 中集成已下载 JAR 的 classpath 注入
- [ ] 触发 ScriptConfiguration refresh 的 API 调研

### 9.2 companionSources kt 模块支持（Phase 5）

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

## 十、已知限制

1. **Stub 缓存无自动失效**：目标脚本的 @Export 成员变更后，需用户手动点击 gutter icon 刷新。可通过监听 FileBasedIndex 变更事件实现自动刷新。

2. **SAScriptDependenciesProvider 为空**：跨脚本源码关联完全依赖 ScriptConfiguration 通道。如果 K2 分析引擎未正确识别跨脚本类型引用，可能需要启用此通道。

3. **Import 类型推断启发式**：`PsiAnnotationExtractor.inferType` 在无显式标志时按字符串格式推断，需与框架运行时行为保持一致。

4. **Maven 依赖 JAR 未注入 classpath**：当前 IDE 模式完全不处理 `mavenDepends` 类型的 @Import，导致依赖 Maven 库的脚本在 IDE 中标红。

5. **companion .kt 文件无 IDE 支持**：脱离 Gradle 后这些文件将变成离散文件，IDE 无法为其提供正确的 classpath 和类型解析。

## 十一、与 Gradle 的关系

插件消除了对 `.metadata` 的依赖。IDE 依赖视图完全由注解驱动，不再需要 Gradle `implementation(project(...))` 源集依赖。

长期目标是完全脱离 Gradle 进行开发（包括 companion .kt 文件），由插件独立管理所有依赖和编译配置。

如 IDE 插件不可用（CI 环境），下游项目仍可保留 `.metadata` 手动维护能力作为后备。
