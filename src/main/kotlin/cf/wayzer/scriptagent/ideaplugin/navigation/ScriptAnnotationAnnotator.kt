package cf.wayzer.scriptagent.ideaplugin.navigation

import cf.wayzer.scriptagent.ideaplugin.index.PsiAnnotationExtractor
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtAnnotationEntry

/**
 * Annotator that validates:
 * 1. @Depends("scriptId") — reports error if scriptId not found in index
 * 2. @Import(...) — reports error if zero or multiple type flags are set to true
 */
class ScriptAnnotationAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val entry = PsiAnnotationExtractor.asFileAnnotation(element) ?: return
        when (entry.shortName?.asString()) {
            "Depends" -> checkDepends(entry, holder)
            "Import" -> checkImport(entry, holder)
        }
    }

    private fun checkDepends(entry: KtAnnotationEntry, holder: AnnotationHolder) {
        val nameArg = PsiAnnotationExtractor.findNameArgument(entry) ?: return
        val nameExpr = nameArg.getArgumentExpression() ?: return
        val scriptId = PsiAnnotationExtractor.stringValue(nameExpr) ?: return
        if (scriptId.isEmpty()) return

        val project = entry.project
        if (SAIndexerService.findScript(project, scriptId) == null) {
            holder.newAnnotation(HighlightSeverity.ERROR, "未找到脚本: $scriptId")
                .range(nameExpr)
                .create()
        }
    }

    private fun checkImport(entry: KtAnnotationEntry, holder: AnnotationHolder) {
        val activeFlags = PsiAnnotationExtractor.activeImportFlags(entry).size
        when {
            activeFlags == 0 -> holder.newAnnotation(
                HighlightSeverity.ERROR, "必须指定一个导入类型（如 mavenDepends = true）"
            ).range(entry).create()

            activeFlags > 1 -> holder.newAnnotation(
                HighlightSeverity.WARNING, "多个导入类型被设为 true，只有一个生效"
            ).range(entry).create()
        }
        checkImportEnv(entry, holder)
    }

    /**
     * `env` 是环境表达式（`|` 分隔，`!` 取反），只做语法校验 —— 其它环境的 token 本进程不知道，
     * 不能做白名单校验。能确定写错的只有 `!` 后面没写环境名这种。
     */
    private fun checkImportEnv(entry: KtAnnotationEntry, holder: AnnotationHolder) {
        val envExpr = entry.valueArguments.firstOrNull {
            it.getArgumentName()?.asName?.asString() == "env"
        }?.getArgumentExpression() ?: return
        val env = PsiAnnotationExtractor.stringValue(envExpr) ?: return
        if (env.split('|').any { it.trim() == "!" }) {
            holder.newAnnotation(
                HighlightSeverity.WARNING, "env 中 '!' 后缺少环境名"
            ).range(envExpr).create()
        }
    }
}
