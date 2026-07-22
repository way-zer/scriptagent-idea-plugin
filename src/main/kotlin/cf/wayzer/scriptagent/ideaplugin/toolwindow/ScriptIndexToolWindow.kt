package cf.wayzer.scriptagent.ideaplugin.toolwindow

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import cf.wayzer.scriptagent.ideaplugin.stub.extractExportSignaturesFromPsi
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
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
        val text = readAction {
            val editor = FileEditorManager.getInstance(project).selectedEditor
            val vFile = editor?.file ?: return@readAction "No file open"
            if (vFile.extension != "kts") return@readAction "Not a .kts file: ${vFile.name}"

            val psiFile = PsiManager.getInstance(project).findFile(vFile)
                    as? org.jetbrains.kotlin.psi.KtFile
                ?: return@readAction "Cannot resolve PSI for: ${vFile.name}"

            val exports = extractExportSignaturesFromPsi(psiFile)
            if (exports.isEmpty()) return@readAction "No @Export members found in: ${vFile.name}"

            val className = vFile.nameWithoutExtension.replaceFirstChar { it.uppercaseChar() }
            buildString {
                appendLine("// Stub as seen by FIR (from PSI)")
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