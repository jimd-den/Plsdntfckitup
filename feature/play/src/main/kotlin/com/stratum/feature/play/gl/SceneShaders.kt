package com.stratum.feature.play.gl

import com.stratum.engine.scene.ShadingModel
import com.stratum.engine.scene.Surfel
import com.stratum.engine.scene.SurfelLod
import com.stratum.engine.scene.VoxelSplat

/**
 * GLSL ES 3.00 ports of `ShadingModel` and the software rasteriser.
 *
 * Every constant and every formula here has a twin in
 * `engine/scene/.../ShadingModel.kt`; the preview images are rendered from the
 * Kotlin side, the game from this side. When one changes, the other must, or
 * the screenshots stop being evidence. The one deliberate difference is
 * `uShadowTaps`: lower quality tiers take shadow samples away here, and the
 * previews always show the full 3x3 filter the HIGH tier draws.
 */
internal object SceneShaders {

    private fun f(v: Float): String = v.toString()

    /**
     * The diorama finish's per-surface half (see `DioramaLook`): per-voxel
     * grain, bevelled voxel edges, deeper occlusion and warm/cool aerial
     * haze. Twins of `ShadingModel.voxelGrain`, `bevel`, `bevelFade` and
     * `haze`; the numbers are read from ShadingModel itself, so they cannot drift.
     */
    private val DIORAMA = """
        uniform float uGrain;
        uniform float uBevel;
        uniform float uOcclusionDepth;
        uniform bool uHaze;
        uniform float uGlowGain;
        uniform vec3 uFocus;
        uniform float uFocusDistance;
        uniform float uPixelAngle;
        const float VOXELS_PER_BLOCK = ${f(ShadingModel.VOXELS_PER_BLOCK)};
        const float INSIDE = ${f(ShadingModel.INSIDE)};
        const float GRAIN_WARMTH = ${f(ShadingModel.GRAIN_WARMTH)};
        const uint HASH_OFFSET = ${ShadingModel.HASH_OFFSET}u;
        const float BEVEL_WIDTH = ${f(ShadingModel.BEVEL_WIDTH)};
        const float BEVEL_TILT = ${f(ShadingModel.BEVEL_TILT)};
        const float BEVEL_FADE_START = ${f(ShadingModel.BEVEL_FADE_START)};
        const float BEVEL_FADE_END = ${f(ShadingModel.BEVEL_FADE_END)};
        const float AXIS_ALIGNED = ${f(ShadingModel.AXIS_ALIGNED)};
        const float OCCLUSION_SUN_SHARE = ${f(ShadingModel.OCCLUSION_SUN_SHARE)};
        const float HAZE_START = ${f(ShadingModel.HAZE_START)};
        const float HAZE_DENSITY = ${f(ShadingModel.HAZE_DENSITY)};
        const float HAZE_FALLOFF = ${f(ShadingModel.HAZE_FALLOFF)};
        const float HAZE_LOW_MIN = ${f(ShadingModel.HAZE_LOW_MIN)};
        const float HAZE_LOW_MAX = ${f(ShadingModel.HAZE_LOW_MAX)};
        const float HAZE_MAX = ${f(ShadingModel.HAZE_MAX)};
        const vec3 HAZE_WARM = vec3(${ShadingModel.HAZE_WARM.joinToString { f(it) }});
        const vec3 HAZE_COOL = vec3(${ShadingModel.HAZE_COOL.joinToString { f(it) }});

        // ShadingModel.voxelHash, in uint arithmetic: the same bits as Kotlin's wrapping Int.
        uint voxelHash(ivec3 c) {
            uvec3 u = uvec3(c) + uvec3(HASH_OFFSET);
            uint h = (u.x * 73856093u) ^ (u.y * 19349663u) ^ (u.z * 83492791u);
            h ^= h >> 13u;
            h *= 1274126177u;
            return h ^ (h >> 16u);
        }
        vec3 voxelGrain(vec3 w, vec3 n) {
            uint h = voxelHash(ivec3(floor((w - n * INSIDE) * VOXELS_PER_BLOCK)));
            float tone = 1.0 + (float(h & 65535u) / 65535.0 - 0.5) * 2.0 * uGrain;
            float warm = (float((h >> 16u) & 65535u) / 65535.0 - 0.5) * uGrain * GRAIN_WARMTH;
            return vec3(tone * (1.0 + warm), tone, tone * (1.0 - warm));
        }
        float edgeTilt(float w) {
            float f = fract(w * VOXELS_PER_BLOCK);
            if (f < BEVEL_WIDTH) return -(1.0 - f / BEVEL_WIDTH);
            if (f > 1.0 - BEVEL_WIDTH) return 1.0 - (1.0 - f) / BEVEL_WIDTH;
            return 0.0;
        }
        vec3 bevelled(vec3 n, vec3 w, float dist) {
            if (uBevel <= 0.0) return n;
            float strength = uBevel * (1.0 - smoothstep(BEVEL_FADE_START, BEVEL_FADE_END, dist * uPixelAngle * VOXELS_PER_BLOCK));
            vec3 a = abs(n);
            if (strength <= 0.0 || max(a.x, max(a.y, a.z)) < AXIS_ALIGNED) return n;
            vec3 t = vec3(a.x < 0.5 ? edgeTilt(w.x) : 0.0, a.y < 0.5 ? edgeTilt(w.y) : 0.0, a.z < 0.5 ? edgeTilt(w.z) : 0.0);
            return normalize(n + t * strength * BEVEL_TILT);
        }
        // Amount of haze in .a, its colour (before tone mapping is undone) in .rgb.
        vec4 haze(vec3 w, float dist, vec3 eye, vec3 sun, vec3 fog) {
            float beyond = max(0.0, dist - uFocusDistance * HAZE_START);
            float low = clamp(exp(-(w.z - uFocus.z) * HAZE_FALLOFF), HAZE_LOW_MIN, HAZE_LOW_MAX);
            float amount = min(HAZE_MAX, (1.0 - exp(-beyond * HAZE_DENSITY)) * low);
            vec2 v = w.xy - eye.xy;
            float towards = smoothstep(-0.6, 1.0, dot(v, sun.xy) / (max(length(sun.xy), 1e-4) * max(length(v), 1e-4)));
            return vec4(fog * mix(HAZE_COOL, HAZE_WARM, towards), amount);
        }
    """

    private const val ATTRIBUTES = """
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec3 aColor;
        layout(location = 3) in float aAo;
        layout(location = 4) in vec2 aUv;
        layout(location = 5) in float aLayer;
        layout(location = 6) in float aEmissive;
        layout(location = 7) in vec2 aVariants;
    """

    val LIT_VERTEX = """#version 300 es
        $ATTRIBUTES
        uniform mat4 uViewProj;
        uniform mat4 uShadowViewProj;
        out vec3 vWorld;
        out vec3 vNormal;
        out vec3 vColor;
        out float vAo;
        out vec2 vUv;
        flat out float vLayer;
        out float vEmissive;
        out vec4 vShadow;
        flat out vec2 vVariants;
        void main() {
            vWorld = aPos;
            vNormal = aNormal;
            vColor = aColor;
            vAo = aAo;
            vUv = aUv;
            vLayer = aLayer;
            vEmissive = aEmissive;
            vVariants = aVariants;
            vShadow = uShadowViewProj * vec4(aPos + aNormal * 0.04, 1.0);
            gl_Position = uViewProj * vec4(aPos, 1.0);
        }
    """

    val LIT_FRAGMENT = """#version 300 es
        precision highp float;
        precision highp sampler2DArray;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec3 vColor;
        in float vAo;
        in vec2 vUv;
        flat in float vLayer;
        in float vEmissive;
        in vec4 vShadow;
        flat in vec2 vVariants;
        uniform sampler2DArray uTextures;
        uniform sampler2DArray uMaps;
        uniform sampler2D uShadowMap;
        uniform float uShadowSize;
        // 0: no shadow map this tier; 1: one hard sample; 9: the 3x3 filter ShadingModel uses.
        uniform int uShadowTaps;
        uniform bool uCutout;
        // The see-through cut around the player: feet xyz, radius in w (0 = off). See ShadingModel.revealCut.
        uniform vec4 uReveal;
        uniform vec3 uEye;
        uniform vec3 uSun;
        uniform vec3 uFill;
        uniform float uFillStrength;
        uniform float uFloorDetail;
        uniform float uFloorSaturation;
        uniform vec3 uSunColor;
        uniform vec3 uSky;
        uniform vec3 uGround;
        uniform vec3 uFog;
        uniform vec3 uRim;
        uniform float uFogStart;
        uniform float uFogEnd;
        uniform float uFogFloor;
        uniform float uShadowStrength;
        uniform float uExposure;
        uniform int uLightCount;
        uniform vec3 uLightPos[8];
        uniform vec3 uLightColor[8];
        uniform float uLightRadius[8];
        out vec4 fragColor;
        $DIORAMA

        const float EMISSIVE_GAIN = 1.6;
        const float TONE_GAIN = 1.25;
        const float HEIGHT_FOG_DEPTH = 6.0;
        const float HEIGHT_FOG_MAX = 0.55;

        float dither(vec2 p) {
            int x = int(mod(p.x, 4.0));
            int y = int(mod(p.y, 4.0));
            int i = y * 4 + x;
            float m[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
            return m[i] / 16.0;
        }

        const float REVEAL_FEATHER = 0.9;
        const float REVEAL_FLOOR = 0.3;
        const float REVEAL_BODY = 1.0;
        const float REVEAL_BEHIND = 0.6;
        float revealCut(vec3 w) {
            if (uReveal.w <= 0.0 || w.z <= uReveal.z + REVEAL_FLOOR) return 0.0;
            vec3 toBody = vec3(uReveal.xy, uReveal.z + REVEAL_BODY) - uEye;
            float len = max(length(toBody), 1e-4);
            vec3 d = toBody / len;
            vec3 v = w - uEye;
            float t = dot(v, d);
            if (t >= len - REVEAL_BEHIND) return 0.0;
            float off = length(v - d * t);
            return 1.0 - smoothstep(uReveal.w - REVEAL_FEATHER, uReveal.w, off);
        }

        float sunlit(float ndl) {
            if (uShadowTaps == 0) return 1.0;
            vec3 p = vShadow.xyz / vShadow.w * 0.5 + 0.5;
            if (p.x < 0.0 || p.y < 0.0 || p.x > 1.0 || p.y > 1.0) return 1.0;
            float bias = 0.0015 + 0.004 * (1.0 - ndl);
            if (uShadowTaps == 1) return (p.z - bias <= texture(uShadowMap, p.xy).r) ? 1.0 : 0.0;
            float lit = 0.0;
            for (int oy = -1; oy <= 1; oy++) {
                for (int ox = -1; ox <= 1; ox++) {
                    float d = texture(uShadowMap, p.xy + vec2(ox, oy) / uShadowSize).r;
                    lit += (p.z - bias <= d) ? 1.0 : 0.0;
                }
            }
            return lit / 9.0;
        }

        float untone(float v) { return -log(1.0 - clamp(v, 0.0, 0.999)) / (uExposure * TONE_GAIN); }

        // Port of ShadingModel.detile / valueNoise.
        float hash21(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
        float vnoise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = p - i;
            vec2 u = f * f * (3.0 - 2.0 * f);
            float a = hash21(i);
            float b = hash21(i + vec2(1.0, 0.0));
            float c = hash21(i + vec2(0.0, 1.0));
            float d = hash21(i + vec2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }
        // Layers from 1024 up are ground maps, in their own larger array
        // (TextureLibrary.MAP_BASE). Branching on a flat layer is uniform per
        // triangle, so mip selection stays well defined.
        vec4 layerTexel(vec2 uv, float layer) {
            return layer >= 1024.0 ? texture(uMaps, vec3(uv, layer - 1024.0)) : texture(uTextures, vec3(uv, layer));
        }
        vec3 layerMean(float layer) {
            return layer >= 1024.0 ? textureLod(uMaps, vec3(0.5, 0.5, layer - 1024.0), 16.0).rgb
                : textureLod(uTextures, vec3(0.5, 0.5, layer), 16.0).rgb;
        }

        vec3 detiled(vec3 first, vec2 uv, vec3 world) {
            vec2 uv2 = mat2(0.8253356, 0.5646425, -0.5646425, 0.8253356) * uv * 0.71 + vec2(0.37, 0.19);
            vec2 p = vec2(world.x + world.z * 0.7, world.y - world.z * 0.7);
            float w = smoothstep(0.35, 0.65, vnoise(p / 6.0));
            float tint = 0.88 + 0.24 * vnoise(p / 13.0 + vec2(17.0, 5.0));
            vec3 second = layerTexel(uv2, vLayer).rgb;
            vec3 c = mix(first, second, w);
            // Port of ShadingModel.variants: sister paintings in slow patches.
            // Branching only on the flat per-face layers, so every pixel of a
            // 2x2 quad samples alike and mip selection stays well defined.
            vec2 q = world.xy / 9.0;
            if (vVariants.x >= 0.0) {
                float wa = smoothstep(0.5, 0.64, vnoise(q + vec2(41.0, 7.0)));
                c = mix(c, texture(uTextures, vec3(uv, vVariants.x)).rgb, wa);
            }
            if (vVariants.y >= 0.0) {
                float wb = smoothstep(0.5, 0.64, vnoise(q * 1.3 + vec2(-23.0, 61.0)));
                c = mix(c, texture(uTextures, vec3(uv2, vVariants.y)).rgb, wb);
            }
            return c * tint;
        }

        // Port of ShadingModel.calm. The smallest mip level is the painting's
        // average colour, which is what contrast is pulled towards.
        vec3 calm(vec3 c, vec3 n) {
            vec3 mean = layerMean(vLayer);
            bool floorFacing = n.z > 0.7;
            float detail = floorFacing ? uFloorDetail : min(1.0, uFloorDetail + 0.25);
            float saturation = floorFacing ? uFloorSaturation : min(1.0, uFloorSaturation + 0.25);
            c = mean + (c - mean) * detail;
            float luma = dot(c, vec3(0.299, 0.587, 0.114));
            return vec3(luma) + (c - vec3(luma)) * saturation;
        }

        void main() {
            if (uCutout && vAo < 0.999 && vAo <= dither(gl_FragCoord.xy)) discard;
            float cut = revealCut(vWorld);
            if (cut > 0.0 && cut >= dither(gl_FragCoord.xy)) discard;
            vec3 albedo = vColor;
            if (vLayer >= 0.0) {
                vec2 uv = uCutout ? clamp(vUv, 0.0, 1.0) : vUv;
                vec4 texel = layerTexel(uv, vLayer);
                if (uCutout && texel.a < 0.5) discard;
                albedo *= uCutout ? texel.rgb : calm(detiled(texel.rgb, uv, vWorld), normalize(vNormal));
            }
            vec3 n = normalize(vNormal);
            vec3 toEye = uEye - vWorld;
            float dist = length(toEye);
            toEye /= dist;
            float ao = uCutout ? 1.0 : vAo;
            // Flat-coloured opaque faces are the microvoxels: each tiny cube its own tone, its edges rounded.
            if (!uCutout && vLayer > -1.5 && vLayer < -0.5) {
                if (uGrain > 0.0) albedo *= voxelGrain(vWorld, n);
                n = bevelled(n, vWorld, dist);
            }
            // Deeper occlusion (ShadingModel.shade): the sky term takes a power of it, direct sun a share.
            float od = uCutout ? 0.0 : uOcclusionDepth;
            float skyAo = od > 0.0 ? pow(ao, 1.0 + od) : ao;
            float sunAo = 1.0 - od * OCCLUSION_SUN_SHARE * (1.0 - ao);

            float hemi = n.z * 0.5 + 0.5;
            vec3 light = mix(uGround, uSky, hemi) * skyAo;
            float ndl = max(0.0, dot(n, uSun));
            float lit = ndl > 0.0 ? sunlit(ndl) : 0.0;
            light += uSunColor * ndl * (1.0 - uShadowStrength * (1.0 - lit)) * sunAo;
            // Fill light from the camera's side; see ShadingModel.
            light += uSunColor * max(0.0, dot(n, uFill)) * uFillStrength;
            for (int i = 0; i < 8; i++) {
                if (i >= uLightCount) break;
                vec3 d = uLightPos[i] - vWorld;
                float len = length(d);
                if (len >= uLightRadius[i]) continue;
                float facing = max(0.0, dot(n, d / max(len, 1e-4))) * 0.7 + 0.3;
                float fall = 1.0 - len / uLightRadius[i];
                light += uLightColor[i] * fall * fall * facing;
            }
            vec3 color = light * albedo + albedo * vEmissive * (1.0 + uGlowGain) * EMISSIVE_GAIN;
            if (vLayer < -1.5) {
                float edge = 1.0 - max(0.0, dot(n, toEye));
                color += uRim * edge * edge;
            }
            if (uHaze) {
                vec4 hz = haze(vWorld, dist, uEye, uSun, uFog);
                color = mix(color, vec3(untone(hz.r), untone(hz.g), untone(hz.b)), hz.a);
            }
            float fog = smoothstep(uFogStart, uFogEnd, dist);
            if (vWorld.z < uFogFloor) fog = max(fog, clamp((uFogFloor - vWorld.z) / HEIGHT_FOG_DEPTH, 0.0, 1.0) * HEIGHT_FOG_MAX);
            vec3 fogPre = vec3(untone(uFog.r), untone(uFog.g), untone(uFog.b));
            fragColor = vec4(mix(color, fogPre, fog), 1.0);
        }
    """

    /**
     * Surfels as point sprites: twelve bytes a point ([com.stratum.engine.scene.Surfel]),
     * decoded, faded by [com.stratum.engine.scene.SurfelLod], and lit once
     * here in the vertex shader, so a surfel costs one vertex's lighting
     * however many pixels it covers. Twin of `SceneRasterizer.surfels`.
     */
    val SURFEL_VERTEX = """#version 300 es
        precision highp float;
        layout(location = 0) in uvec3 aSurfel;
        uniform mat4 uViewProj;
        uniform mat4 uShadowViewProj;
        uniform sampler2D uShadowMap;
        uniform int uShadowTaps;
        uniform vec2 uOrigin;
        uniform float uKeep;
        uniform float uSurfelRadius;
        uniform float uPixelsPerUnit;
        uniform float uMinPixels;
        uniform vec2 uViewport;
        uniform vec4 uReveal;
        uniform vec3 uEye;
        uniform vec3 uSun;
        uniform vec3 uFill;
        uniform float uFillStrength;
        uniform vec3 uSunColor;
        uniform vec3 uSky;
        uniform vec3 uGround;
        uniform vec3 uFog;
        uniform float uFogStart;
        uniform float uFogEnd;
        uniform float uFogFloor;
        uniform float uShadowStrength;
        uniform float uExposure;
        uniform int uLightCount;
        uniform vec3 uLightPos[8];
        uniform vec3 uLightColor[8];
        uniform float uLightRadius[8];
        $DIORAMA
        flat out vec3 vColor;
        flat out vec3 vAxis;

        const float POSITION_STEP = ${f(Surfel.POSITION_STEP)};
        const float XY_OFFSET = ${f(Surfel.XY_OFFSET)};
        const float MAX_RADIUS = ${f(Surfel.MAX_RADIUS)};
        const float NEAR_SHARE = ${f(SurfelLod.NEAR_SHARE)};
        const float FADE = ${f(SurfelLod.FADE)};
        const float PULL = ${f(ShadingModel.SURFEL_PULL)};
        const float MIN_SQUASH = ${f(ShadingModel.SURFEL_MIN_SQUASH)};
        const float TONE_GAIN = ${f(ShadingModel.TONE_GAIN)};
        const float HEIGHT_FOG_DEPTH = ${f(ShadingModel.HEIGHT_FOG_DEPTH)};
        const float HEIGHT_FOG_MAX = ${f(ShadingModel.HEIGHT_FOG_MAX)};
        const float REVEAL_FEATHER = 0.9;
        const float REVEAL_FLOOR = 0.3;
        const float REVEAL_BODY = 1.0;
        const float REVEAL_BEHIND = 0.6;

        float untone(float v) { return -log(1.0 - clamp(v, 0.0, 0.999)) / (uExposure * TONE_GAIN); }
        float revealCut(vec3 w) {
            if (uReveal.w <= 0.0 || w.z <= uReveal.z + REVEAL_FLOOR) return 0.0;
            vec3 toBody = vec3(uReveal.xy, uReveal.z + REVEAL_BODY) - uEye;
            float len = max(length(toBody), 1e-4);
            vec3 d = toBody / len;
            vec3 v = w - uEye;
            float t = dot(v, d);
            if (t >= len - REVEAL_BEHIND) return 0.0;
            return 1.0 - smoothstep(uReveal.w - REVEAL_FEATHER, uReveal.w, length(v - d * t));
        }
        float sunlit(vec3 w, vec3 n, float ndl) {
            if (uShadowTaps == 0) return 1.0;
            vec4 s = uShadowViewProj * vec4(w + n * ${f(ShadingModel.SURFEL_SHADOW_OFFSET)}, 1.0);
            vec3 p = s.xyz / s.w * 0.5 + 0.5;
            if (p.x < 0.0 || p.y < 0.0 || p.x > 1.0 || p.y > 1.0) return 1.0;
            float bias = 0.0015 + 0.004 * (1.0 - ndl);
            // One tap: a surfel is a few pixels across; the 3x3 filter would be lost on it.
            return (p.z - bias <= textureLod(uShadowMap, p.xy, 0.0).r) ? 1.0 : 0.0;
        }
        void cull() { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; vColor = vec3(0.0); vAxis = vec3(1.0, 0.0, 1.0); }

        void main() {
            uvec3 a = aSurfel;
            vec3 w = vec3(
                float(a.x & 1023u) * POSITION_STEP - XY_OFFSET + uOrigin.x,
                float((a.x >> 10u) & 1023u) * POSITION_STEP - XY_OFFSET + uOrigin.y,
                float(a.x >> 20u) * POSITION_STEP);
            float rank = float(a.z >> 24u) / 255.0;
            float share = 1.0 - smoothstep(uSurfelRadius * NEAR_SHARE, uSurfelRadius, length(w.xy - uFocus.xy));
            float grow = clamp((share * uKeep - rank) / FADE, 0.0, 1.0);
            if (grow <= 0.0 || revealCut(w) > 0.5) { cull(); return; }
            float radius = float(a.y & 255u) / 255.0 * MAX_RADIUS * grow;
            vec3 albedo = vec3(float((a.y >> 24u) & 255u), float((a.y >> 16u) & 255u), float((a.y >> 8u) & 255u)) / 255.0;
            // Octahedral normal (Surfel.normal).
            vec2 e = vec2(float(a.z & 255u), float((a.z >> 8u) & 255u)) / 255.0 * 2.0 - 1.0;
            float ez = 1.0 - abs(e.x) - abs(e.y);
            if (ez < 0.0) e = (1.0 - abs(e.yx)) * vec2(e.x >= 0.0 ? 1.0 : -1.0, e.y >= 0.0 ? 1.0 : -1.0);
            vec3 n = normalize(vec3(e, ez));
            float ao = float((a.z >> 16u) & 255u) / 255.0;

            vec3 toEye = uEye - w;
            float dist = length(toEye);
            toEye /= dist;
            vec4 clip = uViewProj * vec4(w + toEye * radius * PULL, 1.0);
            if (clip.w < 0.5) { cull(); return; }
            float rp = radius * uPixelsPerUnit / clip.w;
            if (rp < uMinPixels) { cull(); return; }
            // Which way the disc is foreshortened on screen: along its normal's projection, y down as in the rasteriser.
            vec4 b0 = uViewProj * vec4(w, 1.0);
            vec4 b1 = uViewProj * vec4(w + n * radius, 1.0);
            vec2 d = (b1.xy / b1.w - b0.xy / b0.w) * uViewport * vec2(1.0, -1.0);
            float dl = length(d);
            d = dl < 1e-6 ? vec2(1.0, 0.0) : d / dl;
            vAxis = vec3(d, clamp(abs(dot(n, toEye)), MIN_SQUASH, 1.0));

            // ShadingModel.shade, once per surfel.
            float skyAo = uOcclusionDepth > 0.0 ? pow(ao, 1.0 + uOcclusionDepth) : ao;
            float sunAo = 1.0 - uOcclusionDepth * OCCLUSION_SUN_SHARE * (1.0 - ao);
            vec3 light = mix(uGround, uSky, n.z * 0.5 + 0.5) * skyAo;
            float ndl = max(0.0, dot(n, uSun));
            float lit = ndl > 0.0 ? sunlit(w, n, ndl) : 0.0;
            light += uSunColor * ndl * (1.0 - uShadowStrength * (1.0 - lit)) * sunAo;
            light += uSunColor * max(0.0, dot(n, uFill)) * uFillStrength;
            for (int i = 0; i < 8; i++) {
                if (i >= uLightCount) break;
                vec3 ld = uLightPos[i] - w;
                float len = length(ld);
                if (len >= uLightRadius[i]) continue;
                float facing = max(0.0, dot(n, ld / max(len, 1e-4))) * 0.7 + 0.3;
                float fall = 1.0 - len / uLightRadius[i];
                light += uLightColor[i] * fall * fall * facing;
            }
            vec3 color = light * albedo;
            if (uHaze) {
                vec4 hz = haze(w, dist, uEye, uSun, uFog);
                color = mix(color, vec3(untone(hz.r), untone(hz.g), untone(hz.b)), hz.a);
            }
            float fog = smoothstep(uFogStart, uFogEnd, dist);
            if (w.z < uFogFloor) fog = max(fog, clamp((uFogFloor - w.z) / HEIGHT_FOG_DEPTH, 0.0, 1.0) * HEIGHT_FOG_MAX);
            vColor = mix(color, vec3(untone(uFog.r), untone(uFog.g), untone(uFog.b)), fog);
            gl_Position = clip;
            gl_PointSize = 2.0 * rp;
        }
    """

    val SURFEL_FRAGMENT = """#version 300 es
        precision highp float;
        flat in vec3 vColor;
        flat in vec3 vAxis;
        out vec4 fragColor;
        const float DOME = ${f(ShadingModel.SURFEL_DOME)};
        void main() {
            // A disc squashed along its normal's screen direction, domed towards the rim.
            vec2 u = gl_PointCoord * 2.0 - 1.0;
            float along = dot(u, vAxis.xy) / vAxis.z;
            float across = -u.x * vAxis.y + u.y * vAxis.x;
            float q = along * along + across * across;
            if (q > 1.0) discard;
            fragColor = vec4(vColor * (1.0 + DOME * (0.5 - q)), 1.0);
        }
    """

    /**
     * Voxel splats ([com.stratum.engine.scene.SplatMode]): one point per
     * surface voxel, eight bytes each. The vertex shader decodes it, lights
     * its top and the two sides turned to the eye once each, and sizes the
     * sprite to hold the cube; the fragment shader picks the face a pixel
     * shows. Twin of `SceneRasterizer.splats`.
     *
     * [exact] false is [com.stratum.engine.scene.SplatMode.FAST]: the sprite
     * is cut to the cube's outline and the face found by three lines
     * through it ([com.stratum.engine.scene.SplatFaces]), six dot products
     * a pixel; depth is the centre's, from the rasteriser, not the shader.
     * [exact] true ray-casts the voxel's box per pixel and writes the hit's
     * depth.
     */
    fun splatVertex(exact: Boolean) = """#version 300 es
        precision highp float;
        layout(location = 0) in uvec2 aSplat;
        uniform mat4 uViewProj;
        uniform mat4 uShadowViewProj;
        uniform sampler2D uShadowMap;
        uniform int uShadowTaps;
        uniform vec2 uOrigin;
        uniform float uPerMicro;
        uniform float uPixelsPerUnit;
        uniform vec2 uViewport;
        uniform vec4 uReveal;
        uniform vec3 uEye;
        uniform vec3 uSun;
        uniform vec3 uFill;
        uniform float uFillStrength;
        uniform vec3 uSunColor;
        uniform vec3 uSky;
        uniform vec3 uGround;
        uniform vec3 uFog;
        uniform float uFogStart;
        uniform float uFogEnd;
        uniform float uFogFloor;
        uniform float uShadowStrength;
        uniform float uExposure;
        uniform int uLightCount;
        uniform vec3 uLightPos[8];
        uniform vec3 uLightColor[8];
        uniform float uLightRadius[8];
        $DIORAMA
        flat out vec3 vTop;
        flat out vec3 vSideX;
        flat out vec3 vSideY;
        ${if (exact) """
        flat out vec3 vBoxMin;
        flat out float vBoxSide;
        """ else """
        flat out vec2 vCentre;
        flat out vec3 vLine0;
        flat out vec3 vLine1;
        flat out vec3 vLine2;
        flat out vec3 vSlab0;
        flat out vec3 vSlab1;
        flat out vec3 vSlab2;
        """}

        const float SPRITE_SPREAD = ${f(VoxelSplat.SPRITE_SPREAD)};
        const float EDGE_PIXELS = ${f(com.stratum.engine.scene.SplatFaces.EDGE_PIXELS)};
        const float SHADOW_LIFT = ${f(VoxelSplat.SHADOW_LIFT)};
        const float MIN_OCCLUSION = ${f(VoxelSplat.MIN_OCCLUSION)};
        const float EMISSIVE_GAIN = ${f(ShadingModel.EMISSIVE_GAIN)};
        const float TONE_GAIN = ${f(ShadingModel.TONE_GAIN)};
        const float HEIGHT_FOG_DEPTH = ${f(ShadingModel.HEIGHT_FOG_DEPTH)};
        const float HEIGHT_FOG_MAX = ${f(ShadingModel.HEIGHT_FOG_MAX)};
        const float REVEAL_FEATHER = 0.9;
        const float REVEAL_FLOOR = 0.3;
        const float REVEAL_BODY = 1.0;
        const float REVEAL_BEHIND = 0.6;

        float untone(float v) { return -log(1.0 - clamp(v, 0.0, 0.999)) / (uExposure * TONE_GAIN); }
        float revealCut(vec3 w) {
            if (uReveal.w <= 0.0 || w.z <= uReveal.z + REVEAL_FLOOR) return 0.0;
            vec3 toBody = vec3(uReveal.xy, uReveal.z + REVEAL_BODY) - uEye;
            float len = max(length(toBody), 1e-4);
            vec3 d = toBody / len;
            vec3 v = w - uEye;
            float t = dot(v, d);
            if (t >= len - REVEAL_BEHIND) return 0.0;
            return 1.0 - smoothstep(uReveal.w - REVEAL_FEATHER, uReveal.w, length(v - d * t));
        }
        float sunlit(vec3 w) {
            if (uShadowTaps == 0) return 1.0;
            vec4 s = uShadowViewProj * vec4(w, 1.0);
            vec3 p = s.xyz / s.w * 0.5 + 0.5;
            if (p.x < 0.0 || p.y < 0.0 || p.x > 1.0 || p.y > 1.0) return 1.0;
            // One tap: a voxel is a few pixels across.
            return (p.z - 0.0015 <= textureLod(uShadowMap, p.xy, 0.0).r) ? 1.0 : 0.0;
        }
        // ShadingModel.shade, then haze and fog, for one face.
        vec3 shadeFace(vec3 n, vec3 w, vec3 albedo, float ao, float lit, float emissive, float dist) {
            float skyAo = uOcclusionDepth > 0.0 ? pow(ao, 1.0 + uOcclusionDepth) : ao;
            float sunAo = 1.0 - uOcclusionDepth * OCCLUSION_SUN_SHARE * (1.0 - ao);
            vec3 light = mix(uGround, uSky, n.z * 0.5 + 0.5) * skyAo;
            float ndl = max(0.0, dot(n, uSun));
            light += uSunColor * ndl * (1.0 - uShadowStrength * (1.0 - lit)) * sunAo;
            light += uSunColor * max(0.0, dot(n, uFill)) * uFillStrength;
            for (int i = 0; i < 8; i++) {
                if (i >= uLightCount) break;
                vec3 ld = uLightPos[i] - w;
                float len = length(ld);
                if (len >= uLightRadius[i]) continue;
                float facing = max(0.0, dot(n, ld / max(len, 1e-4))) * 0.7 + 0.3;
                float fall = 1.0 - len / uLightRadius[i];
                light += uLightColor[i] * fall * fall * facing;
            }
            vec3 color = light * albedo + albedo * emissive * EMISSIVE_GAIN;
            if (uHaze) {
                vec4 hz = haze(w, dist, uEye, uSun, uFog);
                color = mix(color, vec3(untone(hz.r), untone(hz.g), untone(hz.b)), hz.a);
            }
            float fog = smoothstep(uFogStart, uFogEnd, dist);
            if (w.z < uFogFloor) fog = max(fog, clamp((uFogFloor - w.z) / HEIGHT_FOG_DEPTH, 0.0, 1.0) * HEIGHT_FOG_MAX);
            return mix(color, vec3(untone(uFog.r), untone(uFog.g), untone(uFog.b)), fog);
        }
        ${if (exact) "" else """
        // SplatFaces.line: the line through p along a, its normal turned to b's side.
        vec3 line(vec2 a, vec2 b, vec2 p) {
            float side = a.x * b.y - a.y * b.x >= 0.0 ? 1.0 : -1.0;
            vec2 n = vec2(-a.y, a.x) * side;
            return vec3(n, dot(n, p));
        }
        // SplatFaces.slab: across axis a's screen direction, the cube reaches as far as b and c take it.
        vec3 slab(vec2 a, vec2 b, vec2 c, float h) {
            vec2 n = vec2(-a.y, a.x);
            return vec3(n, (abs(dot(n, b)) + abs(dot(n, c))) * h + length(n) * EDGE_PIXELS);
        }
        """}
        void cull() {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0;
            vTop = vec3(0.0); vSideX = vec3(0.0); vSideY = vec3(0.0);
            ${if (exact) "vBoxMin = vec3(0.0); vBoxSide = 0.0;" else "vCentre = vec2(0.0); vLine0 = vec3(0.0); vLine1 = vec3(0.0); vLine2 = vec3(0.0); vSlab0 = vec3(0.0); vSlab1 = vec3(0.0); vSlab2 = vec3(0.0);"}
        }

        void main() {
            uint a = aSplat.x;
            uint b = aSplat.y;
            float side = float(1u << ((a >> 20u) & 3u)) * uPerMicro;
            float half_ = side * 0.5;
            vec3 lo = vec3(uOrigin + vec2(float(a & 63u), float((a >> 6u) & 63u)) * uPerMicro, float((a >> 12u) & 255u) * uPerMicro);
            vec3 w = lo + half_;
            if (revealCut(w) > 0.5) { cull(); return; }
            vec4 clip = uViewProj * vec4(w, 1.0);
            if (clip.w < 0.5) { cull(); return; }
            // Half the sprite, in pixels.
            float r = side * SPRITE_SPREAD * uPixelsPerUnit / clip.w * 0.5;

            vec3 toEye = uEye - w;
            float dist = length(toEye);
            toEye /= dist;
            float sx = toEye.x >= 0.0 ? 1.0 : -1.0;
            float sy = toEye.y >= 0.0 ? 1.0 : -1.0;
            uint faces = (a >> 22u) & 63u;
            vec3 albedo = vec3(float((b >> 24u) & 255u), float((b >> 16u) & 255u), float((b >> 8u) & 255u)) / 255.0;
            if (uGrain > 0.0) albedo *= voxelGrain(w, vec3(0.0));
            float emissive = float(b & 255u) / 255.0 * (1.0 + uGlowGain);
            float lit = sunlit(w + uSun * side * SHADOW_LIFT);
            float ao = 1.0 - (1.0 - MIN_OCCLUSION) * float(a >> 28u) / 15.0;
            vTop = shadeFace(vec3(0.0, 0.0, 1.0), w, albedo, ao, lit, emissive, dist);
            // A closed side is hidden by its neighbour; shaded as top, the sprite's spill onto that neighbour keeps its colour.
            bool xOpen = (faces & (sx > 0.0 ? 1u : 2u)) != 0u;
            bool yOpen = (faces & (sy > 0.0 ? 4u : 8u)) != 0u;
            vSideX = xOpen ? shadeFace(vec3(sx, 0.0, 0.0), w, albedo, 1.0, lit, emissive, dist) : vTop;
            vSideY = yOpen ? shadeFace(vec3(0.0, sy, 0.0), w, albedo, 1.0, lit, emissive, dist) : vTop;
            ${if (exact) """
            vBoxMin = lo;
            vBoxSide = side;
            """ else """
            // SplatFaces.lines: pixels a world unit along each axis moves the centre, then the three lines.
            vec2 hv = uViewport * 0.5;
            float iw2 = 1.0 / (clip.w * clip.w);
            vec2 ax = (uViewProj[0].xy * clip.w - clip.xy * uViewProj[0].w) * iw2 * hv;
            vec2 ay = (uViewProj[1].xy * clip.w - clip.xy * uViewProj[1].w) * iw2 * hv;
            vec2 az = (uViewProj[2].xy * clip.w - clip.xy * uViewProj[2].w) * iw2 * hv;
            vec2 p = (ax * sx + ay * sy + az) * half_;
            vec2 ex = -sx * ax;
            vec2 fy = -sy * ay;
            vLine0 = line(ex, fy, p);
            vLine1 = line(fy, ex, p);
            vLine2 = line(-az, ex, p);
            vSlab0 = slab(ax, ay, az, half_);
            vSlab1 = slab(ay, ax, az, half_);
            vSlab2 = slab(az, ax, ay, half_);
            vCentre = (clip.xy / clip.w * 0.5 + 0.5) * uViewport;
            """}
            gl_Position = clip;
            gl_PointSize = max(1.0, 2.0 * r);
        }
    """

    fun splatFragment(exact: Boolean) = if (exact) """#version 300 es
        precision highp float;
        flat in vec3 vTop;
        flat in vec3 vSideX;
        flat in vec3 vSideY;
        flat in vec3 vBoxMin;
        flat in float vBoxSide;
        uniform mat4 uInverseViewProj;
        uniform mat4 uViewProj;
        uniform vec2 uViewport;
        out vec4 fragColor;
        void main() {
            // The pixel's ray, near plane to far, against the voxel's box.
            vec2 ndc = gl_FragCoord.xy / uViewport * 2.0 - 1.0;
            vec4 n4 = uInverseViewProj * vec4(ndc, -1.0, 1.0);
            vec4 f4 = uInverseViewProj * vec4(ndc, 1.0, 1.0);
            vec3 o = n4.xyz / n4.w;
            vec3 d = f4.xyz / f4.w - o;
            vec3 inv = 1.0 / d;
            vec3 ta = (vBoxMin - o) * inv;
            vec3 tb = (vBoxMin + vBoxSide - o) * inv;
            vec3 t0 = min(ta, tb);
            vec3 t1 = max(ta, tb);
            float enter = max(max(t0.x, t0.y), max(t0.z, 0.0));
            float leave = min(min(t1.x, t1.y), t1.z);
            if (enter > leave) discard;
            vec3 c = t0.z >= t0.x && t0.z >= t0.y ? vTop : (t0.x >= t0.y ? vSideX : vSideY);
            vec4 hit = uViewProj * vec4(o + d * enter, 1.0);
            gl_FragDepth = hit.z / hit.w * 0.5 + 0.5;
            fragColor = vec4(c, 1.0);
        }
    """ else """#version 300 es
        precision highp float;
        flat in vec3 vTop;
        flat in vec3 vSideX;
        flat in vec3 vSideY;
        flat in vec2 vCentre;
        flat in vec3 vLine0;
        flat in vec3 vLine1;
        flat in vec3 vLine2;
        flat in vec3 vSlab0;
        flat in vec3 vSlab1;
        flat in vec3 vSlab2;
        out vec4 fragColor;
        void main() {
            // SplatFaces.face: outside the cube's outline, nothing; inside, which side of its three lines.
            vec2 d = gl_FragCoord.xy - vCentre;
            if (abs(dot(vSlab0.xy, d)) > vSlab0.z || abs(dot(vSlab1.xy, d)) > vSlab1.z || abs(dot(vSlab2.xy, d)) > vSlab2.z) discard;
            bool top = dot(vLine0.xy, d) >= vLine0.z && dot(vLine1.xy, d) >= vLine1.z;
            fragColor = vec4(top ? vTop : (dot(vLine2.xy, d) >= vLine2.z ? vSideY : vSideX), 1.0);
        }
    """

    /** A splat into the sun's depth map: a square of its centre's depth, no colour. */
    val SPLAT_SHADOW_VERTEX = """#version 300 es
        precision highp float;
        layout(location = 0) in uvec2 aSplat;
        uniform mat4 uShadowViewProj;
        uniform vec2 uOrigin;
        uniform float uPerMicro;
        uniform float uShadowPixelsPerUnit;
        const float SHADOW_SPREAD = ${f(VoxelSplat.SHADOW_SPREAD)};
        void main() {
            uint a = aSplat.x;
            float side = float(1u << ((a >> 20u) & 3u)) * uPerMicro;
            vec3 w = vec3(uOrigin + vec2(float(a & 63u), float((a >> 6u) & 63u)) * uPerMicro, float((a >> 12u) & 255u) * uPerMicro) + side * 0.5;
            gl_Position = uShadowViewProj * vec4(w, 1.0);
            gl_PointSize = max(1.0, side * SHADOW_SPREAD * uShadowPixelsPerUnit);
        }
    """

    val SPLAT_SHADOW_FRAGMENT = """#version 300 es
        precision mediump float;
        void main() {}
    """

    /** Decals and glows: unlit, procedurally shaped, blended. */
    val SOFT_VERTEX = """#version 300 es
        $ATTRIBUTES
        uniform mat4 uViewProj;
        out vec3 vColor;
        out float vOpacity;
        out vec2 vUv;
        flat out float vPattern;
        flat out float vTexLayer;
        void main() {
            vColor = aColor;
            vOpacity = aAo;
            vUv = aUv;
            vPattern = aLayer;
            vTexLayer = aVariants.x;
            gl_Position = uViewProj * vec4(aPos, 1.0);
        }
    """

    val SOFT_FRAGMENT = """#version 300 es
        precision highp float;
        precision highp sampler2DArray;
        in vec3 vColor;
        in float vOpacity;
        in vec2 vUv;
        flat in float vPattern;
        flat in float vTexLayer;
        uniform bool uGlow;
        uniform sampler2DArray uTextures;
        out vec4 fragColor;
        const float GLOW_GAIN = 1.4;
        void main() {
            float r = length(vUv);
            if (uGlow) {
                if (r >= 1.0) discard;
                float fall = (1.0 - r) * (1.0 - r) * vOpacity;
                fragColor = vec4(vColor * fall * GLOW_GAIN, 1.0);
                return;
            }
            float shape;
            if (vPattern >= 1.5) {
                // A sprite's silhouette, softened by reading a smaller mip.
                shape = texture(uTextures, vec3(clamp(vUv, 0.0, 1.0), vTexLayer), 1.5).a;
            } else if (vPattern >= 0.5) {
                float k = (r - 0.82) / 0.1;
                shape = exp(-k * k);
            } else {
                shape = 1.0 - smoothstep(0.35, 1.0, r);
            }
            fragColor = vec4(vColor, clamp(shape * vOpacity, 0.0, 1.0));
        }
    """

    val SHADOW_VERTEX = """#version 300 es
        $ATTRIBUTES
        uniform mat4 uShadowViewProj;
        out vec2 vUv;
        flat out float vLayer;
        void main() {
            vUv = aUv;
            vLayer = aLayer;
            gl_Position = uShadowViewProj * vec4(aPos, 1.0);
        }
    """

    val SHADOW_FRAGMENT = """#version 300 es
        precision highp float;
        precision highp sampler2DArray;
        in vec2 vUv;
        flat in float vLayer;
        uniform sampler2DArray uTextures;
        uniform bool uCutout;
        void main() {
            if (uCutout && vLayer >= 0.0 && texture(uTextures, vec3(clamp(vUv, 0.0, 1.0), vLayer)).a < 0.5) discard;
        }
    """

    /** Full-screen triangle, used by the sky and the finishing pass. */
    val SCREEN_VERTEX = """#version 300 es
        out vec2 vUv;
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            vUv = p;
            gl_Position = vec4(p * 2.0 - 1.0, 1.0, 1.0);
        }
    """

    val SKY_FRAGMENT = """#version 300 es
        precision highp float;
        in vec2 vUv;
        uniform vec3 uTop;
        uniform vec3 uBottom;
        uniform float uExposure;
        out vec4 fragColor;
        float untone(float v) { return -log(1.0 - clamp(v, 0.0, 0.999)) / (uExposure * 1.25); }
        void main() {
            vec3 c = mix(uBottom, uTop, vUv.y);
            fragColor = vec4(untone(c.r), untone(c.g), untone(c.b), 1.0);
        }
    """

    /**
     * Tone mapping, grade and vignette; with a depth texture bound
     * (`uDepthFinish`), first the depth-reading half of the diorama finish:
     * ink in the creases, occlusion between separate pieces and the
     * tilt-shift blur. Twin of `SceneRasterizer.depthFinish`.
     */
    val FINISH_FRAGMENT = """#version 300 es
        precision highp float;
        in vec2 vUv;
        uniform sampler2D uScene;
        uniform sampler2D uDepth;
        uniform bool uDepthFinish;
        uniform vec2 uTexel;
        uniform float uNear;
        uniform float uFar;
        uniform float uEdges;
        uniform float uScreenAo;
        uniform float uTiltShift;
        uniform float uFocusDepth;
        uniform float uPixelsPerUnit;
        uniform float uExposure;
        uniform float uSaturation;
        uniform float uVignette;
        out vec4 fragColor;
        const float EDGE_GAIN = ${f(ShadingModel.EDGE_GAIN)};
        const float EDGE_LIGHT = ${f(ShadingModel.EDGE_LIGHT)};
        const float SCREEN_AO_RADIUS = ${f(ShadingModel.SCREEN_AO_RADIUS)};
        const float SCREEN_AO_CLAMP = ${f(ShadingModel.SCREEN_AO_CLAMP)};
        const float SCREEN_AO_GAIN = ${f(ShadingModel.SCREEN_AO_GAIN)};
        const float TILT_NEAR = ${f(ShadingModel.TILT_NEAR)};
        const float TILT_FAR = ${f(ShadingModel.TILT_FAR)};
        const vec2 RING[8] = vec2[8](${(0 until 8).joinToString { "vec2(${f(ShadingModel.RING[it * 2])}, ${f(ShadingModel.RING[it * 2 + 1])})" }});
        const vec2 DISC[12] = vec2[12](${(0 until 12).joinToString { "vec2(${f(ShadingModel.DISC[it * 2])}, ${f(ShadingModel.DISC[it * 2 + 1])})" }});

        // One over linear depth, 0 for the sky: what the rasteriser's `inverse` reads.
        float inverseDepth(vec2 uv) {
            float d = textureLod(uDepth, clamp(uv, 0.0, 1.0), 0.0).r;
            if (d >= 1.0) return 0.0;
            float ndc = d * 2.0 - 1.0;
            return (uFar + uNear - ndc * (uFar - uNear)) / (2.0 * uNear * uFar);
        }

        vec3 finished() {
            vec3 scene = texture(uScene, vUv).rgb;
            if (!uDepthFinish) return scene;
            float wc = inverseDepth(vUv);
            float amount = 1.0;
            float k = 1.0;
            if (wc > 0.0) {
                float lin = 1.0 / wc;
                amount = uTiltShift > 0.0 ? smoothstep(TILT_NEAR, TILT_FAR, abs(lin - uFocusDepth) / uFocusDepth) : 0.0;
                float lap = (inverseDepth(vUv - vec2(uTexel.x, 0.0)) + inverseDepth(vUv + vec2(uTexel.x, 0.0))
                    + inverseDepth(vUv - vec2(0.0, uTexel.y)) + inverseDepth(vUv + vec2(0.0, uTexel.y))) * 0.25 - wc;
                float rel = lap / wc * EDGE_GAIN;
                float crease = clamp(rel, 0.0, 1.0);
                float rim = clamp(-rel, 0.0, 1.0) * EDGE_LIGHT;
                float occ = 0.0;
                if (uScreenAo > 0.0) {
                    float r = SCREEN_AO_RADIUS * uPixelsPerUnit * wc;
                    float lo = wc * (1.0 - SCREEN_AO_CLAMP);
                    float hi = wc * (1.0 + SCREEN_AO_CLAMP);
                    float sum = 0.0;
                    for (int t = 0; t < 8; t++) sum += clamp(inverseDepth(vUv + RING[t] * r * uTexel), lo, hi);
                    occ = clamp((sum / 8.0 - wc) / wc * SCREEN_AO_GAIN, 0.0, 1.0);
                }
                float shade = (1.0 - uEdges * crease) * (1.0 - uScreenAo * occ) + uEdges * rim;
                k = 1.0 + (shade - 1.0) * (1.0 - amount);
            } else if (uTiltShift <= 0.0) {
                amount = 0.0;
            }
            if (amount > 0.0 && uTiltShift > 0.0) {
                float r = uTiltShift * amount / uTexel.y;
                vec3 sum = vec3(0.0);
                for (int t = 0; t < 12; t++) sum += texture(uScene, vUv + DISC[t] * r * uTexel).rgb;
                return sum / 12.0 * k;
            }
            return scene * k;
        }

        void main() {
            vec3 c = 1.0 - exp(-finished() * uExposure * 1.25);
            float luma = dot(c, vec3(0.299, 0.587, 0.114));
            c = vec3(luma) + (c - vec3(luma)) * uSaturation;
            vec2 d = vUv - 0.5;
            float v = 1.0 - uVignette * smoothstep(0.25, 0.75, length(d) * 1.2);
            fragColor = vec4(clamp(c * v, 0.0, 1.0), 1.0);
        }
    """
}
