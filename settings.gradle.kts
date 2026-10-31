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
                // FORGEJO_MAVEN_TOKEN: dedicated read:package token (CI Maven
                // resolution). FORGEJO_TOKEN fallback retained for local dev —
                // in CI it carries read:repository only (clone/rootfs/release),
                // which 401s on private package GETs (reqPackageAccess).
                // Failure signature: Maven 401 with a token PRESENT = scope
                // mismatch — check which env var won this chain, not whether
                // auth works.
                password = providers.gradleProperty("forgejo.token").orNull
                    ?: System.getenv("FORGEJO_MAVEN_TOKEN")
                    ?: System.getenv("FORGEJO_TOKEN")
            }
        }
    }
}
rootProject.name = "letta-environment-android"
include(":app")
