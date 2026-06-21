package cf.wayzer.scriptagent.ideaplugin.toolwindow

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class ScriptIndexToolWindow(val cs: CoroutineScope) : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ScriptIndexPanel(project, cs)
        val content = ContentFactory.getInstance().createContent(panel, "Scripts", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class ScriptIndexPanel(
    private val project: Project,
    private val cs: CoroutineScope
) : JPanel(BorderLayout()) {
    private val textArea = JBTextArea().apply {
        isEditable = false
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }

    init {
        add(JButton("Refresh").apply {
            addActionListener { cs.launch { refresh() } }
        }, BorderLayout.NORTH)
        add(JBScrollPane(textArea), BorderLayout.CENTER)
        DumbService.getInstance(project).runWhenSmart {
            cs.launch { refresh() }
        }
    }

    private suspend fun refresh() {
        val text = readAction {
            val sb = StringBuilder()
            val allKeys = SAIndexerService.getAllIds(project)

            sb.appendLine("=== SA Script Index (${allKeys.size} scripts) ===")
            sb.appendLine()

            for (key in allKeys) {
                val file = SAIndexerService.findScript(project, key)!!
                val info = SAIndexerService.getInfo(project, file)
                if (info == null) {
                    sb.appendLine("[$key] ${file.path}")
                    continue
                }
                sb.appendLine("[$key] ${file.name}${if (info.isModule) " (module)" else ""}")
                val deps = SAIndexerService.getDepends(project, file)
                sb.appendLine("  → ${deps.joinToString(", ") { it.name }}")
                if (info.depends.isNotEmpty())
                    sb.appendLine("  depends: ${info.depends.map { it.name }}")
                if (info.imports.isNotEmpty()) {
                    sb.appendLine("  imports:")
                    for (it in info.imports) {
                        sb.appendLine("  - [${it.type}] ${it.value}")
                    }
                }
                sb.appendLine()
            }
            sb.toString()
        }

        withContext(Dispatchers.EDT) {
            textArea.text = text
            textArea.caretPosition = 0
        }
    }
}