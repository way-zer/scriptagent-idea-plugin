ScriptAgent，简称SA，是一个基于kotlin scripting/kts的模块化框架。每个kts是最小的功能模块，具有独立的环境和生命周期。

这是ScriptAgent的IDEA插件，提高SA的开发体验。

IDE中SA的项目结构：在scripts文件夹下，有若干文件夹，每个文件夹代表一个模块(一系列脚本)，文件夹里面通常有module.kts。scripts顶层一般没有kts。

IDE插件功能：
- 能够注册SA到Scripting中，可以无需Gradle进行开发。
- 能够索引项目，从PSI/Ka中提取注解，构造元数据，供框架配置层使用，消除.metadata的依赖。
- @file:Depends("...") 辅助功能：自动补全，跳转，检查。
- 实现 @Export 和 inject 功能，能够在IDE中构造Stub，确保语法检查功能正确且易用
- 能够和Maven依赖解析配合，下载并导入脚本library。不能在Script Configuration中阻塞下载，可能需要增加按钮，主动下载并刷新Configuration。
- 能够正确配置companionSources的kt模块情况，确保功能和运行时行为对齐。

IDE中，Stub的类名为 scripts.math.TestUtil 形式，对应math/testUtil脚本，Stub的内容kts中标注@Export的成员声明。

testProject中为本插件的测试项目，包含一个极简的SA项目，但是可以用于测试IDE插件的各项功能。

协作说明：
- 我可能会在中途review和修改代码，尽量通知你。
- 我一般不会去修改注释，不一致你负责维护
- 我review没啥问题时，会通知你收尾，你进行最终检查，按提示操作。