package cf.wayzer.scriptagent.ideaplugin.stub

import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptagent.ideaplugin.index.PsiAnnotationExtractor
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.resolve.extensions.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*

/**
 * Provides resolve extensions that generate stub classes for scripts with @Export members.
 * Reads directly from PSI (no index dependency for exports).
 */
@OptIn(KaExperimentalApi::class, KaSpiExtensionPoint::class)
class SAResolveExtensionProvider : KaResolveExtensionProvider() {
    override fun provideExtensionsFor(module: KaModule): List<KaResolveExtension> {
        val scriptModule = module as? org.jetbrains.kotlin.analysis.api.projectStructure.KaScriptModule
            ?: return emptyList()
        val project = module.project ?: return emptyList()
        val ktFile = scriptModule.file

        // Find inject dependencies from the current script's file annotations
        val fileAnnotations = ktFile.fileAnnotationList?.annotationEntries ?: return emptyList()
        val injectDeps = fileAnnotations
            .filter { it.shortName?.asString() == "Depends" }
            .mapNotNull { PsiAnnotationExtractor.extractDepends(it) }
            .filter { it.inject.isNotEmpty() }
            .map { it.name }

        if (injectDeps.isEmpty()) return emptyList()

        // Generate stub files only for the inject dependencies
        val stubFiles = injectDeps.mapNotNull { depId ->
            val vFile = SAIndexerService.findScript(project, depId) ?: return@mapNotNull null
            SAStubFile(project, depId, vFile)
        }
        if (stubFiles.isEmpty()) return emptyList()
        return listOf(SAResolveExtension(stubFiles))
    }
}

@OptIn(KaExperimentalApi::class, KaSpiExtensionPoint::class)
private class SAResolveExtension(
    private val stubFiles: List<SAStubFile>,
) : KaResolveExtension() {

    override fun getKtFiles(): List<KaResolveExtensionFile> = stubFiles

    override fun getContainedPackages(): Set<FqName> {
        return setOf(FqName.ROOT)
    }

    override fun dispose() {}
}

@OptIn(KaExperimentalApi::class, KaSpiExtensionPoint::class)
internal class SAStubFile(
    private val project: Project,
    private val scriptId: String,
    private val scriptVFile: VirtualFile,
) : KaResolveExtensionFile() {

    private val className: String = scriptId.substringAfterLast(Config.idSeparator)
        .replaceFirstChar { it.uppercaseChar() }

    override fun getFileName(): String = "__stub_$className.kt"

    override fun getFilePackageName(): FqName = FqName.ROOT

    override fun getTopLevelClassifierNames(): Set<Name> = setOf(Name.identifier(className))

    override fun getTopLevelCallableNames(): Set<Name> = emptySet()

    override fun buildFileText(): String {
        val exports = extractExportSignatures()
        if (exports.isEmpty()) return "" // empty file, no stub needed
        return buildString {
            appendLine("abstract class $className {")
            for (member in exports) {
                appendLine("    $member")
            }
            appendLine("}")
        }
    }

    override fun createNavigationTargetsProvider(): KaResolveExtensionNavigationTargetsProvider {
        return SANavigationTargetsProvider(project, scriptId)
    }

    private fun extractExportSignatures(): List<String> {
        val psiFile = PsiManager.getInstance(project).findFile(scriptVFile) as? KtFile ?: return emptyList()
        return extractExportSignaturesFromPsi(psiFile)
    }
}

@OptIn(KaExperimentalApi::class, KaSpiExtensionPoint::class)
private class SANavigationTargetsProvider(
    private val project: Project,
    private val scriptId: String,
) : KaResolveExtensionNavigationTargetsProvider() {

    override fun KaSession.getNavigationTargets(element: KtElement): Collection<PsiElement> {
        val vFile = SAIndexerService.findScript(project, scriptId) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(vFile) as? KtFile ?: return emptyList()
        val declarations = psiFile.script?.declarations ?: psiFile.declarations

        val targetName = when (element) {
            is KtNamedFunction -> element.name
            is KtProperty -> element.name
            is KtClass -> element.name
            else -> null
        }

        if (targetName == null || targetName == className(scriptId)) {
            return listOf(psiFile)
        }

        val target = declarations.firstOrNull { decl ->
            when (decl) {
                is KtNamedFunction -> decl.name == targetName
                is KtProperty -> decl.name == targetName
                else -> false
            }
        }
        return listOfNotNull(target)
    }

    private fun className(scriptId: String): String =
        scriptId.substringAfterLast(Config.idSeparator).replaceFirstChar { it.uppercaseChar() }
}

/**
 * Extract @Export-annotated member signatures from a KtFile's PSI.
 * Returns abstract member declaration strings suitable for stub class generation.
 * Requires explicit type declarations on @Export members.
 */
fun extractExportSignaturesFromPsi(ktFile: KtFile): List<String> {
    val declarations = ktFile.script?.declarations ?: ktFile.declarations
    val results = mutableListOf<String>()

    for (decl in declarations) {
        if (!decl.annotationEntries.any { it.shortName?.asString() == "Export" }) continue
        when (decl) {
            is KtNamedFunction -> {
                val name = decl.name ?: continue
                val params = decl.valueParameters.joinToString(", ") { p ->
                    "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any?"}"
                }
                val returnType = decl.typeReference?.text ?: "Unit"
                results.add("abstract fun $name($params): $returnType")
            }
            is KtProperty -> {
                val name = decl.name ?: continue
                val type = decl.typeReference?.text ?: "Any?"
                val keyword = if (decl.isVar) "var" else "val"
                results.add("abstract $keyword $name: $type")
            }
        }
    }
    return results
}
