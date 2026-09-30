package com.stratum.core.data.micro

import com.stratum.core.domain.micro.MicroModel
import com.stratum.core.domain.micro.MicroModelCodec
import com.stratum.core.domain.micro.MicroStamp
import kotlinx.serialization.Serializable

/** A microvoxel model as JSON: its cells run-length coded, see [MicroModelCodec]. */
@Serializable
data class MicroModelSchema(
    val id: String,
    val name: String,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val palette: List<String>,
    val cells: String,
    val source: String = "studio",
    val tags: List<String> = emptyList(),
) {
    fun toDomain() = MicroModel(id, name, sizeX, sizeY, sizeZ, palette, MicroModelCodec.decode(cells, sizeX * sizeY * sizeZ), source, tags)

    companion object {
        fun of(m: MicroModel) = MicroModelSchema(m.id, m.name, m.sizeX, m.sizeY, m.sizeZ, m.palette, MicroModelCodec.encode(m.cells), m.source, m.tags)
    }
}

@Serializable
data class MicroStampSchema(
    val model: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val turns: Int = 0,
    val mirror: Boolean = false,
    val carve: Boolean = false,
) {
    fun toDomain() = MicroStamp(model, x, y, z, turns, mirror, carve)

    companion object {
        fun of(s: MicroStamp) = MicroStampSchema(s.modelId, s.x, s.y, s.z, s.turns, s.mirror, s.carve)
    }
}
