package cf.wayzer.scriptagent.ideaplugin.stub

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtAnnotationEntry

/**
 * Shows a refresh gutter icon on @file:Depends(..., inject="xxx") lines.
 * Clicking the icon triggers an async re-generation of the stub via Analysis API.
 */
class StubRefreshLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement) = createMarkerIfApplicable(element)

    private fun createMarkerIfApplicable(element: PsiElement): LineMarkerInfo<PsiElement>? {
        val annotation = element as? KtAnnotationEntry ?: return null
        if (annotation.shortName?.asString() != "Depends") return null

        // Check it's a file-level annotation
        val ktFile = annotation.containingKtFile
        val fileAnnotations = ktFile.fileAnnotationList?.annotationEntries ?: return null
        if (annotation !in fileAnnotations) return null

        // Check it has inject parameter
        val injectArg = annotation.valueArguments.find {
            it.getArgumentName()?.asName?.asString() == "inject"
        } ?: return null
        val injectValue = injectArg.getArgumentExpression()?.text?.removeSurrounding("\"")
        if (injectValue.isNullOrEmpty()) return null

        // Extract dependency name
        val nameArg = annotation.valueArguments.firstOrNull {
            it.getArgumentName()?.asName?.asString().let { n -> n == null || n == "name" }
        }
        val depId = nameArg?.getArgumentExpression()?.text?.removeSurrounding("\"") ?: return null

        val anchor = annotation.atSymbol ?: annotation.firstChild ?: return null

        @Suppress("DEPRECATION")
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            AllIcons.Actions.Refresh,
            com.intellij.util.Function<PsiElement, String> { "Refresh stub for '$depId'" },
            GutterIconNavigationHandler<PsiElement> { _, elt ->
                val project = elt?.project ?: return@GutterIconNavigationHandler
                StubCacheService.getInstance(project).refreshAsync(depId)
            },
            GutterIconRenderer.Alignment.LEFT,
        )
    }
}
