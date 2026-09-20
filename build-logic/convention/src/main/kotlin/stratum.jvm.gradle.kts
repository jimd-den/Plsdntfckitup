/**
 * Convention for pure-Kotlin modules that must never see Android.
 *
 * Applying only the Kotlin JVM plugin is the boundary enforcement: a file in one
 * of these modules cannot import `android.*` or `androidx.*` because those
 * classes are not on the compile classpath. The rule is held by the compiler,
 * not by convention or review.
 */
plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
  }
}

dependencies {
  add("testImplementation", kotlin("test"))
  add("testImplementation", libs.findLibrary("junit").get())
  add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
}

tasks.withType<Test>().configureEach {
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

/**
 * Per-module boundary alarm.
 *
 * The module graph already makes an Android import impossible here, so this
 * catches the other direction: someone adding an Android artifact or plugin to a
 * module that is meant to stay pure. Registered after evaluation so the module's
 * own dependency block has been read, and it captures plain strings so the
 * configuration cache can serialize the task.
 */
afterEvaluate {
  val androidPlugins = plugins
    .filter { it.javaClass.name.startsWith("com.android.") }
    .map { "applies Android plugin ${it.javaClass.name}" }

  val androidDependencies = configurations
    .filter { it.name.endsWith("Implementation") || it.name == "implementation" || it.name == "api" }
    .flatMap { configuration ->
      configuration.dependencies
        .filter { dependency ->
          val group = dependency.group.orEmpty()
          group.startsWith("androidx.") || group.startsWith("com.android")
        }
        .map { "depends on Android artifact ${it.group}:${it.name}" }
    }

  val violations = (androidPlugins + androidDependencies).distinct()
  val modulePath = path

  tasks.register("architectureCheck") {
    group = "verification"
    description = "Asserts that $modulePath stays free of Android."
    doLast {
      if (violations.isNotEmpty()) {
        throw GradleException(
          "Clean architecture boundary violated in $modulePath:\n" +
            violations.joinToString("\n") { "  - $it" },
        )
      }
      logger.lifecycle("$modulePath is Android-free.")
    }
  }
}
