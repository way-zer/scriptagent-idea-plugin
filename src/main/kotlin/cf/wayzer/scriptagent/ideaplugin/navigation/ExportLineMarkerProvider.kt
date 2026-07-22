package cf.wayzer.scriptagent.ideaplugin.navigation

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Shows a gutter icon next to @Export-annotated declarations,
 * confirming the annotation is recognized by the plugin.
 */
class ExportLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement) = when (element) {
        is KtNamedFunction -> {
            if (hasExportAnnotation(element)) element.funKeyword?.let { createMarker(it, "Exported: ${element.name}") }
            else null
        }
        is KtProperty -> {
            if (hasExportAnnotation(element)) element.valOrVarKeyword?.let { createMarker(it, "Exported: ${element.name}") }
            else null
        }
        else -> null
    }

    private fun hasExportAnnotation(element: PsiElement): Boolean {
        val annotations = when (element) {
            is KtNamedFunction -> element.annotationEntries
            is KtProperty -> element.annotationEntries
            else -> return false
        }
        return annotations.any { it.shortName?.asString() == "Export" }
    }

    @Suppress("DEPRECATION")
    private fun createMarker(anchor: PsiElement, tooltip: String) = LineMarkerInfo(
        anchor,
        anchor.textRange,
        AllIcons.Gutter.ExtAnnotation,
        com.intellij.util.Function<PsiElement, String> { tooltip },
        null,
        GutterIconRenderer.Alignment.LEFT
    )
}
