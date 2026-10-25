package cf.wayzer.scriptagent.ideaplugin.maven

import cf.wayzer.scriptAgent.define.SAIDEModeApi
import cf.wayzer.scriptAgent.define.SAInternalApi
import cf.wayzer.scriptAgent.define.ScriptInfo
import cf.wayzer.scriptAgent.define.ScriptSource
import cf.wayzer.scriptAgent.impl.ManagerImpl
import cf.wayzer.scriptAgent.impl.SACompiledScript
import cf.wayzer.scriptAgent.impl.config.IDEMode
import cf.wayzer.scriptAgent.util.DependencyManager
import cf.wayzer.scriptAgent.util.maven.Dependency
import cf.wayzer.scriptagent.ideaplugin.companion.SAModuleLibraries
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import cf.wayzer.scriptagent.ideaplugin.index.withIndexInSmartMode
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ActionCallback
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service responsible for downloading dependencies declared in scripts.
 * Supports downloading for a single script and its transitive dependencies.
 * Shows progress in IDE status bar via withBackgroundProgress.
 * After download completes, ScriptConfiguration should be refreshed to pick up new JARs.
 *
 * 插件里**只有**两个允许联网的入口，且都只由用户显式触发：
 * [downloadForScript]（Sync）与 [downloadSources]（库类横幅上的 "Download sources (SA)"）。
 */
@Service(Service.Level.PROJECT)
class MavenLibraryService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val downloading = AtomicBoolean(false)

    val isDownloading: Boolean get() = downloading.get()

    /** 已确认拉不到 sources 的坐标：会话内不再重试，免得每次点都白等一遍 404。 */
    private val sourcesUnavailable = ConcurrentHashMap.newKeySet<String>()

    /**
     * Downloads dependencies for a script and all its transitive dependencies.
     * Uses the same [ManagerImpl.importToLibraries] as runtime.
     *
     * 同时把 Kotlin runtime 下到 `<libs>`，**排在脚本依赖之前**：注册的 global 是解析依赖时的 baseline
     * （见 [IDEMode.resolveKotlinRuntime]）。分析阶段（refine）与 companion install 都是离线的，
     * 点一次 Sync 把两边一起补齐。runtime 部分**尽力而为**：失败只记进 `errors` 一并报出，
     * 不中断脚本依赖的下载。
     * Shows background progress in the IDE.
     */
    @OptIn(SAInternalApi::class, SAIDEModeApi::class)
    fun downloadForScript(
        scriptId: String,
        onComplete: suspend (success: Boolean, message: String) -> Unit = { _, _ -> },
    ) {
        if (!downloading.compareAndSet(false, true)) {
            scope.launch { onComplete(false, "Download already in progress") }
            return
        }
        scope.launch {
            try {
                val (errors, visited) = withBackgroundProgress(project, "Downloading Script Dependencies") {
                    val errors = mutableListOf<String>()
                    val visited = mutableSetOf<String>()
                    // 先注册 Kotlin runtime 的 global（解析依赖的 baseline），再解析脚本依赖 —— 顺序不能反。
                    // 尽力而为：失败不中断脚本依赖，记进 errors 一起报给用户。
                    // 这也是唯一允许联网的调用点（用户点了 Sync），其余路径一律 offline = true。
                    try {
                        IDEMode.resolveKotlinRuntime()
                    } catch (e: Exception) {
                        errors += "Kotlin runtime: ${e.message}"
                    }
                    downloadRecursive(scriptId, visited, errors)
                    errors to visited
                }
                val message = if (errors.isEmpty()) {
                    "Dependencies resolved for ${visited.size} scripts"
                } else {
                    "Resolved with ${errors.size} errors:\n${errors.joinToString("\n")}"
                }
                onComplete(errors.isEmpty(), message)
            } catch (e: IndexNotReadyException) {
                // 兜底：下载途中工程又进入 dumb 模式（文件变更触发重建索引）。这不是下载失败。
                onComplete(false, "Indexing in progress, please retry Sync after it finishes")
            } catch (e: Exception) {
                onComplete(false, "Download failed: ${e.message}")
            } finally {
                downloading.set(false)
            }
        }
    }

    /**
     * 下载单个依赖的 sources —— 平台横幅（[SAAttachSourcesProvider]）的 "Download sources" 用。
     * 结果通过 [callback] 结束；已确认拉不到的坐标不再重试。
     */
    fun downloadSources(dependency: Dependency, callback: ActionCallback) {
        val key = keyOf(dependency)
        if (key in sourcesUnavailable) {
            callback.setRejected()
            return
        }
        scope.launch {
            val ok = runCatching { DependencyManager.ensureSources(dependency) }.getOrDefault(false)
            if (ok) {
                SAModuleLibraries.install(project) // 重挂 SOURCES，横幅随之消失
                callback.setDone()
            } else {
                sourcesUnavailable += key
                callback.setRejected()
            }
        }
    }

    /** 该坐标的 sources 是否已确认拉不到（会话内）。 */
    fun isSourcesUnavailable(dependency: Dependency): Boolean = keyOf(dependency) in sourcesUnavailable

    private fun keyOf(dependency: Dependency) = dependency.id + ":" + dependency.version

    @OptIn(SAInternalApi::class)
    private suspend fun downloadRecursive(
        scriptId: String,
        visited: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        if (!visited.add(scriptId)) return
        val (info, depends) = project.withIndexInSmartMode {
            val file = SAIndexerService.findScript(project, scriptId) ?: return@withIndexInSmartMode null
            val info = SAIndexerService.getInfo(project, file) ?: return@withIndexInSmartMode null
            info to SAIndexerService.getDepends(project, file)
        } ?: return

        val script = SACompiledScript(ScriptSource.Dummy(ScriptInfo.getOrCreate(scriptId))).apply {
            importData = info.imports.toSet()
            dependsData = depends.toSet()
        }

        ManagerImpl.importToLibraries(script) { msg, key, e ->
            errors.add("$msg: $key${e?.let { " (${it.message})" } ?: ""}")
        }

        for (dep in depends) {
            if (dep.soft) continue
            downloadRecursive(dep.name, visited, errors)
        }
    }

    companion object {
        fun getInstance(project: Project): MavenLibraryService = project.service()
    }
}
