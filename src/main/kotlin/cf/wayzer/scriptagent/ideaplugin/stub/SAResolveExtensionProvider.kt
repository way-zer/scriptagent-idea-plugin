package cf.wayzer.scriptagent.ideaplugin.stub

import cf.wayzer.scriptagent.ideaplugin.deriveScriptFqName
import cf.wayzer.scriptagent.ideaplugin.index.PsiAnnotationExtractor
import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaScriptModule
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtension
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionFile
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionNavigationTargetsProvider
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.utils.mapToSetOrEmpty

/**
 * Provides resolve extensions that generate stub classes for scripts with @Export members.
 */
@OptIn(KaExperimentalApi::class, KaSpiExtensionPoint::class)
class SAResolveExtensionProvider : KaResolveExtensionProvider() {
    override fun provideExtensionsFor(module: KaModule): List<KaResolveExtension> {
        if (module !is KaScriptModule) return emptyList()
        // 只取 VirtualFile：KaScriptModule.file 可能是上一代 PSI，缓存 KtFile 会在 PSI 重建后失效
        val project = module.project
        val vFile = module.file.virtualFile
        return listOf(SAResolveExtension(project, vFile))
    }
}

@OptIn(KaExperimentalApi::class, KaSpiExtensionPoint::class)
private class SAResolveExtension(
    private val project: Project,
    private val vFile: VirtualFile,
) : KaResolveExtension() {
    private val files by lazy {
        // Find inject dependencies from the current script's file annotations
        // KtFile 每次从 VirtualFile 现取（缓存会在 PSI 重建后变成 Invalid PSI Element）
        val ktFile = vFile.toPsiFile(project) as? KtFile ?: return@lazy emptyList()
        val fileAnnotations = ktFile.fileAnnotationList?.annotationEntries ?: return@lazy emptyList()
        val injectDeps = fileAnnotations
            .filter { it.shortName?.asString() == "Depends" }
            .mapNotNull { PsiAnnotationExtractor.extractDepends(it) }
            .filter { it.inject.isNotEmpty() }
            .map { it.name }

        injectDeps.map { depId ->
            SAStubFile(project, depId)
        }
    }

    override fun getKtFiles(): List<KaResolveExtensionFile> = files

    override fun getContainedPackages(): Set<FqName> = files.mapToSetOrEmpty {
        it.getFilePackageName()
    }

    override fun dispose() {}
}

@OptIn(KaExperimentalApi::class, KaSpiExtensionPoint::class)
internal class SAStubFile(
    private val project: Project,
    private val scriptId: String,
) : KaResolveExtensionFile() {
    val classFqName = deriveScriptFqName(scriptId)

    // 只缓存 VirtualFile（跨 PSI 重建稳定）；KtFile 每次现取 ——
    // 缓存的 KtFile 在脚本配置重载/PSI 重建后会变成 "Invalid PSI Element ... different providers"。
    private val vFile by lazy { SAIndexerService.findScript(project, scriptId) }
    val ktFile: KtFile? get() = vFile?.toPsiFile(project) as? KtFile
    val pkg = classFqName.first
    val className = classFqName.second

    override fun getFileName(): String = vFile?.name ?: "_$className.kt"

    override fun getFilePackageName(): FqName = FqName.fromSegments(pkg.split("."))

    override fun getTopLevelClassifierNames(): Set<Name> = setOf(Name.identifier(className))

    override fun getTopLevelCallableNames(): Set<Name> = emptySet()

    override fun buildFileText(): String {
        val cache = StubCacheService.getInstance(project)
        val exports = cache.getCached(scriptId)
        if (exports == null) {
            // First sight in this session: generate in the background. This analysis keeps the stub
            // unresolved (red); it is refreshed once generation completes.
            cache.requestRefresh(scriptId)
            return ""
        }
        if (exports.isEmpty()) return ""
        return buildString {
            appendLine("package $pkg")
            appendLine("abstract class $className {")
            for (member in exports) {
                appendLine("    $member")
            }
            appendLine("}")
        }
    }

    override fun createNavigationTargetsProvider(): KaResolveExtensionNavigationTargetsProvider {
        return SANavigationTargetsProvider(ktFile)
    }
}

@OptIn(KaExperimentalApi::class, KaSpiExtensionPoint::class)
private class SANavigationTargetsProvider(
    private val ktFile: KtFile?,
) : KaResolveExtensionNavigationTargetsProvider() {
    override fun KaSession.getNavigationTargets(element: KtElement): Collection<PsiElement> {
        if (ktFile == null) return emptyList()
        val declarations = ktFile.script?.declarations ?: ktFile.declarations

        return when (element) {
            is KtNamedFunction -> declarations.filter {
                it is KtNamedFunction && it.name == element.name
            }

            is KtProperty -> declarations.filter {
                it is KtProperty && it.name == element.name
            }

            else -> listOf(ktFile)
        }
    }
}
