package cf.wayzer.scriptagent.ideaplugin.index

import cf.wayzer.scriptAgent.define.annotations.DependsData
import cf.wayzer.scriptAgent.define.annotations.ImportData
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile

/**
 * Extracts `@file:Depends`, `@file:Import`, `@file:Implement` annotations from a KtFile's PSI tree.
 */
object PsiAnnotationExtractor {
    fun extractAll(psiFile: PsiFile): Triple<List<DependsData>, List<ImportData>, List<String>> {
        val ktFile = psiFile as? KtFile ?: return Triple(emptyList(), emptyList(), emptyList())
        val fileAnnotations = ktFile.fileAnnotationList?.annotationEntries ?: emptyList()

        val depends = mutableListOf<DependsData>()
        val imports = mutableListOf<ImportData>()
        val implements = mutableListOf<String>()

        for (entry in fileAnnotations) {
            when (entry.shortName?.asString()) {
                "Depends" -> extractDepends(entry)?.let { depends.add(it) }
                "Import" -> extractImport(entry)?.let { imports.add(it) }
                "Implement" -> extractImplement(entry)?.let { implements.add(it) }
            }
        }
        return Triple(depends, imports, implements)
    }

    private fun extractDepends(entry: KtAnnotationEntry): DependsData? {
        val args = entry.valueArguments
        val name = args.firstOrNull { it.getArgumentName()?.asName?.asString().let { n -> n == null || n == "name" } }
            ?.getArgumentExpression()?.text?.removeSurrounding("\"") ?: return null
        val desc = args.find { it.getArgumentName()?.asName?.asString() == "desc" }
            ?.getArgumentExpression()?.text?.removeSurrounding("\"").orEmpty()
        val soft = args.find { it.getArgumentName()?.asName?.asString() == "soft" }
            ?.getArgumentExpression()?.text == "true"
        val inject = args.find { it.getArgumentName()?.asName?.asString() == "inject" }
            ?.getArgumentExpression()?.text?.removeSurrounding("\"") ?: ""
        return DependsData(name, desc, inject, soft)
    }

    private fun extractImport(entry: KtAnnotationEntry): ImportData? {
        val args = entry.valueArguments
        val value = args.firstOrNull { it.getArgumentName()?.asName?.asString().let { n -> n == null || n == "value" } }
            ?.getArgumentExpression()?.text?.removeSurrounding("\"") ?: return null

        val type = IMPORT_FLAGS.firstOrNull { (flagName, _) ->
            args.any { arg ->
                arg.getArgumentName()?.asName?.asString() == flagName &&
                        arg.getArgumentExpression()?.text == "true"
            }
        }?.second ?: inferType(value)

        return ImportData(type, value)
    }

    private fun extractImplement(entry: KtAnnotationEntry): String? {
        val expr = entry.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: return null
        return expr.removeSuffix("::class").takeIf { it.isNotEmpty() }
    }

    private val IMPORT_FLAGS = ImportData.Type.entries.map { type ->
        type.name.replaceFirstChar { it.lowercase() } to type
    }

    private fun inferType(value: String): ImportData.Type = when {
        value.contains(':') && value.count { it == ':' } >= 2 -> ImportData.Type.MavenDepends
        value.startsWith("http://") || value.startsWith("https://") -> ImportData.Type.MavenRepository
        value.contains(".*") -> ImportData.Type.DefaultImport
        value.startsWith("-") -> ImportData.Type.CompileArg
        else -> ImportData.Type.LibraryByClass
    }
}
