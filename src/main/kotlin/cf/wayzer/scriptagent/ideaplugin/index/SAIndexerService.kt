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

    fun getDepends(project: Project, file: VirtualFile): List<DependsData> {
        val info = getInfo(project, file) ?: return emptyList()
        val module = Config.getModulesById(info.id).asReversed().firstOrNull {
            findScript(project, it) != null
        }
        if (module != null) {
            return info.depends + DependsData(module, "module")
        }
        return info.depends
    }

    fun findScript(project: Project, id: String): VirtualFile? {
        return FileBasedIndex.getInstance()
            .getContainingFiles(index, id, GlobalSearchScope.projectScope(project))
            .firstOrNull()
    }
}