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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // XposedBridge 旧版 API（LSPosed 通过 legacy bridge 兼容，API 93+）
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "SubwayIsland"
include(":app")
