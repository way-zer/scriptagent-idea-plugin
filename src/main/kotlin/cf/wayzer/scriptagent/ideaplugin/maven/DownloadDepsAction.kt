package cf.wayzer.scriptagent.ideaplugin.maven

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import cf.wayzer.scriptagent.ideaplugin.stub.StubCacheService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
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
                // Refresh stubs for this script and its dependencies
                val stubService = StubCacheService.getInstance(project)
                stubService.refreshAsync(info.id)
                for (dep in info.depends) {
                    if (!dep.soft) stubService.refreshAsync(dep.name)
                }

                // Trigger ScriptConfiguration reload
                val ktFile = file.toPsiFile(project) as? KtFile
                if (ktFile != null) {
                    ReloadScriptConfigurationService.getInstance(project).reloadScriptData(ktFile)
                }
            }
        }
    }
}
