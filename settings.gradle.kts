pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Sonatype snapshots for libxposed API 102 (pre-release channel)
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots") {
            mavenContent {
                includeGroup("io.github.libxposed")
            }
        }
    }
}

rootProject.name = "OptIcon"
include(":app")
