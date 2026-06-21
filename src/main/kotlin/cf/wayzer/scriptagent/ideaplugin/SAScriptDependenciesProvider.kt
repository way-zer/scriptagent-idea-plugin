package cf.wayzer.scriptagent.ideaplugin

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.core.script.k2.modules.K2IdeScriptAdditionalIdeaDependenciesProvider
import org.jetbrains.kotlin.idea.core.script.k2.modules.KotlinScriptLibraryEntity

/**
 * Provides per-file script dependency information to the K2 Kotlin IDE analysis engine.
 * Returns dependent script files as "related modules" so their symbols become visible.
 */
class SAScriptDependenciesProvider : K2IdeScriptAdditionalIdeaDependenciesProvider {

    override fun getRelatedModules(file: VirtualFile, project: Project): List<VirtualFile> {
        return SAIndexerService.getDepends(project, file).filter { !it.soft }.mapNotNull { dep ->
            SAIndexerService.findScript(project, dep.name)
        }
    }

    override fun getRelatedLibraries(file: VirtualFile, project: Project): List<KotlinScriptLibraryEntity> {
        return emptyList()
    }
}
