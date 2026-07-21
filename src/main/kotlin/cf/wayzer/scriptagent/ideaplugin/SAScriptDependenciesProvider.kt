package cf.wayzer.scriptagent.ideaplugin

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.core.script.v1.ScriptAdditionalIdeaDependenciesProvider

/**
 * Provides per-file script dependency information to the K2 Kotlin IDE analysis engine.
 *
 * Currently SA scripts all reside in a single IntelliJ module, so [getRelatedModules] returns empty.
 * The real per-file dependency resolution is handled by ScriptConfiguration (IDEMode.refineForDepsIDEMode).
 *
 * This provider is kept as a hook for future use (e.g., multi-module project layouts).
 */
class SAScriptDependenciesProvider : ScriptAdditionalIdeaDependenciesProvider {

    override fun getRelatedModules(file: VirtualFile, project: Project): List<Module> {
        // SA scripts live in a single module; cross-script visibility is handled by ScriptConfiguration.
        return emptyList()
    }

    override fun getRelatedLibraries(file: VirtualFile, project: Project): List<Library> {
        return emptyList()
    }
}
