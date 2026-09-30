package com.stratum.importer.flame

import com.stratum.importer.common.MemoryImportSource

/** A small Flame RPG laid out the way `flutter create` and the Flame templates lay one out. */
object FlameFixtures {

    val pubspec = """
        name: forest_quest
        description: "A tiny Flame RPG."
        version: 0.3.0+7

        environment:
          sdk: ">=3.0.0 <4.0.0"

        dependencies:
          flutter:
            sdk: flutter
          flame: ^1.18.0
          flame_tiled: ^1.20.0

        flutter:
          assets:
            - assets/images/
            - assets/tiles/
    """.trimIndent()

    /** The Flame docs' pattern: one fromFrameData per state, images from the cache. */
    val playerDart = """
        class Player extends SpriteAnimationGroupComponent<PlayerState> with HasGameRef<ForestQuest> {
          @override
          Future<void> onLoad() async {
            final idle = SpriteAnimation.fromFrameData(
              game.images.fromCache('player/idle.png'),
              SpriteAnimationData.sequenced(amount: 4, stepTime: 0.15, textureSize: Vector2(32, 48)),
            );
            animations = {
              PlayerState.idle: idle,
              PlayerState.running: await game.loadSpriteAnimation(
                'player/run.png',
                SpriteAnimationData.sequenced(amount: 6, stepTime: 0.1, textureSize: Vector2.all(32), amountPerRow: 3),
              ),
              PlayerState.hit: await game.loadSpriteAnimation(
                'player/run.png',
                SpriteAnimationData.sequenced(amount: 2, stepTime: speed, textureSize: Vector2.all(32)),
              ),
            };
          }
        }
    """.trimIndent()

    /** The SpriteSheet pattern: one image, a row per state. */
    val slimeDart = """
        class Slime extends SpriteAnimationComponent {
          late final SpriteAnimation walk;
          late final SpriteAnimation die;

          @override
          Future<void> onLoad() async {
            final sheet = SpriteSheet(image: await Flame.images.load('slime.png'), srcSize: Vector2(24, 16));
            walk = sheet.createAnimation(row: 0, stepTime: 0.2, to: 5);
            die = sheet.createAnimation(row: 1, stepTime: 0.1, from: 1, to: 4);
          }
        }
    """.trimIndent()

    val bossAseprite = """
        {"frames": [
            {"filename": "boss 0", "frame": {"x": 0,  "y": 0, "w": 64, "h": 64}, "duration": 100},
            {"filename": "boss 1", "frame": {"x": 64, "y": 0, "w": 64, "h": 64}, "duration": 300},
            {"filename": "boss 2", "frame": {"x": 0,  "y": 64, "w": 64, "h": 64}, "duration": 80},
            {"filename": "boss 3", "frame": {"x": 64, "y": 64, "w": 64, "h": 64}, "duration": 80},
            {"filename": "boss 4", "frame": {"x": 128, "y": 64, "w": 64, "h": 64}, "duration": 80}
          ],
          "meta": {"app": "https://www.aseprite.org/", "image": "boss.png",
            "frameTags": [
              {"name": "Idle", "from": 0, "to": 1, "direction": "forward"},
              {"name": "Attack", "from": 2, "to": 4, "direction": "pingpong"},
              {"name": "Taunt", "from": 0, "to": 0, "direction": "forward"}
            ]}}
    """.trimIndent()

    val level = """
        {"type": "map", "orientation": "orthogonal", "width": 3, "height": 3, "tilewidth": 16, "tileheight": 16,
         "tilesets": [{"firstgid": 1, "name": "forest", "tilewidth": 16, "tileheight": 16, "tilecount": 2, "columns": 2,
                       "image": "../images/forest.png"}],
         "layers": [
           {"type": "tilelayer", "name": "ground", "width": 3, "height": 3, "data": [1,1,1,1,1,1,1,1,1]},
           {"type": "objectgroup", "name": "spawns", "objects": [
             {"id": 1, "name": "Player", "x": 24, "y": 24, "width": 0, "height": 0, "point": true}]}
         ]}
    """.trimIndent()

    fun project(withLevel: Boolean = true) = MemoryImportSource.ofText(
        "forest_quest-main",
        buildMap {
            put("pubspec.yaml", pubspec)
            put("lib/components/player.dart", playerDart)
            put("lib/components/slime.dart", slimeDart)
            put("assets/images/player/idle.png", "")
            put("assets/images/player/run.png", "")
            put("assets/images/slime.png", "")
            put("assets/images/boss.png", "")
            put("assets/images/boss.json", bossAseprite)
            put("assets/images/forest.png", "")
            if (withLevel) put("assets/tiles/level1.tmj", level)
        },
    )
}
