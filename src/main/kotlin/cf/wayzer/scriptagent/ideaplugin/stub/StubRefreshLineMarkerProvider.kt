package cf.wayzer.scriptagent.ideaplugin.stub

import cf.wayzer.scriptagent.ideaplugin.index.PsiAnnotationExtractor
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement

/**
 * Shows a refresh gutter icon on @file:Depends(..., inject="xxx") lines.
 * Clicking the icon triggers an async re-generation of the stub via Analysis API.
 */
class StubRefreshLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement) = createMarkerIfApplicable(element)

    private fun createMarkerIfApplicable(element: PsiElement): LineMarkerInfo<PsiElement>? {
        val annotation = PsiAnnotationExtractor.asFileAnnotation(element) ?: return null
        if (annotation.shortName?.asString() != "Depends") return null

        val data = PsiAnnotationExtractor.extractDepends(annotation) ?: return null
        if (data.inject.isEmpty()) return null
        val depId = data.name

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
