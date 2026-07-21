package cf.wayzer.scriptagent.ideaplugin.navigation

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFileAnnotationList
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Annotator that validates:
 * 1. @Depends("scriptId") — reports error if scriptId not found in index
 * 2. @Import(...) — reports error if zero or multiple type flags are set to true
 */
class ScriptAnnotationAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val annotationEntry = element as? KtAnnotationEntry ?: return
        // Only process file-level annotations (@file:Depends, @file:Import)
        if (annotationEntry.parent !is KtFileAnnotationList) return
        when (annotationEntry.shortName?.asString()) {
            "Depends" -> checkDepends(annotationEntry, holder)
            "Import" -> checkImport(annotationEntry, holder)
        }
    }

    private fun checkDepends(entry: KtAnnotationEntry, holder: AnnotationHolder) {
        val nameArg = findNameArgument(entry) ?: return
        val nameExpr = nameArg.getArgumentExpression() as? KtStringTemplateExpression ?: return
        val text = nameExpr.text
        if (!text.startsWith("\"") || !text.endsWith("\"")) return
        val scriptId = text.removeSurrounding("\"")
        if (scriptId.isEmpty()) return

        val project = entry.project
        if (SAIndexerService.findScript(project, scriptId) == null) {
            holder.newAnnotation(HighlightSeverity.ERROR, "未找到脚本: $scriptId")
                .range(nameExpr)
                .create()
        }
    }

    private fun checkImport(entry: KtAnnotationEntry, holder: AnnotationHolder) {
        val args = entry.valueArguments
        val activeFlags = IMPORT_TYPE_FLAGS.count { flagName ->
            args.any { arg ->
                arg.getArgumentName()?.asName?.asString() == flagName &&
                        arg.getArgumentExpression()?.text == "true"
            }
        }

        if (activeFlags == 0) {
            holder.newAnnotation(HighlightSeverity.ERROR, "必须指定一个导入类型（如 mavenDepends = true）")
                .range(entry)
                .create()
        } else if (activeFlags > 1) {
            holder.newAnnotation(HighlightSeverity.WARNING, "多个导入类型被设为 true，只有一个生效")
                .range(entry)
                .create()
        }
    }

    private fun findNameArgument(entry: KtAnnotationEntry): KtValueArgument? {
        val args = entry.valueArguments
        // Named "name" argument
        args.find { it.getArgumentName()?.asName?.asString() == "name" }
            ?.let { return it as? KtValueArgument }
        // First positional argument
        return args.firstOrNull()?.let { it as? KtValueArgument }
    }

    companion object {
        private val IMPORT_TYPE_FLAGS = listOf(
            "libraryByClass", "libraryByClassLoader", "libraryByName",
            "libraryByFile", "sourceFile", "mavenDepends", "mavenDependsSingle",
            "mavenRepository", "defaultImport", "compileArg"
        )
    }
}
