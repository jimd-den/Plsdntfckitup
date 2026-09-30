plugins { id("stratum.jvm") }

dependencies {
  api(project(":core:domain"))
  api(project(":engine:settlement"))
  api(project(":engine:worldgen"))
  // The microvoxel generator, played on blocks (`stratum:microvoxel`).
  api(project(":engine:microbridge"))
  api(project(":engine:crowd"))
  // The built-in pack, to check a microvoxel world plays with real blocks.
  testImplementation(project(":content:igbo"))
}
