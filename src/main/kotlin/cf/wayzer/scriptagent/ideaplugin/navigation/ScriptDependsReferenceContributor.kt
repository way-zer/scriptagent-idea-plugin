package cf.wayzer.scriptagent.ideaplugin.navigation

import cf.wayzer.scriptagent.ideaplugin.index.PsiAnnotationExtractor
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Provides PSI references for script IDs in `@file:Depends("scriptId")` annotations.
 * Enables Ctrl+Click navigation and completion for script ID strings.
 */
class ScriptDependsReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(KtStringTemplateExpression::class.java),
            ScriptIdReferenceProvider()
        )
    }
}

private class ScriptIdReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val stringExpr = element as? KtStringTemplateExpression ?: return PsiReference.EMPTY_ARRAY
        if (!isInDependsNamePosition(stringExpr)) return PsiReference.EMPTY_ARRAY

        val scriptId = PsiAnnotationExtractor.stringValue(stringExpr) ?: return PsiReference.EMPTY_ARRAY
        if (scriptId.isEmpty()) return PsiReference.EMPTY_ARRAY

        // TextRange within the element (exclude quotes)
        val range = TextRange(1, stringExpr.textLength - 1)
        return arrayOf(ScriptIdReference(stringExpr, range, scriptId))
    }

    /**
     * Checks if a string expression is the `name` argument of a `@file:Depends` annotation.
     */
    private fun isInDependsNamePosition(expr: KtStringTemplateExpression): Boolean {
        val valueArg = expr.parent as? KtValueArgument ?: return false
        val entry = PsiAnnotationExtractor.asFileAnnotation(valueArg.parent?.parent) ?: return false
        if (entry.shortName?.asString() != "Depends") return false
        return PsiAnnotationExtractor.findNameArgument(entry) == valueArg
    }
}

private class ScriptIdReference(
    element: KtStringTemplateExpression,
    range: TextRange,
    private val scriptId: String,
) : PsiReferenceBase<KtStringTemplateExpression>(element, range, false) {

    override fun resolve(): PsiElement? {
        val project = element.project
        val targetFile = SAIndexerService.findScript(project, scriptId) ?: return null
        return PsiManager.getInstance(project).findFile(targetFile)
    }

    override fun getVariants(): Array<Any> {
        val project = element.project
        return SAIndexerService.getAllIds(project).map { id ->
            LookupElementBuilder.create(id)
                .withIcon(com.intellij.icons.AllIcons.Nodes.Plugin)
        }.toTypedArray()
    }
}
