package com.stratum.feature.play.gl

/**
 * GLSL ES 3.00 ports of `ShadingModel` and the software rasteriser.
 *
 * Every constant and every formula here has a twin in
 * `engine/scene/.../ShadingModel.kt`; the preview images are rendered from the
 * Kotlin side, the game from this side. When one changes, the other must, or
 * the screenshots stop being evidence.
 */
internal object SceneShaders {

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
        uniform bool uCutout;
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

        float sunlit(float ndl) {
            vec3 p = vShadow.xyz / vShadow.w * 0.5 + 0.5;
            if (p.x < 0.0 || p.y < 0.0 || p.x > 1.0 || p.y > 1.0) return 1.0;
            float bias = 0.0015 + 0.004 * (1.0 - ndl);
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

            float hemi = n.z * 0.5 + 0.5;
            vec3 light = mix(uGround, uSky, hemi) * ao;
            float ndl = max(0.0, dot(n, uSun));
            float lit = ndl > 0.0 ? sunlit(ndl) : 0.0;
            light += uSunColor * ndl * (1.0 - uShadowStrength * (1.0 - lit));
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
            vec3 color = light * albedo + albedo * vEmissive * EMISSIVE_GAIN;
            if (vLayer < -1.5) {
                float edge = 1.0 - max(0.0, dot(n, toEye));
                color += uRim * edge * edge;
            }
            float fog = smoothstep(uFogStart, uFogEnd, dist);
            if (vWorld.z < uFogFloor) fog = max(fog, clamp((uFogFloor - vWorld.z) / HEIGHT_FOG_DEPTH, 0.0, 1.0) * HEIGHT_FOG_MAX);
            vec3 fogPre = vec3(untone(uFog.r), untone(uFog.g), untone(uFog.b));
            fragColor = vec4(mix(color, fogPre, fog), 1.0);
        }
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

    val FINISH_FRAGMENT = """#version 300 es
        precision highp float;
        in vec2 vUv;
        uniform sampler2D uScene;
        uniform float uExposure;
        uniform float uSaturation;
        uniform float uVignette;
        out vec4 fragColor;
        void main() {
            vec3 c = 1.0 - exp(-texture(uScene, vUv).rgb * uExposure * 1.25);
            float luma = dot(c, vec3(0.299, 0.587, 0.114));
            c = vec3(luma) + (c - vec3(luma)) * uSaturation;
            vec2 d = vUv - 0.5;
            float v = 1.0 - uVignette * smoothstep(0.25, 0.75, length(d) * 1.2);
            fragColor = vec4(clamp(c * v, 0.0, 1.0), 1.0);
        }
    """
}
