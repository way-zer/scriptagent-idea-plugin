# ScriptAgent IDE Plugin 设计文档

## 一、项目背景

### 1.1 ScriptAgent 是什么

一个 Kotlin Script（`.kts`）热加载框架。宿主应用（如 Mindustry）通过它加载、编译、启用、禁用、卸载 `.kts` 脚本文件，管理依赖关系和 classloader 隔离。

**核心架构**：
- 每个 `.kts` 文件有独立的 `ScriptClassLoader`
- ClassLoader 链：自己的 kotc JAR → 父脚本 → 祖脚本 → ... → Library JAR
- 依赖通过 `@file:Depends` 声明，library 通过 `@file:Import` 声明
- 脚本间类型互通通过 `inject`（`@Depends(name, inject="prop")`）实现——被注入脚本实例作为属性暴露给消费方
- `inject` 仅 `.kts` → `.kts`，无法从 `.kt` 中引用脚本类型——这是当前架构的核心约束
- 运行时用 `ScriptRegistry` 管理所有脚本

**关键架构决策**：
- `@Implement` 是实验性的，不推荐继续使用——它尝试让脚本实现正式接口，但使用体验不佳
- `export()` 也非主力方案——它的字符串式符号查找缺乏类型安全
- `inject` 是目前脚本间类型共享的核心机制，但仍有局限性：依赖方拿到的是脚本实例本身，没有静态类型契约
- **脚本的"public API"没有正式的声明机制**——这是待解决问题的根源

### 1.2 下游用法（ScriptAgent4MindustryExt）

在 `scripts/` 目录下组织模块化的 `.kts` 脚本：
- `coreLibrary/` — 平台无关的基础库（命令框架、数据库、变量系统）
- `coreMindustry/` — Mindustry 适配层（游戏变量、计分板、菜单）
- `wayzer/` — 核心功能模块（40+ 脚本：投票、地图、用户管理）
- `mapScript/` — 每张地图的游戏逻辑
- `agentCore/` — AI Agent 系统（LLM 集成）
- `mySite/` — Web API 模块

每个模块目录有 `module.kts` 作为依赖/导入的声明中心，其他 `.kts` 继承这些导入。

### 1.3 关键注解

```kotlin
@file:Depends("coreLibrary")                    // 硬依赖，影响 classpath
@file:Depends("optional", soft = true)          // 软依赖，仅影响加载顺序
@file:Depends("ban", inject = "banService")     // 注入依赖脚本实例为属性
@file:Import("com.guava:guava:30.1", mavenDepends = true)  // Maven 依赖
@file:Import("arc.Core", libraryByClass = true)            // 按类名反查 JAR
@file:Import("wayzer.lib.*", defaultImport = true)         // 默认 import
@file:Implement(MyInterface::class)            // 鸭式接口实现（实验性，后续可能移除）
```

### 1.4 现有 IDE Plugin 状态

**已实现**（`idePlugin/`）：
- `SAScriptDefinitionsSource`：将 `SAScript` 注册为 Kotlin 脚本模板
- `MyFirRegistrar`（KCP）：`@Implement` 接口成员检查在 IDE 中生效（低优先级，`@Implement` 后续可能移除）

**缺失**：
- `@Depends` / `@Import` 的语义理解（导航、补全、验证）
- `.metadata` 的同步与消除
- 依赖图可视化
- per-file 依赖解析
- `@Depends(inject=)` 注入属性的合成 Stub

---

## 二、路线图

### 里程碑 1：替代 .metadata + Per-file 依赖解析

**目标**：IDE 直接从注解获取依赖信息，绕过 `.metadata` 和 Gradle 源集模型，每个脚本文件拥有与 SA 运行时一致的 classloader 可见性。

**为什么必须一起做**：
- 替代 `.metadata` 只是换了数据源（注解 → 索引），但如果不把索引数据喂给 IDE 的类型解析引擎，只是"换了个地方存数据"
- `ScriptAdditionalIdeaDependenciesProvider` 是 IDE 接受 per-file 依赖的唯一入口，它需要索引提供的数据，同时负责真正的类型可见性注入
- 两者分开没有意义：建好了索引但 IDE 不用它，和没建一样

**做什么**：
- `SAScriptIndex`（`FileBasedIndexExtension`）——扫描 `scripts/` 下所有 `.kts`，提取 `@file:Depends` 和 `@file:Import` 参数，持久化索引
- `ScriptRegistryIndex`（内存缓存层）——惰性构建依赖图，提供 per-script 查询
- `ScriptAdditionalIdeaDependenciesProvider` —— 为每个 `.kts` 文件提供独立的 classpath + sources + imports，沿 `@Depends` 链递归展开传递依赖
- `AdditionalLibraryRootsProvider` —— 把 `mavenDepends` 解析出的 JAR 注册为模块库
- 改造 `IDEMode.beforeCompilingIDEMode` —— `findScriptMetadata()`（读 `.metadata`）替换为查 `ScriptRegistryIndex`

**完成后效果**：
- `.kts` 中的类型解析、补全、错误检查完全对齐 SA 运行时的 classloader 链
- 修改 `@file:Depends` 后 IDE 自动感知，不再需要同步 `.metadata`
- Gradle `implementation(project(...))` 源集依赖不再需要——IDE 依赖视图完全由注解驱动
- `.metadata` 降级为 CI/命令行场景的备份，`buildSrc/` 中的解析代码可以逐步废弃

### 里程碑 2：结构化导航与校验

**目标**：让 `.kts` 中的字符串引用变成可操作的链接。

**做什么**：
- `@Depends("...")` 字符串 → Ctrl+Click 跳转到目标脚本文件
- `depends("...")` 调用中的字符串 → 同上
- 脚本 ID 补全：`@Depends("^")` 列出所有已知脚本 ID
- `@Depends("nonexistent")` → 红色波浪线 + "未找到脚本"
- `@Import` 的 Boolean 标签检查（0 个或 >1 个 true → 报错 + QuickFix）
- `.metadata` 文件语法高亮（保留到完全废弃前）

**完成后效果**：日常开发中最高频的痛点（"这个依赖指向哪？""我打对了脚本名吗？"）全部解决。

### 里程碑 3（待定）：注入属性合成 Stub

**前置条件**：SA 框架确定脚本 public API 声明机制（当前 `@Implement` 废弃中，替代方案未定）。

**目标**：`@Depends("x", inject="foo")` 让 IDE 知道 `foo` 是一个可用属性，并提供其 public 成员的补全和类型检查。

**做什么**：
- `SyntheticScopeProvider` —— 为带 `inject` 的脚本注入合成 `LightProperty`
- 属性类型 = 被注入脚本的 public API 类型（机制待定）
- `inject=` 参数值的补全建议

---

## 三、核心设计决策

### 决策 1：消除 `.metadata`

**现状**：`.metadata` 是 `@file 注解` 和 `Gradle 源集` 之间的手工维护翻译层。SA 运行时并不需要它——编译时直接从注解读取依赖。

```
当前路径：
  @file:Depends("coreLibrary") 
    → 开发者手写 .metadata: "+DEPENDS coreLibrary"
      → buildSrc/SAMetadataModules.kt 读取
        → Gradle source set dependency
          → IntelliJ IDE 类型解析

目标路径：
  @file:Depends("coreLibrary") 
    → IDE Plugin 直接读取（SAScriptIndex）
      → IntelliJ IDE 类型解析
```

**决策**：IDE 插件直接解析 `.kts` 注解，不再使用 `.metadata` 作中间层。

### 决策 2：绕过 Gradle 项目模型

**动机**：
- SA 的依赖粒度是**脚本**（每文件），Gradle 的粒度是**源集**（每模块目录）
- SA 的 classloader 链是**树**（严格父子关系），Gradle 的 dependency scope 是扁平图
- SA 支持 soft 依赖、inject 依赖、libraryByClass 等 Gradle 无法表达的概念

**决策**：利用 IntelliJ Kotlin 插件对 `.kts` 文件的**平行配置层**，不通过 Gradle Module 模型提供依赖。

### 决策 3：使用 FileBasedIndex 持久化（不用 PSI Stub）

**动机**：
- `KotlinAnnotationsIndex` 索引了完整 PSI stub，太重
- 我们只需要注解参数（字符串），不需要完整类型信息
- 自定义 FileBasedIndex：更小、更快、更可控

**决策**：注册独立的 `SAScriptIndex`（`FileBasedIndexExtension`），通过轻量 PSI（`KtFile.fileAnnotationList`）提取注解参数。

> **实现说明**：最初设计考虑用 regex 无 PSI 提取，但实际采用 PSI 方案更可靠——不会因字符串格式变化、注释、换行而误匹配。`FileContent.psiFile` 在索引期间可用，性能开销可接受。

### 决策 4：复用 ScriptConfiguration 递归解析（不独立建依赖图）

**动机**：`IDEMode` 的 `ScriptCompilationConfiguration` 已有完整的递归依赖解析逻辑（`refineForDepsIDEMode` → `findScriptMetadata` 递归），包括传递依赖展开、`defaultImport` 收集、classpath 注入。

**决策**：
- 传递依赖、defaultImport、library classpath 全部由 ScriptConfiguration 已有递归逻辑处理
- IDE 插件通过 `ideMetadataSource` hook 将索引数据喂给 ScriptConfiguration，不重复实现依赖图
- `SAScriptDependenciesProvider` 作为 K2 分析层的补充，告知 IDE 脚本间源码关联
- 独立的 `ScriptRegistryIndex` 内存缓存层暂不需要；如果后续 Phase 2 导航/补全需要全局依赖图查询，再按需引入

> **原设计**：计划实现独立的 `ScriptRegistryIndex` + `DependencyGraph`（惰性构建、stamp 缓存失效）。实际发现复用 ScriptConfiguration 更精简，避免维护两套递归逻辑。

### 决策 5：待定 — 脚本 public API 声明机制

**现状**：`inject` 让脚本 A 能访问脚本 B 的实例，但 B **没有正式的 public API 声明**——消费者拿到的是一个无类型的脚本引用，通过字符串查找方法（`export()`）或隐式依赖 `@Implement` 接口。

**影响**：IDE 的注入属性合成 Stub（7.4 节）目前依赖 `@Implement` 接口推断类型，但 `@Implement` 即将废弃。在找到替代机制之前，合成 Stub 的精度会受限制——只能退化为"显示所有 top-level 符号"，而非精确的类型驱动补全。

**开放问题**：
- 是否需要类似 Rust `pub` 或 Java `exports` 的显式声明机制？
- 还是用约定代替声明（如 `module.kts` 中的 `defaultImport` 反向推断）？
- 语法形态：注解？DSL 函数？独立配置文件？

此决策直接影响 IDE 插件 Phase 4 的 `SASyntheticScopeProvider` 实现。

---

## 四、IntelliJ 如何区分 `.kts` 和 `.kt`

这是整个设计的基础——`.kts` 文件在 IntelliJ 中走独立通道。

```
常规 .kt 文件：
  Project → Module → SourceSet → classpath（模块级，所有文件共享）

.kts 脚本文件：
  Project → [Module 提供基线: JDK + Kotlin stdlib]
         ↘ ScriptConfiguration（平行层，每文件可不同）
                ├── ScriptDefinition（SAScript 模板）
                ├── ScriptDependencies（per-file 动态依赖）← 我们要注入的
                └── ImplicitImports（来自 @Import(defaultImport=true)）
```

Gradle KTS 就是这套机制的经典用例——`build.gradle.kts` 能解析 Gradle API，但源码目录的 `.kt` 不能。

---

## 五、现有的 IDEMode 编译管线

SA 已有 `IDEMode`（`src/main/kotlin/impl/config/IDEMode.kt`），它是 IDE 模式下的 `ScriptCompilationConfiguration`，通过 `@KotlinScript` 注解关联到 `SAScript` 模板：

```kotlin
@KotlinScript(
    compilationConfiguration = IDEMode::class,
    hostConfiguration = IDEHostConfiguration::class
)
open class SAScript(scriptSource: ScriptSource) : Script(scriptSource)
```

**IDEMode 编译流程**：
```
.kts 文件
  → IDEMode.filePathPattern 匹配
    → beforeCompilingIDEMode 回调
      → findScriptMetadata() ← 当前从 .metadata 读
        → refineForDepsIDEMode() 递归依赖
          → addDefaultImport / addCompileOptions
            → providedProperties（K2 注入模式）
```

**改造方向**：`findScriptMetadata()` 改为查 `ScriptRegistryIndex`（我们的索引层）。

---

## 六、核心 IntelliJ API

### 5.1 需要实现的 Extension Points

| Extension Point | 作用 | 优先级 |
|----------------|------|--------|
| `FileBasedIndexExtension` | 持久化索引注解数据，增量更新 | **P0 基础设施** |
| `ScriptAdditionalIdeaDependenciesProvider` | per-file 依赖注入（classpath + sources + imports） | **P0 功能核心** |
| `AdditionalLibraryRootsProvider` | 把 mavenDepends JAR 注册为库根 | P1 |
| `PsiReferenceContributor` | `@Depends("...")` 字符串 → 目标脚本文件导航 | P1 |
| `CompletionContributor` | `@Depends("^")` 补全脚本 ID | P1 |
| `Annotator` | 依赖检查、`@Import` 标签验证 | P1 |
| `ToolWindowFactory` | 依赖图可视化面板 | P2 |
| `RenameHandler` | 脚本 ID 重命名级联更新 | P2 |

### 5.2 `ScriptAdditionalIdeaDependenciesProvider` 详解

这是最关键的扩展点，位于 `org.jetbrains.kotlin.idea.core.script.v1`（K1）和 `org.jetbrains.kotlin.idea.core.script.k2.dependencies`（K2）。

Kotlin 插件对**每个 `.kts` 文件**调用此接口，要求返回该文件的依赖信息：

```kotlin
interface ScriptAdditionalIdeaDependenciesProvider {
    fun collectDependenciesForFile(
        scriptFile: VirtualFile,
        scriptDefinitions: List<ScriptDefinition>,
        previousData: ScriptDependenciesInfo.ForFile?
    ): ScriptDependenciesInfo.ForFile?
}
```

返回值 `ScriptDependenciesInfo.ForFile` 包含：
- `classpath`：JAR 文件路径列表 → IntelliJ 将其加入类型解析
- `sources`：其他脚本源文件路径列表 → IntelliJ 将其加入源码索引
- `imports`：默认 import 列表 → 自动可见，无需显式 import

这是实现 per-file classpath 隔离的入口。

### 5.3 `FileBasedIndexExtension` 关键参数

```kotlin
class SAScriptIndex : FileBasedIndexExtension<String, ScriptIndexEntry>() {
    override fun getName() = NAME                    // 唯一标识
    override fun getIndexer() = DataIndexer { ... }  // 索引构建逻辑
    override fun getKeyDescriptor()                   // Key 序列化
    override fun getValueExternalizer()               // Value 序列化
    override fun getInputFilter()                     // 文件匹配
    override fun dependsOnFileContent() = true        // 内容变化时重索引
    override fun getVersion() = 1                     // 格式版本号
}
```

- `DataIndexer`：接收 `FileContent`（原始字节+文本，无 PSI），用 regex 提取注解
- `InputFilter`：只匹配 `scripts/` 目录下的 `.kts`，跳过无关文件
- `dependsOnFileContent = true`：文件内容变化时自动增量重索引
- 序列化：通过自定义 `Externalizer` 实现磁盘持久化

### 5.4 FileBasedIndex 查询

```kotlin
// 列出所有 key（所有脚本 ID）
FileBasedIndex.getInstance().getAllKeys(SAScriptIndex.NAME, project)

// 查单个 key 的值
FileBasedIndex.getInstance().getValues(SAScriptIndex.NAME, scriptId, scope)

// 获取索引修改时间戳（判断缓存是否过期）
FileBasedIndex.getInstance().getIndexModificationStamp(SAScriptIndex.NAME, project)
```

---

## 七、索引设计

### 6.1 SAScriptIndex（FileBasedIndex，持久化层）

```
Key:   String（脚本 ID，如 "wayzer/maps"）
Value: ScriptIndexEntry（二进制序列化，每个 ~200-500 bytes）

InputFilter: file.extension == "kts" && file.path.contains("/scripts/")
dependsOnFileContent: true（文件修改时自动增量重索引）
```

**索引构建**（DataIndexer 内）：
```kotlin
// 通过轻量 PSI 提取 file-level 注解
override fun map(inputData: FileContent): Map<String, ScriptIndexInfo> {
    val file = inputData.file
    val (_, id) = resolveRootAndId(file) ?: return emptyMap()
    val psiFile = inputData.psiFile
    val (depends, imports, implements) = PsiAnnotationExtractor.extractAll(psiFile)
    val isModule = file.nameWithoutExtension == "module"
    return mapOf(id to ScriptIndexInfo(id, depends, imports, implements, isModule))
}

// PsiAnnotationExtractor 遍历 KtFile.fileAnnotationList，按 shortName 分派：
// "Depends" → 提取 name, desc, inject, soft
// "Import"  → 提取 value + type flag
// "Implement" → 提取 interface FQN
```

**数据结构**：
```kotlin
// 直接复用框架定义的 DependsData / ImportData，避免重复维护
data class ScriptIndexInfo(
    val id: String,                        // "wayzer/maps"
    val depends: List<DependsData>,        // @file:Depends（框架类型）
    val imports: List<ImportData>,         // @file:Import（框架类型）
    val implements: List<String>,          // @file:Implement 的接口 FQN
    val isModule: Boolean = false,         // 文件名为 module.kts
) : Serializable

// 框架已有（cf.wayzer.scriptAgent.define.annotations）：
data class DependsData(
    val name: String,                      // "coreLibrary"
    val desc: String = "",
    val inject: String = "",               // K2 injected property name
    val soft: Boolean = false,
) : Serializable

data class ImportData(
    val type: Type,                        // MavenDepends | LibraryByClass | DefaultImport | ...
    val value: String,                     // "com.guava:guava:30.1" or "arc.Core" or "wayzer.lib.*"
) : Serializable
```

**大小估算**：1000 个脚本 × 300 bytes ≈ 300KB 磁盘索引。重建时间 < 1ms（regex 扫描文件头）。

### 6.2 SAIndexerService（索引查询服务）

提供对 `SAScriptIndex` 的便捷查询接口。不维护独立的依赖图——传递依赖解析由 ScriptConfiguration 递归处理。

```kotlin
object SAIndexerService {
    val index = ID.create<String, ScriptIndexInfo>("cf.wayzer.scriptAgent.scriptIndex")

    // 列出所有脚本 ID（用于补全、ToolWindow 展示）
    fun getAllIds(project: Project): List<String>
    
    // 按文件查索引信息（单 key O(log n)）
    fun getInfo(project: Project, file: VirtualFile): ScriptIndexInfo?
    
    // 获取直接依赖（含自动推断的 module 依赖）
    fun getDepends(project: Project, file: VirtualFile): List<DependsData>
    
    // 按 ID 查找脚本文件
    fun findScript(project: Project, id: String): VirtualFile?
}
```

**module 自动推断**：`getDepends()` 根据脚本 ID 的路径层级（`Config.getModulesById`），自动查找并附加最近的 `module.kts` 依赖，与运行时行为一致。

> **后续扩展**：如果 Phase 2 导航/补全需要全局依赖图查询（如反向依赖、环检测），可在此基础上按需引入 `DependencyGraph` 缓存层。

---

## 八、核心功能实现路径

### 7.1 Per-file 依赖解析

采用双通道方案：

**通道 1：ScriptConfiguration（主通道）** — `IDEMode.beforeCompilingIDEMode` 递归处理传递依赖、defaultImport、classpath。通过 `ideMetadataSource` hook 从索引读取元数据，替代 `.metadata` 文件。

**通道 2：K2IdeScriptAdditionalIdeaDependenciesProvider（补充）** — 告知 K2 分析层脚本间的源码关联，使跨脚本符号可见。

```kotlin
class SAScriptDependenciesProvider : K2IdeScriptAdditionalIdeaDependenciesProvider {
    
    override fun getRelatedModules(file: VirtualFile, project: Project): List<VirtualFile> {
        // 返回直接非 soft 依赖的脚本文件，让 K2 建立源码关联
        return SAIndexerService.getDepends(project, file)
            .filter { !it.soft }
            .mapNotNull { dep -> SAIndexerService.findScript(project, dep.name) }
    }

    override fun getRelatedLibraries(file: VirtualFile, project: Project): List<KotlinScriptLibraryEntity> {
        // Library classpath 由 ScriptConfiguration 提供，此处不重复
        return emptyList()
    }
}
```

**`ideMetadataSource` 挂载**（启动时注册）：
```kotlin
class ScriptAgentStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        IDEMode.ideMetadataSource = ::findMetadata
    }

    private fun findMetadata(root: File, info: ScriptInfo): SACompiledScript? {
        // 从 FileBasedIndex 查找脚本 → 构造 SACompiledScript
        // IDEMode 的递归逻辑拿到这个结果后，自动处理传递依赖
        val entry = SAIndexerService.findScript(project, info.id)?.let {
            SAIndexerService.getInfo(project, it)
        } ?: return null
        return SACompiledScript(ScriptSource.Dummy(info)).apply {
            dependsData = entry.depends.toSet()
            importData = entry.imports.toSet()
        }
    }
}
```

### 7.2 注解导航（Ctrl+Click）

注册 `PsiReferenceContributor`，为 `@file:Depends("...")` 和 `depends("...")` 中的字符串创建 PSI 引用：

```kotlin
class ScriptDependsReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiRegistrar) {
        // @Depends(name="xxx") 或 depends("xxx") 中的字符串
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(KtLiteralStringTemplateEntry::class.java)
                .inside(KtAnnotationEntry::class.java),  // 或 inside function call
            DependsReferenceProvider()
        )
    }
}

class DependsReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement, context: ProcessingContext
    ): Array<PsiReference> {
        // 确认这个字符串位于 @Depends 的 name 参数位置
        val scriptId = element.text.trim('"')
        val registry = ScriptRegistryIndex.getInstance(element.project)
        val targetInfo = registry.getInfo(scriptId) ?: return PsiReference.EMPTY_ARRAY
        
        return arrayOf(object : PsiReferenceBase<PsiElement>(element) {
            override fun resolve(): PsiElement? =
                PsiManager.getInstance(project).findFile(targetInfo.scriptFile)
            
            override fun getVariants(): Array<Any> =
                registry.allIds.toTypedArray()
        })
    }
}
```

### 7.3 注解 Inspection

```kotlin
class ScriptAnnotationAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is KtAnnotationEntry) return
        
        when (element.shortName?.asString()) {
            "Depends" -> checkDepends(element, holder)
            "Import" -> checkImport(element, holder)
        }
    }
    
    private fun checkDepends(entry: KtAnnotationEntry, holder: AnnotationHolder) {
        val name = entry.valueArguments
            .find { it.getArgumentName()?.asName?.identifier == "name" }
            ?.getArgumentExpression()?.text?.trim('"') ?: return
        
        // 解析检查：指向的是否存在的脚本 ID
        val registry = ScriptRegistryIndex.getInstance(entry.project)
        if (registry.getInfo(name) == null) {
            holder.newAnnotation(HighlightSeverity.ERROR, "未找到脚本: $name")
                .range(entry.textRange)
                .create()
        }
    }
    
    private fun checkImport(entry: KtAnnotationEntry, holder: AnnotationHolder) {
        // 检查 Boolean 标签：0 个或 >1 个 true → error
        val flags = listOf("libraryByClass", "libraryByClassLoader", "libraryByName",
            "libraryByFile", "sourceFile", "mavenDepends", "mavenDependsSingle",
            "mavenRepository", "defaultImport", "compileArg")
        
        val activeFlags = flags.count { 
            entry.valueArguments.find { a -> a.getArgumentName()?.asName?.identifier == it }
                ?.getArgumentExpression()?.text == "true"
        }
        
        if (activeFlags == 0) {
            holder.newAnnotation(HighlightSeverity.ERROR, 
                "必须指定一个导入类型（如 mavenDepends = true）")
                .range(entry.textRange)
                .withFix(SuggestImportTypeFix())
                .create()
        } else if (activeFlags > 1) {
            holder.newAnnotation(HighlightSeverity.WARNING,
                "多个导入类型被设为 true，只有一个生效")
                .range(entry.textRange)
                .create()
        }
    }
}
```

### 7.4 `@Depends(inject=)` — 注入依赖的合成 Stub

#### 问题

```kotlin
// wayzer/user/ban.kts
@file:Implement(BanService::class)

fun ban(player: Player, reason: String) { ... }   // BanService 的接口方法

// wayzer/maps.kts
@file:Depends("wayzer/user/ban", inject = "banService")

onEnable {
    banService.ban(player, "grief")  // ← banService 是注入的属性，IDE 完全不知道它的存在
}
```

`banService` 在运行时由 SA 的 K2 `providedProperties` 机制注入到脚本的构造函数参数中。但 `providedProperties` 有一个关键限制：**值的类型必须是已知的 KotlinType（普通类），不能是运行时动态组装的接口交集**。`banService` 在 IDE 中的真实类型并非某个具体类，而是被注入脚本通过 `@Implement` 实现的接口集合（可能多个），这些接口在编译时没有物理类承载。

运行时不存在这个问题——SA 的 classloader 链保证 `banService` 指向的脚本实例确实有这些方法。

> **注意**：`@Implement` 是实验性特性，后续可能移除。`inject` 是脚本间类型共享的核心机制，但被注入脚本缺少正式的 public API 声明——消费者拿到的只是脚本实例，没有静态类型契约。这对 IDE 来说意味着：**合成属性的"类型"从哪来，目前没有确定答案**。

> 临时方案：基于 `@Implement` 接口推断，但这是过渡性的。长期方案取决于 SA 框架是否引入正式的"脚本对外接口声明"机制（如 Java 的 `exports`、Rust 的 `pub`）。在此之前，合成 Stub 的补全列表可以退化为"列出被注入脚本中所有 top-level 符号"（等于是 `export()` 的反向版），精度较低但可用。

#### 目标

IDE 识别 `inject="x"` 后，在脚本文件中凭空"造"出一个属性 `x`，其类型为被注入脚本实现的接口的**交集类型**（union of `@Implement` interfaces），并提供其所有 public 成员的补全和类型检查。这完全是 IDE 层的静态分析增强，不改变编译行为。

#### 实现路径

**不使用 `providedProperties`** —— 它的值类型必须对应一个物理 KotlinType，无法表达"多个 `@Implement` 接口的交集"。

改为在 **IDE 类型解析层**直接注入合成 PSI 声明。IntelliJ 对"不在源码中但逻辑上存在的成员"提供了 `LightElement` / `LightMember` 机制。Kotlin 插件的 `SyntheticScopeProvider` 允许向脚本文件注入额外声明。

**: `LightMember` / 合成 PSI（接上）**

关键点：这个属性的类型**不是任何物理 class**——它是被注入脚本所有 `@Implement` 接口的交集。IDE 里不存在一个叫 "`BanService`" 的 `.class` 文件（它只是接口定义），更不存在 `BanService & PosMarkService` 这样的物理类型。

因此不能用 `providedProperties`（它要求值是一个可解析的 KotlinType），必须在**符号解析层**直接注入一个声明：

```kotlin
class InjectedScriptProperty(
    parent: KtFile,
    val injectName: String,                 // "banService"
    val implementedTypes: List<String>,     // ["wayzer.BanService", "mapScript.PosMarkService"]
) : KtLightProperty {

    override fun getName(): String = injectName

    // 返回的类型不是物理类，是一个动态组装的交叉类型：
    // BanService & PosMarkService — 表达"这两个接口的所有方法都可用"
    override fun getTypeReference(): KtTypeReference? {
        if (implementedTypes.isEmpty()) return null
        if (implementedTypes.size == 1) {
            return createSimpleType(implementedTypes.first())  // BanService
        }
        return SyntheticIntersectionType(implementedTypes)     // BanService & PosMarkService
    }

    override fun getContainingFile() = parent
    override fun getParent() = parent

    // 导航：Ctrl+Click 跳转到被注入脚本
    override fun getNavigationElement(): PsiElement? {
        val registry = ScriptRegistryIndex.getInstance(project)
        val targetFile = registry.getInfo(targetScriptId)?.scriptFile
        return targetFile?.let { PsiManager.getInstance(project).findFile(it) }
    }
}
```

`SyntheticIntersectionType` 是自定义的合成类型节点，它的成员列表 = 所有 `@Implement` 接口成员的**并集**。IDE 补全引擎遍历这个并集来提供补全项，类型检查也基于它验证调用签名。

##### 注册合成属性

`SyntheticScopeProvider` 是 Kotlin 插件的扩展点，允许向脚本文件注入额外的声明：

```kotlin
class SASyntheticScopeProvider : SyntheticScopeProvider {
    override fun getSyntheticMembers(
        descriptor: ScriptDescriptor,
        resolutionScope: ResolutionScope,
    ): List<KtDeclaration> {
        val scriptFile = descriptor.containingDeclaration as? KtFile ?: return emptyList()
        val scriptId = inferScriptId(scriptFile.virtualFile)
        val registry = ScriptRegistryIndex.getInstance(scriptFile.project)
        val info = registry.getInfo(scriptId) ?: return emptyList()
        
        // 仅处理带 inject 的 @Depends
        return info.depends.filter { it.inject.isNotEmpty() }.map { dep ->
            val targetInfo = registry.getInfo(dep.name) ?: return@map null
            val interfaces = targetInfo.implements.map { fqName ->
                createTypeReference(scriptFile, fqName)
            }
            InjectedScriptProperty(
                parent = scriptFile,
                injectName = dep.inject,
                targetScriptId = dep.name,
                implementedTypes = interfaces,
            )
        }.filterNotNull()
    }
}
```

##### 补全增强

注入属性注册后，IDE 的补全引擎自动处理——`banService.` 会列出 `BanService` 的所有 public 方法，就像普通属性一样。

额外：为 `inject=` 参数值提供补全：

```kotlin
// 在 @Depends(..., inject = "^") 位置补全目标脚本的类型名
class InjectNameCompletionContributor : CompletionContributor() {
    // 匹配 @Depends 注解中 inject= 参数位置的字符串
    init {
        extend(CompletionType.BASIC, 
            psiElement(KtLiteralStringTemplateEntry::class)
                .inside(psiElement(KtAnnotationEntry::class)),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(params: CompletionParameters, result: CompletionResultSet) {
                    // 检查是否在 inject= 上下文
                    val entry = params.position.parent.parent as? KtAnnotationEntry ?: return
                    val targetId = entry.valueArguments
                        .find { it.getArgumentName()?.asName?.identifier == "name" }
                        ?.getArgumentExpression()?.text?.trim('"') ?: return
                    
                    val registry = ScriptRegistryIndex.getInstance(params.position.project)
                    val targetInfo = registry.getInfo(targetId) ?: return
                    
                    // 建议：用接口名的小驼峰作为 inject 变量名
                    targetInfo.implements.forEach { ifaceFqN ->
                        val shortName = ifaceFqN.substringAfterLast('.')
                        val varName = shortName.replaceFirstChar { it.lowercase() }
                        result.addElement(LookupElementBuilder.create(varName)
                            .withTailText(" → $shortName")
                            .withTypeText(targetId))
                    }
                }
            }
        )
    }
}
```

#### 索引增强

为支持合成 Stub，`ScriptIndexEntry` 需要多捕获一个字段：

```kotlin
data class ScriptIndexEntry(
    val scriptId: String,
    val depends: List<DependsEntry>,       // 含 inject 字段
    val imports: List<ImportEntry>,
    val implements: List<String>,          // ← 已有，用于确定注入属性类型
    // 无需新增字段——inject 在 depends 中, implements 已有
)
```

`DependsEntry` 已经包含 `inject: String`：

```kotlin
data class DependsEntry(
    val name: String,       // "wayzer/user/ban"
    val soft: Boolean = false,
    val inject: String = "",  // "banService"
)
```

#### 可见性规则

注入属性的可见性遵循 SA 的 classloader 链语义——只能访问被注入脚本**自身及其传递依赖**中定义的类型。这由 `ScriptAdditionalIdeaDependenciesProvider` 的 per-file 依赖解析保证。

#### 示例效果

```kotlin
// wayzer/maps.kts
@file:Depends("wayzer/user/ban", inject = "banService")

onEnable {
    banService.  // ← IDE 补全列出: ban(), unban(), isBanned() 等 BanService 的方法
                 //   类型提示: banService: BanService
                 //   Ctrl+Click banService → 跳转到 wayzer/user/ban.kts
}
```

---

## 九、数据流全景

```
项目打开
  │
  ▼
IntelliJ 从磁盘加载 SAScriptIndex（不重扫）
  │
  └─ 文件内容变化 → FileBasedIndex 增量重索引该文件
  
用户编辑 wayzer/maps.kts
  │
  ▼
通道 1：ScriptConfiguration（传递依赖 + imports + classpath）
  │
  ├→ IDEMode.beforeCompilingIDEMode 触发
  ├→ findScriptMetadata(depId) → ideMetadataSource hook
  │     └→ SAIndexerService.findScript + getInfo  ← 从 FileBasedIndex 读
  ├→ refineForDepsIDEMode() 递归展开传递依赖
  └→ refineForImportSources() 解析 library classpath
  
通道 2：K2IdeScriptAdditionalIdeaDependenciesProvider（源码关联）
  │
  ├→ SAScriptDependenciesProvider.getRelatedModules()
  │     └→ SAIndexerService.getDepends() → findScript()
  └→ 返回直接依赖脚本的 VirtualFile 列表
     → K2 分析引擎建立跨脚本符号可见性
  │
  ▼
IntelliJ 类型解析引擎综合两个通道的信息解析 maps.kts 中的类型引用
  → 补全、导航、错误检查全部基于此
```

---

## 十、与 Gradle 的松耦合

`.metadata` 不再需要时，`buildSrc/` 中相关代码的状态：

| 组件 | 去留 | 说明 |
|------|------|------|
| `buildSrc/ModuleParser.kt` | 可移除 | `.metadata` 解析逻辑 |
| `buildSrc/SAMetadataModules.kt` | 可移除 | 源集生成逻辑 |
| `buildSrc/Module.kt` | 保留 | 如 IDE 插件不可用（CI 环境），可降级 |
| 其他 buildSrc | 保留 | ShadowJar、precompile、allInOneJar |

**降级策略**：如果 IDE 插件未安装（CI 环境），保留 `.metadata` 手动维护能力作为后备，但不再作为日常开发的主要路径。

---

## 十一、实现阶段

### Phase 1：替代 .metadata + Per-file 依赖解析（里程碑 1）

| 组件 | Extension Point | 状态 |
|------|----------------|------|
| `ScriptIndexer` (FileBasedIndex) | `com.intellij.fileBasedIndex` | ✅ 已实现 |
| `ScriptIndexInfo` + `Externalizer` | 索引数据结构（复用框架 `DependsData`/`ImportData`） | ✅ 已实现 |
| `PsiAnnotationExtractor` | 索引构建逻辑（轻量 PSI） | ✅ 已实现 |
| `SAIndexerService` | 索引查询服务（替代原设计的 `ScriptRegistryIndex`） | ✅ 已实现 |
| `SAScriptDependenciesProvider` | `org.jetbrains.kotlin.k2IdeScriptAdditionalIdeaDependenciesProvider` | ✅ 已实现 |
| `ScriptAgentStartup` + `ideMetadataSource` hook | 将索引数据喂给 ScriptConfiguration 递归解析 | ✅ 已实现 |
| `SAScriptProvider` | `org.jetbrains.kotlin.scriptDefinitionsProvider` | ✅ 已实现 |
| `ScriptIndexToolWindow` | `com.intellij.toolWindow`（调试/查看用） | ✅ 额外实现 |
| ~~`SALibraryRootsProvider`~~ | ~~`com.intellij.additionalLibraryRootsProvider`~~ | 不需要（由 ScriptConfiguration 处理） |
| ~~`ScriptRegistryIndex` 内存缓存~~| ~~惰性依赖图~~ | 不需要（复用 ScriptConfiguration 递归） |

### Phase 2：导航与检查（里程碑 2）

| 组件 | Extension Point | 状态 |
|------|----------------|------|
| `ScriptDependsReferenceContributor` | `com.intellij.psi.referenceContributor` | ✅ 已实现（含 Ctrl+Click + 补全） |
| ~~`ScriptIdCompletionContributor`~~ | ~~`com.intellij.completion.contributor`~~ | 不需要（由 Reference.getVariants 提供） |
| `ScriptAnnotationAnnotator` | `com.intellij.annotator` | ✅ 已实现（@Depends 校验 + @Import 标签检查） |
| `ImportTypeQuickFix` | `com.intellij.codeInsight.intention` | 待实现（低优先级） |
| ~~`.metadata` 语法高亮~~ | — | 不需要（.metadata 已弃用） |

### Phase 3（待定）：注入属性合成 Stub（里程碑 3）

| 组件 | Extension Point | 依赖 |
|------|----------------|------|
| `SASyntheticScopeProvider`（inject 合成属性） | `kotlin.syntheticScopeProvider` | 依赖 SA 框架确定脚本 public API 声明机制 |
| `InjectNameCompletionContributor` | `com.intellij.completion.contributor` | 同上 |

### Phase 4：高级特性

| 组件 | Extension Point |
|------|----------------|
| `ScriptDependencyToolWindow` | `com.intellij.toolWindow` |
| `ScriptRenameHandler` | `com.intellij.refactoring.renameHandler` |
| `ScriptStructureView` | `com.intellij.ide.structureView` |

---

## 十二、下游 Mindustry 扩展点

插件需要设计扩展机制，让下游仓库注入领域专属的逻辑：

```kotlin
// 框架层插件定义的扩展点
interface SAExtensionPoint {
    fun contributeCompletions(file: KtFile, registry: ScriptRegistryIndex): List<LookupElement>
    fun contributeInspections(file: KtFile, registry: ScriptRegistryIndex): List<ProblemDescriptor>
}

// 下游 Mindustry 插件注册
<saExtension implementation="cf.wayzer.scriptAgent.mindustry.MindustrySAExtension"/>
```

下游可以注入：
- `listen<EventType.^>` 的事件类型补全
- `command("name", "desc")` 的命令约定检查
- `registerVar` / `config.key` 的键名补全
- Mindustry API 专属的 import 建议

---

## 十三、Maven / 本地仓库坐标

```xml
<!-- IntelliJ Platform SDK -->
<dependency>com.jetbrains.intellij.platform</dependency>

<!-- Kotlin Plugin APIs -->
<dependency>org.jetbrains.kotlin:kotlin-plugin</dependency>

<!-- 关键 API 所在位置 -->
- com.intellij.util.indexing.FileBasedIndex           (platform-core)
- com.intellij.psi.stubs.StubIndex                    (platform-core)  
- com.intellij.openapi.roots.AdditionalLibraryRootsProvider (platform-projectModel)
- org.jetbrains.kotlin.idea.core.script.v1.ScriptAdditionalIdeaDependenciesProvider (kotlin-plugin)
- kotlin.script.experimental.intellij.ScriptDefinitionsProvider (kotlin-plugin)
- org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex (kotlin-plugin)
```

---

## 十四、关键文件路径

| 文件 | 说明 |
|------|------|
| `E:\Projects\ScriptAgent\idePlugin\build.gradle.kts` | 插件构建配置 |
| `E:\Projects\ScriptAgent\idePlugin\src\main\resources\META-INF\plugin.xml` | 插件描述符 |
| `E:\Projects\ScriptAgent\idePlugin\src\main\kotlin\cf\wayzer\scriptagent\ideaplugin\DefinitionsProvider.kt` | 现有脚本定义注册 |
| `E:\Projects\ScriptAgent\src\main\kotlin\impl\config\IDEMode.kt` | IDE 编译配置（需改造） |
| `E:\Projects\ScriptAgent\src\main\kotlin\impl\config\SAConfiguration.kt` | 运行时编译配置 |
| `E:\Projects\ScriptAgent\src\main\kotlin\impl\config\CompileRefineContext.kt` | 编译配置上下文 |
| `E:\Projects\ScriptAgent\src\main\kotlin\define\annotations\*` | DSL 注解定义 |
| `E:\Projects\ScriptAgent\src\main\kotlin\define\Script.kt` | SAScript 基类 |
| `E:\Projects\ScriptAgent4MindustryExt\buildSrc\src\SAMetadataModules.kt` | 需要废弃的源集生成 |
| `E:\Projects\ScriptAgent4MindustryExt\buildSrc\src\ModuleParser.kt` | 需要废弃的 .metadata 解析 |
