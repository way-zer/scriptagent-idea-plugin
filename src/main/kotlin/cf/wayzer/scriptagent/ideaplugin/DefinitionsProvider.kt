package cf.wayzer.scriptagent.ideaplugin

import cf.wayzer.scriptAgent.define.SAScript
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionsSource
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration


class SAScriptDefinitionsSource : ScriptDefinitionsSource {
    override val definitions: Sequence<ScriptDefinition> = sequenceOf(
        ScriptDefinition.FromTemplate(
            baseHostConfiguration = defaultJvmScriptingHostConfiguration,
            template = SAScript::class,
            contextClass = SAScript::class,
            defaultCompilerOptions = emptyList()
        ).apply { order = Int.MIN_VALUE },
    )
}