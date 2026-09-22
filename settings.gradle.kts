pluginManagement {
  includeBuild("build-logic")
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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "Stratum"

// ---------------------------------------------------------------------------
// Dependency rule: :app -> :feature:* -> :core:designsystem -> :core:domain
//                                     -> :core:data      -> :core:domain
//                                        :engine:render  -> :engine:world
//                                        :engine:world   -> :core:domain
//                                        :content:igbo   -> :core:domain
// Nothing ever points back inward. :core:domain and :engine:world are pure
// Kotlin and cannot reach Android at all.
// ---------------------------------------------------------------------------
include(":app")
include(":core:domain")
include(":core:data")
include(":core:designsystem")
include(":engine:world")
include(":engine:render")
include(":feature:play")
include(":feature:forge")
include(":feature:hero")
include(":content:igbo")

// Renders the world headlessly so the art direction can be reviewed and
// regression-tested without a device. Never shipped in the app.
include(":tools:artpreview")

// The original engine, moved out of :app and split along the layering it
// already had. Being ported feature by feature onto the new architecture.
include(":legacy:domain")
include(":legacy:data")
include(":feature:studio")
