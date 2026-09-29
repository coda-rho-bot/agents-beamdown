pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Angus Software Theming (com.angussoftware.theming:theming-compose) is
        // published to the Forgejo Maven Registry under the angus-bot namespace
        // (rhomancer-namespace copies went unreadable when repos flipped private
        // on Aug 20 2026 — packages inherit repo visibility).
        maven {
            url = uri("https://git.angussoftware.dev/api/packages/angus-bot/maven")
            credentials {
                username = "angus-bot"
                password = providers.gradleProperty("forgejo.token").orNull
                    ?: System.getenv("FORGEJO_TOKEN")
            }
        }
    }
}
rootProject.name = "letta-environment-android"
include(":app")
