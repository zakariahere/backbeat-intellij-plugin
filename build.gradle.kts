import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

fun prop(name: String) = providers.gradleProperty(name)

group = prop("pluginGroup").get()
version = prop("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Stay on the language level of the Kotlin runtime bundled with the oldest supported IDE.
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
        // Use real JVM default methods for interface defaults instead of generating a bridge override
        // for every one of them; the bridges would pin us to platform methods we never meant to touch.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localIde = prop("localIdePath").orNull?.takeIf { it.isNotBlank() }
        if (localIde != null) local(localIde) else intellijIdea(prop("platformVersion"))

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = prop("pluginName")
        version = prop("pluginVersion")
        ideaVersion {
            sinceBuild = prop("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // Only needed for releasing; all values come from the environment so nothing secret lives in the repo.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            // -PverifyIdePath=<install dir> checks against an IDE you already have instead of downloading several.
            val installed = prop("verifyIdePath").orNull?.takeIf { it.isNotBlank() }
            if (installed != null) local(installed) else recommended()
        }
    }
}
