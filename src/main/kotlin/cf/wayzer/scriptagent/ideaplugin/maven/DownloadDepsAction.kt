package cf.wayzer.scriptagent.ideaplugin.maven

import cf.wayzer.scriptagent.ideaplugin.companion.SAModuleLibraries
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import cf.wayzer.scriptagent.ideaplugin.stub.StubCacheService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.readAction
import org.jetbrains.kotlin.idea.core.script.k2.ReloadScriptConfigurationService
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.psi.KtFile

/**
 * Action triggered from the floating toolbar in .kts editors.
 * Downloads dependencies, refreshes stubs, and triggers ScriptConfiguration re-evaluation.
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
        if (project != null) {
            e.presentation.isEnabled = !MavenLibraryService.getInstance(project).isDownloading
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val info = SAIndexerService.getInfo(project, file) ?: return

        MavenLibraryService.getInstance(project).downloadForScript(info.id) { success, message ->
            val type = if (success) NotificationType.INFORMATION else NotificationType.ERROR
            NotificationGroupManager.getInstance()
                .getNotificationGroup("ScriptAgent")
                .createNotification("Script Dependencies", message, type)
                .notify(project)

            if (success) {
                // Generate stubs first, then reload: the new configuration must be analyzed against
                // up-to-date stubs (previously fire-and-forget, racing the reload).
                val stubService = StubCacheService.getInstance(project)
                stubService.refreshAndAwait(info.id)
                for (dep in readAction { SAIndexerService.getDepends(project, file) }) {
                    if (!dep.soft) stubService.refreshAndAwait(dep.name)
                }

                // New jars may have arrived: refresh the companion module library.
                SAModuleLibraries.install(project)

                // PSI/index access from the download coroutine (Dispatchers.Default) needs a read action.
                readAction {
                    val ktFile = file.toPsiFile(project) as? KtFile
                    if (ktFile != null) {
                        ReloadScriptConfigurationService.getInstance(project).reloadScriptData(ktFile)
                    }
                }
            }
        }
    }
}
