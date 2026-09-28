plugins { id("stratum.jvm") }

// Deliberately dependency-free: the microvoxel engine is pure Kotlin with no
// knowledge of the block world, Android or any renderer. A GL renderer
// consumes its packed quads; :tools:microvoxelpreview ray-traces it to PNGs.
