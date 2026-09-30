plugins { id("stratum.jvm") }

dependencies {
  api(project(":core:domain"))
  // Draws quarter-block detail near the camera in worlds generated from microvoxels.
  api(project(":engine:microvoxel"))
}

dependencies {
  // A real microvoxel world on the built-in pack, to check detail meshing end to end.
  testImplementation(project(":engine:world"))
  testImplementation(project(":content:igbo"))
}
