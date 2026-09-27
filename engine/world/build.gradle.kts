plugins { id("stratum.jvm") }

dependencies {
  api(project(":core:domain"))
  api(project(":engine:settlement"))
  api(project(":engine:worldgen"))
  api(project(":engine:crowd"))
}
