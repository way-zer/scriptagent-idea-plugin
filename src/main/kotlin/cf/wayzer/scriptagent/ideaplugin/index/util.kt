package cf.wayzer.scriptagent.ideaplugin.index

import cf.wayzer.scriptAgent.Config
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import org.jetbrains.kotlin.idea.KotlinFileType

fun resolveRootAndId(file: VirtualFile): Pair<VirtualFile, String>? {
    if (file.extension != "kts") return null
    var dir = file.parent
    var path = file.name
    while (dir != null) {
        if (dir.name == "scripts" || dir.name.startsWith("@") || dir.findChild(".SARoot") != null) {
            val id = path.removeSuffix(Config.scriptSuffix)
                .removeSuffix(Config.moduleIdSuffix)
            return dir to id
        }
        path = dir.name + Config.idSeparator + path
        dir = dir.parent
    }
    return null
}

object ScriptFileFilter : DefaultFileTypeSpecificInputFilter(KotlinFileType.INSTANCE) {
    override fun acceptInput(file: VirtualFile): Boolean {
        return file.extension == "kts" && resolveRootAndId(file) != null
    }
}
