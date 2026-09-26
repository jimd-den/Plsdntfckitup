plugins { id("stratum.jvm") }

dependencies {
  api(project(":core:domain"))
  api(project(":engine:settlement"))
  api(project(":engine:crowd"))
}
