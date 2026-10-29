dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // 与主构建共享 version catalog（单一真源）
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
