package com.stratum.importer.tiled

import com.stratum.importer.common.MemoryImportSource
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/** Small Tiled projects, written the way the Tiled editor saves them. */
object TiledFixtures {

    /**
     * A 4x3 orthogonal JSON map: grass floor, a wall along the top row, a
     * flower decoration, a collision rectangle and a player spawn.
     */
    val village = """
        {
          "type": "map", "orientation": "orthogonal", "infinite": false,
          "width": 4, "height": 3, "tilewidth": 16, "tileheight": 16,
          "properties": [{"name": "name", "type": "string", "value": "Oak Village"}],
          "tilesets": [{
            "firstgid": 1, "name": "Overworld", "tilewidth": 16, "tileheight": 16,
            "tilecount": 4, "columns": 2, "image": "../images/overworld.png",
            "tiles": [
              {"id": 0, "properties": [{"name": "color", "type": "color", "value": "#ff3a7d44"}]},
              {"id": 3, "type": "Flower"}
            ]
          }],
          "layers": [
            {"type": "tilelayer", "name": "Ground", "width": 4, "height": 3, "visible": true,
             "data": [1,1,1,1, 1,1,1,1, 1,1,1,1]},
            {"type": "tilelayer", "name": "Walls", "width": 4, "height": 3, "visible": true,
             "data": [2,2,2,2, 0,0,0,0, 0,0,0,0]},
            {"type": "tilelayer", "name": "Decor", "width": 4, "height": 3, "visible": true,
             "data": [0,0,0,0, 0,4,0,0, 0,0,0,0]},
            {"type": "tilelayer", "name": "Roof", "width": 4, "height": 3, "visible": true,
             "data": [0,0,0,0, 0,0,0,0, 3,0,0,0]},
            {"type": "objectgroup", "name": "Collisions", "visible": true, "objects": [
              {"id": 1, "name": "rock", "x": 48, "y": 32, "width": 16, "height": 16}
            ]},
            {"type": "objectgroup", "name": "Spawns", "visible": true, "objects": [
              {"id": 2, "name": "player", "type": "", "x": 16, "y": 16, "width": 0, "height": 0, "point": true},
              {"id": 3, "name": "slime", "class": "enemy_spawn", "x": 32, "y": 32, "width": 0, "height": 0, "point": true}
            ]},
            {"type": "imagelayer", "name": "Sky", "visible": true}
          ]
        }
    """.trimIndent()

    /** The same tiles in XML, with an external tileset and zlib-compressed data. */
    fun dungeonTmx(compression: String = "zlib"): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <map version="1.10" orientation="orthogonal" width="3" height="2" tilewidth="32" tileheight="32" infinite="0">
          <tileset firstgid="1" source="../tilesets/dungeon.tsx"/>
          <layer id="1" name="Floor" width="3" height="2">
            <data encoding="base64" compression="$compression">${base64(intArrayOf(1, 1, 1, 1, 2, 1), compression)}</data>
          </layer>
          <objectgroup id="2" name="Objects">
            <object id="1" name="start" x="80" y="16"><point/></object>
          </objectgroup>
        </map>
    """.trimIndent()

    val dungeonTileset = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tileset version="1.10" name="Dungeon" tilewidth="32" tileheight="32" spacing="2" margin="1" tilecount="4" columns="2">
          <image source="dungeon.png" width="67" height="67"/>
          <tile id="1"><properties><property name="solid" type="bool" value="true"/></properties></tile>
        </tileset>
    """.trimIndent()

    fun villageProject() = MemoryImportSource.ofText("Oak", mapOf("maps/village.tmj" to village))

    fun dungeonProject(compression: String = "zlib") = MemoryImportSource.ofText(
        "Crypt",
        mapOf("maps/dungeon.tmx" to dungeonTmx(compression), "tilesets/dungeon.tsx" to dungeonTileset),
    )

    fun base64(gids: IntArray, compression: String): String {
        val raw = ByteArrayOutputStream().apply {
            gids.forEach { gid -> (0 until 4).forEach { shift -> write((gid shr (shift * 8)) and 0xFF) } }
        }.toByteArray()
        val packed = ByteArrayOutputStream().also { out ->
            when (compression) {
                "zlib" -> DeflaterOutputStream(out).use { it.write(raw) }
                "gzip" -> GZIPOutputStream(out).use { it.write(raw) }
                else -> out.write(raw)
            }
        }.toByteArray()
        return Base64.getEncoder().encodeToString(packed)
    }
}
