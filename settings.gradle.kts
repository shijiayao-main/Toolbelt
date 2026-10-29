rootProject.name = "Toolbelt"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":composeApp")
include(":core")
include(":adb-core")
include(":device-core")
include(":ui")
include(":log-tools")
include(":feature-timestamp")
include(":feature-logkit")
include(":feature-shelltools")
include(":feature-logcat")
include(":feature-screencast")
include(":feature-larkbridge")
include(":feature-logworkbench")
include(":feature-ai")
include(":feature-archive")
include(":feature-voice")
include(":feature-mcp")
include(":feature-quest")
include(":lark-gateway")
include(":lark-bridge")
include(":lark-ws")
include(":test-fixtures")
include(":test-support")
