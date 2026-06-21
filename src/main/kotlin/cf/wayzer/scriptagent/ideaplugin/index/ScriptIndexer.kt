package cf.wayzer.scriptagent.ideaplugin.index

import cf.wayzer.scriptagent.ideaplugin.index.SAIndexerService.ScriptIndexInfo
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.jetbrains.kotlin.idea.core.script.v1.readObject
import org.jetbrains.kotlin.idea.core.script.v1.writeObject
import java.io.DataInput
import java.io.DataOutput

class ScriptIndexer : FileBasedIndexExtension<String, ScriptIndexInfo>() {
    override fun getName() = SAIndexerService.index
    override fun getVersion(): Int = 8
    override fun dependsOnFileContent(): Boolean = true
    override fun traceKeyHashToVirtualFileMapping(): Boolean = true
    override fun getInputFilter() = ScriptFileFilter
    override fun getKeyDescriptor(): KeyDescriptor<String?> = EnumeratorStringDescriptor()
    override fun getValueExternalizer() = Externalizer

    override fun getIndexer() = object : DataIndexer<String, ScriptIndexInfo, FileContent> {
        override fun map(inputData: FileContent): Map<String, ScriptIndexInfo> {
            val file = inputData.file
            val (_, id) = resolveRootAndId(file) ?: return emptyMap()
            val psiFile = inputData.psiFile

            val (depends, imports, implements) = PsiAnnotationExtractor.extractAll(psiFile)
            val isModule = file.nameWithoutExtension == "module"
            return mapOf(
                id to ScriptIndexInfo(id, depends, imports, implements, isModule)
            )
        }
    }


    object Externalizer : DataExternalizer<ScriptIndexInfo> {
        override fun save(out: DataOutput, value: ScriptIndexInfo) {
            out.writeObject(value)
        }

        override fun read(input: DataInput): ScriptIndexInfo {
            return input.readObject<ScriptIndexInfo>()
        }
    }
}