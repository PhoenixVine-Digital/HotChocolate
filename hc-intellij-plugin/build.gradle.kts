plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = (findProperty("group") as String?) ?: "hc"
version = (findProperty("version") as String?) ?: "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2023.3.6")
        instrumentationTools()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // Needed for the Debug configuration: HotChocolate compiles to real JVM bytecode and runs
        // under a real `java` process, so debugging it IS a real Java/JDWP debugging session --
        // `RemoteConnection`/`RemoteState`/`PositionManager` (this plugin's own hook for mapping a
        // JVM class+line back to `.hotc` source) all live in the bundled Java plugin, not the base
        // platform, the same dependency Kotlin/Groovy/Scala's own plugins take for the identical
        // reason (reusing the platform's already-correct JVM debugger rather than writing a raw
        // JDWP client from scratch).
        bundledPlugin("com.intellij.java")
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        id.set("com.hotchocolate.intellij")
        name.set("HotChocolate")
        version.set(project.version.toString())
        description.set("Syntax highlighting, formatting, and quick documentation for the HotChocolate (.hotc) language.")
        vendor {
            name.set("HotChocolate")
        }
        ideaVersion {
            sinceBuild.set("233")
            // Explicit EMPTY string, not just an omitted call -- the Gradle plugin's own
            // `patchPluginXml` task defaults `untilBuild` to "<sinceBuild major>.*" (233.*) when
            // this is left unset entirely, which is what caused BOTH real install failures so
            // far: first a stale hardcoded "252.*" rejecting a 253 IDE, then this same
            // auto-defaulted "233.*" rejecting the exact same 253 IDE from the other direction.
            // An explicit empty string is what actually tells the platform "no upper bound" --
            // nothing this plugin does (lexer-only highlighting/formatting, a hand-rolled
            // `PsiBuilder` parser, text-scanning tooltips/hints) leans on internal platform API
            // shapes that tend to break across releases the way real inspection/intention APIs
            // sometimes do, so there's no principled upper bound to set at all.
            untilBuild.set("")
        }
    }
}

kotlin {
    jvmToolchain(17)
}
