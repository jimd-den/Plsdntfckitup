plugins { id("stratum.jvm") }

dependencies {
  api(project(":core:domain"))
  api(project(":engine:world"))
}
