package com.stratum.engine.world

/**
 * The noise the terrain is made from, which now lives with the rest of world
 * generation in `:engine:worldgen`. Kept under its old names so the layered
 * generator and anything written against it read exactly as before.
 */
typealias ValueNoise = com.stratum.engine.worldgen.ValueNoise

typealias PositionalRandom = com.stratum.engine.worldgen.PositionalRandom
