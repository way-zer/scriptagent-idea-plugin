package cf.wayzer.scriptagent.ideaplugin.index

import cf.wayzer.scriptAgent.define.annotations.DependsData
import cf.wayzer.scriptAgent.define.annotations.ImportData
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.*

/**
 * Extracts `@file:Depends`, `@file:Import`, `@file:Implement` annotations from a KtFile's PSI tree.
 *
 * All annotation-argument parsing is centralized here so that the indexer, the annotator,
 * the reference contributor and the stub-refresh marker cannot drift apart.
 */
object PsiAnnotationExtractor {
    fun extractAll(psiFile: PsiFile): Triple<List<DependsData>, List<ImportData>, List<String>> {
        val ktFile = psiFile as? KtFile ?: return Triple(emptyList(), emptyList(), emptyList())
        return extractAll(ktFile.fileAnnotationList?.annotationEntries ?: emptyList())
    }

    fun extractAll(fileAnnotations: List<KtAnnotationEntry>): Triple<List<DependsData>, List<ImportData>, List<String>> {
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

    /** Returns [element] as a file-level annotation entry (`@file:X`), or null. */
    fun asFileAnnotation(element: PsiElement?): KtAnnotationEntry? {
        val entry = element as? KtAnnotationEntry ?: return null
        return entry.takeIf { it.parent is KtFileAnnotationList }
    }

    /** The `name` argument of `@Depends`: the named `name` argument, or the first positional one. */
    fun findNameArgument(entry: KtAnnotationEntry): ValueArgument? {
        val args = entry.valueArguments
        return args.firstOrNull { argName(it).let { n -> n == null || n == "name" } }
    }

    fun extractDepends(entry: KtAnnotationEntry): DependsData? {
        val args = entry.valueArguments
        val name = stringValue(findNameArgument(entry)?.getArgumentExpression()) ?: return null
        val desc = stringValue(args.find { argName(it) == "desc" }?.getArgumentExpression()).orEmpty()
        val soft = args.find { argName(it) == "soft" }?.getArgumentExpression()?.text == "true"
        val inject = stringValue(args.find { argName(it) == "inject" }?.getArgumentExpression()).orEmpty()
        return DependsData(name, desc, inject, soft)
    }

    private fun extractImport(entry: KtAnnotationEntry): ImportData? {
        val args = entry.valueArguments
        val value = stringValue(
            args.firstOrNull { argName(it).let { n -> n == null || n == "value" } }?.getArgumentExpression()
        ) ?: return null

        val type = activeImportFlags(entry).firstOrNull() ?: inferType(value)
        // env 是修饰符，不参与类型推断
        val env = stringValue(args.find { argName(it) == "env" }?.getArgumentExpression()).orEmpty()
        return ImportData(type, value, env)
    }

    /** Import type selectors that are explicitly set to `true` on this annotation. */
    fun activeImportFlags(entry: KtAnnotationEntry): List<ImportData.Type> =
        IMPORT_FLAGS.filter { (flagName, _) -> hasFlag(entry, flagName) }.map { it.second }

    private fun hasFlag(entry: KtAnnotationEntry, flagName: String): Boolean =
        entry.valueArguments.any {
            argName(it) == flagName && it.getArgumentExpression()?.text == "true"
        }

    private fun extractImplement(entry: KtAnnotationEntry): String? {
        val expr = entry.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: return null
        return expr.removeSuffix("::class").takeIf { it.isNotEmpty() }
    }

    private fun argName(arg: ValueArgument) = arg.getArgumentName()?.asName?.asString()

    /**
     * Unquoted value of a plain string literal, or null for interpolated templates / non-string expressions.
     * Handles escape sequences (`"a\\nb"`) and raw strings (`"""abc"""`); callers skip what they cannot read
     * instead of guessing from raw text.
     */
    fun stringValue(expr: KtExpression?): String? {
        val template = expr as? KtStringTemplateExpression ?: return null
        if (template.hasInterpolation()) return null
        val sb = StringBuilder()
        for (entry in template.entries) {
            when (entry) {
                is KtLiteralStringTemplateEntry -> sb.append(entry.text)
                is KtEscapeStringTemplateEntry -> sb.append(entry.unescapedValue)
                else -> return null
            }
        }
        return sb.toString()
    }

    private val IMPORT_FLAGS = ImportData.Type.entries.map { type ->
        type.name.replaceFirstChar { it.lowercase() } to type
    }

    private fun inferType(value: String): ImportData.Type = when {
        value.startsWith("http://") || value.startsWith("https://") -> ImportData.Type.MavenRepository
        value.count { it == ':' } >= 2 -> ImportData.Type.MavenDepends
        value.contains(".*") -> ImportData.Type.DefaultImport
        value.startsWith("-") -> ImportData.Type.CompileArg
        else -> ImportData.Type.LibraryByClass
    }
}
