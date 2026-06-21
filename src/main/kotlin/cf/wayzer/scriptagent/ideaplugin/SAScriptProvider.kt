package cf.wayzer.scriptagent.ideaplugin

import cf.wayzer.scriptAgent.impl.config.IDEMode
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import kotlin.reflect.KClass
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.hostConfiguration
import kotlin.script.experimental.host.ScriptDefinition
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.getScriptingClass
import kotlin.script.experimental.intellij.ScriptDefinitionsProvider
import kotlin.script.experimental.jvm.jdkHome
import kotlin.script.experimental.jvm.jvm

class SAScriptProvider(private val project: Project) : ScriptDefinitionsProvider {
    override val id: String = "ScriptAgent"

    override fun getDefinitionClasses(): Iterable<String> = emptyList()
    override fun getDefinitionsClassPath(): Iterable<File> = emptyList()
    override fun useDiscovery(): Boolean = false

    object LocalGetScriptingClass : kotlin.script.experimental.jvm.GetScriptingClassByClassLoader {
        override fun invoke(
            classType: KotlinType,
            contextClassLoader: ClassLoader?,
            hostConfiguration: ScriptingHostConfiguration
        ): KClass<*> = resolve(classType)

        override fun invoke(
            classType: KotlinType,
            contextClass: KClass<*>,
            hostConfiguration: ScriptingHostConfiguration
        ): KClass<*> = resolve(classType)

        fun resolve(classType: KotlinType): KClass<*> {
            return classType.fromClass ?: Class.forName(classType.typeName).kotlin
        }
    }

    val hostConfiguration = ScriptingHostConfiguration {
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val jdkHome = sdk?.homePath?.let { File(it) }

        getScriptingClass(LocalGetScriptingClass)
        jvm {
            if (jdkHome != null) {
                jdkHome(jdkHome)
            }
        }
    }

    override fun provideDefinitions(
        baseHostConfiguration: ScriptingHostConfiguration,
        loadedScriptDefinitions: List<ScriptDefinition>
    ): Iterable<ScriptDefinition> {
        return listOf(
            ScriptDefinition(
                compilationConfiguration = ScriptCompilationConfiguration(IDEMode) {
                    hostConfiguration(this@SAScriptProvider.hostConfiguration)
                },
                evaluationConfiguration = ScriptEvaluationConfiguration()
            )
        ) + loadedScriptDefinitions
    }
}
