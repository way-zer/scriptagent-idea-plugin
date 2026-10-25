# ScriptAgent IDE Plugin

IntelliJ IDEA support for ScriptAgent Kotlin script projects. The core library is a private repo
and is consumed as a Maven artifact (cf.wayzer:ScriptAgent).

## Layout

- src/main/kotlin: plugin sources
- IMPLEMENTATION.md / SPEC.md: design notes
- testProject: sample script project for manual testing

For local development a sibling checkout of the core repo (../ScriptAgent) is picked up automatically
through the Gradle composite build, so no core publishing is needed.

## Build

    gradle :buildPlugin                       # uses the local installed IDE (fast)
    gradle :buildPlugin -PuseLocalIde=false   # downloads IDEA 2026.2 (reproducible)

Output: build/distributions/scriptagent-idea-plugin-<version>.zip

Run a sandbox IDE:

    gradle :runIde

## Core version

build.gradle.kts pins saVersion (default 2.3.4); override with -PsaVersion=<version>.
Artifacts come from saRepository(): aliyun inside China, Cloudflare mirror elsewhere.

## Publish alpha to JetBrains Marketplace

1. Create the plugin on Marketplace (id cf.wayzer.scriptAgent.ideaPlugin), upload the first
   scriptagent-idea-plugin-*.zip manually and pick the alpha channel.
2. Create an authentication token and publish later versions:

    set PUBLISH_TOKEN=...
    gradle :buildPlugin :publishPlugin -PpluginVersion=0.1.0-alpha.2 -PuseLocalIde=false -PsaVersion=2.3.4

publishing { channels = ["alpha"] } is already configured. Users opt in via
https://plugins.jetbrains.com/plugins/alpha/<numeric-plugin-id> or install the zip from disk.

## CI

| variable | purpose |
|---|---|
| SA_MAVEN_USER / SA_MAVEN_PASS | private maven credentials |
| PUBLISH_TOKEN | Marketplace upload token |

CI runs with CI=true (disables the composite local core checkout) and must pass -PsaVersion.

## License

Plugin sources are released under the MIT License (see LICENSE).
The distribution bundles third-party libraries; their licenses are listed in THIRD-PARTY-NOTICES.md.
The ScriptAgent core library is a separate, private component and is bundled with permission.
