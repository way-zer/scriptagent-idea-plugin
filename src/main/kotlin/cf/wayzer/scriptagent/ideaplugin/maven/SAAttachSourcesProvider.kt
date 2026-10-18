package cf.wayzer.scriptagent.ideaplugin.maven

import cf.wayzer.scriptAgent.util.DependencyManager
import cf.wayzer.scriptAgent.util.maven.Dependency
import com.intellij.codeInsight.AttachSourcesProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.util.ActionCallback
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.psi.PsiFile

/**
 * 打开缺 sources 的库类时，平台横幅（Java 插件的 `AttachSourcesNotificationProvider`）会列出这里的动作，
 * 只管我们自己建进 companion library 的那些 jar。
 *
 * 名字带 "(SA)"：Maven 插件对同一个类也会摆一个 "Download Sources"，两者都叫 Download 分不清。
 */
class SAAttachSourcesProvider : AttachSourcesProvider {
    /** 旧签名，平台已改用 [getLibrariesActions]。 */
    @Deprecated("平台已改用 getLibrariesActions")
    override fun getActions(orderEntries: List<LibraryOrderEntry>, file: PsiFile) =
        emptyList<AttachSourcesProvider.AttachSourcesAction>()

    override fun getLibrariesActions(
        libraryEntities: Collection<LibraryEntity>,
        psiFile: PsiFile,
    ): Collection<AttachSourcesProvider.AttachSourcesAction> {
        val file = psiFile.virtualFile ?: return emptyList()
        val jarName = file.path.substringBefore("!/").substringAfterLast('/')
        val dependency = DependencyManager.dependencyOfJar(jarName) ?: return emptyList()
        if (dependency.sourcesVariant().getFile("jar").exists()) return emptyList()
        if (MavenLibraryService.getInstance(psiFile.project).isSourcesUnavailable(dependency)) return emptyList()
        return listOf(DownloadSourcesAction(dependency))
    }

    private class DownloadSourcesAction(private val dependency: Dependency) :
        AttachSourcesProvider.LightAttachSourcesAction {

        override fun getName() = "Download sources (SA)"
        override fun getBusyText() = "Downloading sources..."

        @Deprecated("平台已改用 perform(libraryEntities, project)")
        override fun perform(orderEntries: List<LibraryOrderEntry>) = ActionCallback.REJECTED

        override fun perform(libraryEntities: Collection<LibraryEntity>, project: Project): ActionCallback {
            val callback = ActionCallback()
            MavenLibraryService.getInstance(project).downloadSources(dependency, callback)
            return callback
        }
    }
}
