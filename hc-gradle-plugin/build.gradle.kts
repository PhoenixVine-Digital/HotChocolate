plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "1.9.24"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("hotChocolate") {
            id = "hc"
            implementationClass = "hc.gradle.HotChocolatePlugin"
        }
    }
}

kotlin {
    jvmToolchain(17)
}
