package cf.wayzer.scriptagent.ideaplugin.index

import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.define.annotations.DependsData
import cf.wayzer.scriptAgent.define.annotations.ImportData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import java.io.Serializable

object SAIndexerService {
    data class ScriptIndexInfo(
        val id: String,
        val depends: List<DependsData>,
        val imports: List<ImportData>,
        val implements: List<String>,
        val isModule: Boolean = false,
    ) : Serializable

    val index = ID.create<String, ScriptIndexInfo>("cf.wayzer.scriptAgent.scriptIndex")

    fun getAllIds(project: Project): List<String> {
        return FileBasedIndex.getInstance()
            .getAllKeys(index, project)
            .sorted()
    }

    fun getInfo(project: Project, file: VirtualFile): ScriptIndexInfo? {
        return FileBasedIndex.getInstance().getFileData(index, file, project)
            .values.firstOrNull()
    }

    /**
     * Script dependencies including the implicitly inherited closest `module`, mirroring the runtime
     * (`SAConfiguration.handleOnCollectingData`): every script auto-depends on its nearest existing
     * module. An explicit `@Depends` with the same id wins over the implicit entry (same as runtime).
     *
     * Resolved at query time rather than stored in [ScriptIndexInfo]: knowing whether the module script
     * exists requires the index itself, which must not be re-entered while indexing.
     */
    fun getDepends(project: Project, file: VirtualFile): List<DependsData> {
        val info = getInfo(project, file) ?: return emptyList()
        val module = Config.getModulesById(info.id).asReversed().firstOrNull {
            findScript(project, it) != null
        } ?: return info.depends
        return (info.depends + DependsData(module, "module")).distinctBy { it.name }
    }

    /**
     * 传递依赖：BFS 所有非 soft @Depends（[getDepends] 已包含隐式「最近 module」依赖）。
     * 返回遍历到的全部脚本 id（含自身）。
     */
    fun getAllDepends(project: Project, file: VirtualFile): List<String> {
        val result = LinkedHashSet<String>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        resolveRootAndId(file)?.second?.let { queue += it }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            val vFile = findScript(project, id) ?: continue
            result += id
            getDepends(project, vFile).asSequence().filter { !it.soft }.forEach { queue += it.name }
        }
        return result.toList()
    }

    fun findScript(project: Project, id: String): VirtualFile? {
        return FileBasedIndex.getInstance()
            .getContainingFiles(index, id, GlobalSearchScope.projectScope(project))
            .firstOrNull()
    }
}