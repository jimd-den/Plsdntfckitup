import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

/**
 * The fallback signing key. Its password is deliberately public: the key's only
 * job is to make continuous integration artifacts installable, and treating it
 * as a secret would imply it protects something.
 */
val CI_KEYSTORE_NAME = "ci-signing.jks"
val CI_KEYSTORE_ALIAS = "stratum-ci"
val CI_KEYSTORE_PASSWORD = "stratum-ci"

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.stratum.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.igboarpg.omagvd"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // A release build is always signed, because an unsigned APK cannot be
  // installed at all and is therefore not a deliverable.
  //
  // Two keys, in order of preference:
  //  1. A real upload key, handed in by CI through KEYSTORE_PATH and the
  //     matching password environment variables.
  //  2. ci-signing.jks, committed to this repo with a published password.
  //     It exists so every pull request produces an installable artifact, and
  //     because the key is stable, build N+1 upgrades build N in place instead
  //     of forcing an uninstall. It is the same bargain as Android's shared
  //     debug.keystore and carries the same warning: never publish with it.
  val uploadKeystore = System.getenv("KEYSTORE_PATH")?.let(::file)?.takeIf { it.isFile }
  val ciKeystore = rootProject.file(CI_KEYSTORE_NAME).takeIf { it.isFile }

  signingConfigs {
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    create("release") {
      if (uploadKeystore != null) {
        storeFile = uploadKeystore
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      } else {
        requireNotNull(ciKeystore) {
          "No signing key available: set KEYSTORE_PATH to an upload keystore, " +
            "or restore $CI_KEYSTORE_NAME in the repository root."
        }
        storeFile = ciKeystore
        storePassword = CI_KEYSTORE_PASSWORD
        keyAlias = CI_KEYSTORE_ALIAS
        keyPassword = CI_KEYSTORE_PASSWORD
      }

      // Stated rather than inherited from minSdk. v2 is what every supported
      // device verifies; v3 is what allows the signing key to be rotated later
      // without existing installs refusing the update.
      enableV1Signing = false
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  // The new architecture. :app stays thin: wiring and the Activity, no rules.
  implementation(project(":core:domain"))
  implementation(project(":core:designsystem"))
  implementation(project(":engine:world"))
  implementation(project(":content:igbo"))
  implementation(project(":feature:play"))
  implementation(project(":engine:scene"))
  implementation(project(":feature:forge"))
  implementation(project(":feature:hero"))
  implementation(project(":feature:library"))
  implementation(project(":importer:flame"))
  implementation(project(":core:data"))
  implementation(project(":feature:studio"))

  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.coil.compose)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.play.services.location)
  testImplementation(project(":content:igbo"))
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * Prints which key signed the build. Without this the only way to tell an
 * upload-signed artifact from a CI-signed one is to unzip it and read the
 * certificate, which nobody does before handing a build to a tester.
 */
tasks.register("reportSigningKey") {
  group = "verification"
  description = "Says which key release builds will be signed with."
  // Resolved to plain strings at configuration time. Reading the script's own
  // properties inside the action would capture the script object itself, which
  // the configuration cache cannot serialize.
  val summary = if (System.getenv("KEYSTORE_PATH")?.let(::file)?.isFile == true) {
    "Release signing: upload key from KEYSTORE_PATH."
  } else {
    "Release signing: $CI_KEYSTORE_NAME (test key, not for publication)."
  }
  doLast { logger.lifecycle(summary) }
}

val apkOutputDir = layout.projectDirectory.dir("../apk")
val debugApkDir = layout.buildDirectory.dir("outputs/apk/debug")
val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")

val copyApkToRoot = tasks.register<Copy>("copyApkToRoot") {
  from(debugApkDir)
  into(apkOutputDir)
  include("app-debug.apk")
}

val copyNamedApkToRoot = tasks.register<Copy>("copyNamedApkToRoot") {
  from(debugApkDir)
  into(apkOutputDir)
  include("app-debug.apk")
  rename("app-debug.apk", "ofo-and-bronze-engine.apk")
}

/**
 * Keeps a signed, installable build where this project has always kept one.
 * The debug copies above predate release signing; this is the one to hand to
 * someone who just wants to run the game.
 */
val copyReleaseApkToRoot = tasks.register<Copy>("copyReleaseApkToRoot") {
  from(releaseApkDir)
  into(apkOutputDir)
  include("app-release.apk")
  rename("app-release.apk", "stratum-release.apk")
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
  finalizedBy(copyApkToRoot, copyNamedApkToRoot)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
  finalizedBy(copyReleaseApkToRoot)
}



