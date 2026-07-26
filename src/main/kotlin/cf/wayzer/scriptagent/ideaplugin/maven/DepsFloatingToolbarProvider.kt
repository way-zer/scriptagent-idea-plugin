package cf.wayzer.scriptagent.ideaplugin.maven

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.editor.toolbar.floating.isInsideMainEditor

private const val ACTION_GROUP = "ScriptAgent.DownloadDepsActionGroup"

class DepsFloatingToolbarProvider : AbstractFloatingToolbarProvider(ACTION_GROUP) {
    override val autoHideable: Boolean = false

    @Deprecated("Deprecated in Java")
    override fun isApplicable(dataContext: DataContext): Boolean {
        if (!isInsideMainEditor(dataContext)) return false
        val file = dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return false
        return file.extension == "kts"
    }

    override fun register(dataContext: DataContext, component: FloatingToolbarComponent, parentDisposable: Disposable) {
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return
        // Always show for .kts files — the action itself will check if deps are needed
        component.scheduleShow()
    }
}
