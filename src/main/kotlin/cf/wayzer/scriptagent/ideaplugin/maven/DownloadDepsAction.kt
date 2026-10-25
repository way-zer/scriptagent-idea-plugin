package cf.wayzer.scriptagent.ideaplugin.maven

import cf.wayzer.scriptagent.ideaplugin.companion.SAModuleLibraries
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import cf.wayzer.scriptagent.ideaplugin.index.withIndexInSmartMode
import cf.wayzer.scriptagent.ideaplugin.index.resolveRootAndId
import cf.wayzer.scriptagent.ideaplugin.stub.StubCacheService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbService
import org.jetbrains.kotlin.idea.core.script.k2.ReloadScriptConfigurationService
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.psi.KtFile

/**
 * Action triggered from the floating toolbar in .kts editors.
 * Downloads dependencies, refreshes stubs, and triggers ScriptConfiguration re-evaluation.
 *
 * 对 `module.kts` 我们把它呈现为 "Sync Module"：同一个流程，但对模块来说它额外决定了
 * companion module（lib/ 下的 .kt）能看到的 classpath。
 */
class DownloadDepsAction : AnAction(
    "Sync Script Dependencies",
    "Download dependencies, refresh stubs, and reload script configuration",
    AllIcons.Actions.BuildLoadChanges
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null
                && file != null && file.extension == "kts"
        if (file?.name == "module.kts") {
            e.presentation.text = "Sync Module"
            e.presentation.description = "Download module dependencies, refresh stubs, " +
                    "rebuild the companion module classpath, and reload script configuration"
        }
        if (project != null) {
            // 索引查询只在 smart 模式可用：dumb 模式期间先禁用，等索引结束再点。
            val dumb = DumbService.getInstance(project).isDumb
            e.presentation.isEnabled = !dumb && !MavenLibraryService.getInstance(project).isDownloading
            if (dumb) e.presentation.description = "Indexing in progress, please wait…"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        // 这里不查索引（dumb 模式下会抛 IndexNotReadyException）：script id 直接从文件路径推导。
        val scriptId = resolveRootAndId(file)?.second ?: return

        MavenLibraryService.getInstance(project).downloadForScript(scriptId) { success, message ->
            val type = if (success) NotificationType.INFORMATION else NotificationType.ERROR
            NotificationGroupManager.getInstance()
                .getNotificationGroup("ScriptAgent")
                .createNotification("Script Dependencies", message, type)
                .notify(project)

            if (success) {
                // Generate stubs first, then reload: the new configuration must be analyzed against
                // up-to-date stubs (previously fire-and-forget, racing the reload).
                val stubService = StubCacheService.getInstance(project)
                stubService.refreshAndAwait(scriptId)
                for (dep in project.withIndexInSmartMode { SAIndexerService.getDepends(project, file) }) {
                    if (!dep.soft) stubService.refreshAndAwait(dep.name)
                }

                // New jars may have arrived: refresh the companion module library.
                SAModuleLibraries.install(project)

                // 改工程模型可能触发重新索引：这段同样查索引，交给 helper 处理 smart 与重试。
                project.withIndexInSmartMode {
                    val ktFile = file.toPsiFile(project) as? KtFile
                    if (ktFile != null) {
                        ReloadScriptConfigurationService.getInstance(project).reloadScriptData(ktFile)
                    }
                }
            }
        }
    }
}
