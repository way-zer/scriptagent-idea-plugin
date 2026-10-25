package cf.wayzer.scriptagent.ideaplugin.toolwindow

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import cf.wayzer.scriptagent.ideaplugin.index.awaitSmartMode
import cf.wayzer.scriptagent.ideaplugin.stub.StubCacheService
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
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
import java.awt.FlowLayout
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
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Refresh Index").apply {
                addActionListener { cs.launch { refresh() } }
            })
            add(JButton("Show Stub (Current File)").apply {
                addActionListener { cs.launch { showCurrentFileStub() } }
            })

        }
        add(buttonPanel, BorderLayout.NORTH)
        add(JBScrollPane(textArea), BorderLayout.CENTER)
        DumbService.getInstance(project).runWhenSmart {
            cs.launch { refresh() }
        }
    }

    private suspend fun showCurrentFileStub() {
        DumbService.getInstance(project).awaitSmartMode()
        val text = readAction {
            val editor = FileEditorManager.getInstance(project).selectedEditor
            val vFile = editor?.file ?: return@readAction "No file open"
            if (vFile.extension != "kts") return@readAction "Not a .kts file: ${vFile.name}"

            // Find the script ID for the current file
            val info = SAIndexerService.getInfo(project, vFile)
                ?: return@readAction "Not indexed: ${vFile.name}"
            val scriptId = info.id

            // Refresh the stub via Analysis API
            val cacheService = StubCacheService.getInstance(project)
            cacheService.refreshSync(scriptId)

            val exports = cacheService.getCached(scriptId)
            if (exports.isNullOrEmpty()) return@readAction "No @Export members found in: ${vFile.name}"

            val className = vFile.nameWithoutExtension.replaceFirstChar { it.uppercaseChar() }
            buildString {
                appendLine("// Stub generated via Analysis API (FQN types)")
                appendLine("// Script: $scriptId")
                appendLine()
                appendLine("abstract class $className {")
                for (member in exports) {
                    appendLine("    $member")
                }
                appendLine("}")
            }
        }
        withContext(Dispatchers.EDT) {
            textArea.text = text
            textArea.caretPosition = 0
        }
    }

    private suspend fun refresh() {
        DumbService.getInstance(project).awaitSmartMode()
        val text = readAction {
            val sb = StringBuilder()
            val allKeys = SAIndexerService.getAllIds(project)

            sb.appendLine("=== SA Script Index (${allKeys.size} scripts) ===")
            sb.appendLine()

            for (key in allKeys) {
                val file = SAIndexerService.findScript(project, key) ?: continue
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