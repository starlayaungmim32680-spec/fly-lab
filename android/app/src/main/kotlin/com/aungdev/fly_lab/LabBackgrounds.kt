package com.aungdev.fly_lab

// Fly Lab step 3 (16 Nov 2026) - futuristic animated backgrounds, drawn live
// by the GPU (AGSL RuntimeShader, Android 13+), so there is no video file,
// no download and no licence question - and they are endless / never loop
// visibly. Each has a glow colour for the neon rim around the person.
//
// AGSL rules kept here: uniforms iResolution (px) + iTime (s); y grows
// DOWN; constant loop bounds; smoothstep only with edge0 < edge1.

import android.graphics.Color

object LabBackgrounds {

    /** Mode key -> neon rim colour around the person. */
    val glow: Map<String, Int> = mapOf(
        "city" to Color.rgb(255, 64, 160),
        "warp" to Color.rgb(0, 229, 255),
        "matrix" to Color.rgb(0, 255, 110),
        "grid" to Color.rgb(0, 229, 255),
        "aurora" to Color.rgb(90, 255, 180),
        "chrome" to Color.rgb(230, 240, 255),
    )

    private const val COMMON = """
uniform float2 iResolution;
uniform float iTime;
float hash(float n) { return fract(sin(n) * 43758.5453); }
float hash2(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
"""

    /** Synthwave city: pink-purple sky, striped sun, neon skyline, moving
     *  grid floor. */
    private const val CITY = COMMON + """
half4 main(float2 fc) {
    float2 uv = fc / iResolution;
    float y = 1.0 - uv.y;
    float aspect = iResolution.x / iResolution.y;
    float horizon = 0.40;
    float3 col = float3(0.0);
    if (y > horizon) {
        float t = (y - horizon) / (1.0 - horizon);
        col = mix(float3(1.0, 0.22, 0.52), float3(0.06, 0.02, 0.22), pow(t, 0.55));
        float2 p = float2((uv.x - 0.5) * aspect, y - (horizon + 0.16));
        float d = length(p);
        float sun = 1.0 - smoothstep(0.15, 0.155, d);
        float stripes = step(0.45, fract((y - horizon) * 42.0 + iTime * 0.6));
        if (p.y < 0.02) { sun *= stripes; }
        float3 sunCol = mix(float3(1.0, 0.25, 0.6), float3(1.0, 0.9, 0.3), clamp(p.y * 4.0 + 0.6, 0.0, 1.0));
        col = mix(col, sunCol, sun);
        col += float3(1.0, 0.3, 0.6) * exp(-d * 7.0) * 0.35;
        float i = floor(uv.x * 26.0);
        float bh = 0.03 + hash(i * 3.17) * 0.16 + hash(i * 7.3) * hash(i * 1.9) * 0.08;
        if (y - horizon < bh) {
            col = float3(0.04, 0.0, 0.10);
            float wx = fract(uv.x * 26.0 * 3.0);
            float wy = fract(y * 90.0);
            float lit = step(0.62, hash(i * 13.0 + floor(y * 90.0) * 1.7 + floor(uv.x * 78.0)));
            float win = step(0.25, wx) * step(wx, 0.75) * step(0.3, wy) * step(wy, 0.8);
            float flick = 0.7 + 0.3 * sin(iTime * 3.0 + i);
            col += mix(float3(0.0, 0.9, 1.0), float3(1.0, 0.3, 0.8), hash(i)) * lit * win * flick;
        }
    } else {
        float d = horizon - y;
        float z = 0.10 / max(d, 0.0015);
        float x = (uv.x - 0.5) * aspect * z;
        float gx = abs(fract(x * 1.6) - 0.5);
        float gz = abs(fract(z * 0.9 + iTime * 1.4) - 0.5);
        float w = 0.03 + 0.004 * z;
        float line = max(1.0 - smoothstep(0.0, w, gx), 1.0 - smoothstep(0.0, w, gz));
        float fade = exp(-z * 0.07);
        col = float3(0.08, 0.0, 0.16) + float3(1.0, 0.2, 0.8) * line * fade;
        col += float3(1.0, 0.3, 0.7) * exp(-d * 25.0) * 0.6;
    }
    return half4(half3(col), 1.0);
}
"""

    /** Warp tunnel: star streaks rushing out of the centre. */
    private const val WARP = COMMON + """
half4 main(float2 fc) {
    float2 p = (fc - 0.5 * iResolution) / iResolution.y;
    float r = length(p);
    float a = atan(p.y, p.x) / 6.2831853 + 0.5;
    float3 col = float3(0.01, 0.0, 0.06);
    for (int k = 0; k < 3; k++) {
        float n = 90.0 + float(k) * 70.0;
        float id = floor(a * n);
        float fa = fract(a * n);
        float h = hash(id * (1.0 + float(k) * 0.37));
        if (h > 0.45) {
            float z = 0.25 / (r + 0.02);
            float s = fract(z * (0.3 + h * 0.5) - iTime * (0.5 + h) + h * 17.0);
            float streak = smoothstep(0.0, 0.05, s) * (1.0 - smoothstep(0.05, 0.45, s));
            float thin = 1.0 - smoothstep(0.0, 0.35, abs(fa - 0.5));
            float3 c = mix(float3(0.2, 0.9, 1.0), float3(0.9, 0.3, 1.0), hash(id + 3.0));
            col += c * streak * thin * smoothstep(0.02, 0.35, r) * 1.6;
        }
    }
    col += float3(0.4, 0.3, 1.0) * exp(-r * 7.0) * (0.8 + 0.2 * sin(iTime * 4.0));
    return half4(half3(col), 1.0);
}
"""

    /** Matrix rain: falling green glyph columns. */
    private const val MATRIX = COMMON + """
half4 main(float2 fc) {
    float cols = 34.0;
    float cs = iResolution.x / cols;
    float2 cell = floor(fc / cs);
    float2 f = fract(fc / cs);
    float rows = iResolution.y / cs;
    float sp = 6.0 + hash(cell.x * 1.7) * 10.0;
    float head = fract(iTime * sp / (rows + 25.0) + hash(cell.x * 7.1)) * (rows + 25.0);
    float dist = head - cell.y;
    float3 col = float3(0.0, 0.03, 0.01);
    if (dist >= 0.0 && dist < 22.0) {
        float b = 1.0 - dist / 22.0;
        float2 g = floor(f * float2(3.0, 5.0));
        float change = floor(iTime * (2.0 + hash(cell.x + cell.y) * 6.0));
        float bit = step(0.45, hash2(cell * 1.31 + g * 0.17 + change));
        float inside = step(0.12, f.x) * step(f.x, 0.88) * step(0.08, f.y) * step(f.y, 0.92);
        float glyph = bit * inside;
        float3 c = (dist < 1.0) ? float3(0.85, 1.0, 0.9) : float3(0.0, 1.0, 0.35);
        col += c * glyph * b * b * 1.2;
    }
    return half4(half3(col), 1.0);
}
"""

    /** Hologram room: cyan grid floor and ceiling, glowing horizon, scan
     *  lines, a pulse ring. */
    private const val GRID = COMMON + """
half4 main(float2 fc) {
    float2 p = (fc - 0.5 * iResolution) / iResolution.y;
    float ay = abs(p.y);
    float3 col = float3(0.0, 0.02, 0.05);
    if (ay > 0.004) {
        float z = 0.32 / ay;
        float x = p.x * z;
        float gx = abs(fract(x * 1.4) - 0.5);
        float gz = abs(fract(z * 0.8 - iTime * 0.9) - 0.5);
        float w = 0.025 + 0.003 * z;
        float line = max(1.0 - smoothstep(0.0, w, gx), 1.0 - smoothstep(0.0, w, gz));
        col += float3(0.0, 0.85, 1.0) * line * exp(-z * 0.09);
    }
    col += float3(0.2, 0.8, 1.0) * exp(-ay * 22.0) * 0.9;
    float ring = abs(fract(length(p) * 1.5 - iTime * 0.35) - 0.5);
    col += float3(0.1, 0.6, 1.0) * (1.0 - smoothstep(0.0, 0.02, ring)) * 0.25;
    col *= 0.88 + 0.12 * sin(fc.y * 1.6 + iTime * 8.0);
    return half4(half3(col), 1.0);
}
"""

    /** Aurora sky: twinkling stars and moving green-violet light curtains. */
    private const val AURORA = COMMON + """
half4 main(float2 fc) {
    float2 uv = fc / iResolution;
    float y = 1.0 - uv.y;
    float3 col = mix(float3(0.0, 0.01, 0.05), float3(0.02, 0.04, 0.16), y);
    float2 sc = floor(fc / 3.0);
    float st = hash2(sc);
    if (st > 0.992) {
        col += float3(1.0) * (0.5 + 0.5 * sin(iTime * (2.0 + st * 8.0) + st * 50.0));
    }
    for (int k = 0; k < 3; k++) {
        float fk = float(k);
        float c = 0.62 + fk * 0.08 + 0.07 * sin(uv.x * 3.0 + iTime * 0.35 + fk * 2.0)
                  + 0.04 * sin(uv.x * 8.0 - iTime * 0.6 + fk);
        float dd = (y - c) * 9.0;
        float band = exp(-dd * dd) * (0.55 + 0.45 * sin(uv.x * 26.0 + iTime * 1.3 + fk * 3.0));
        float3 bc = mix(float3(0.1, 1.0, 0.55), float3(0.7, 0.3, 1.0), fk / 2.0);
        col += bc * band * 0.75;
    }
    return half4(half3(col), 1.0);
}
"""

    /** Liquid chrome: flowing metal with neon reflections. */
    private const val CHROME = COMMON + """
half4 main(float2 fc) {
    float2 p = fc / iResolution.y * 3.0;
    float t = iTime * 0.6;
    float2 q = p + float2(sin(p.y * 1.3 + t), cos(p.x * 1.1 - t * 0.8));
    q += 0.6 * float2(sin(q.y * 2.1 + t * 1.3), cos(q.x * 1.7 - t));
    float v = sin(q.x * 2.0 + q.y * 1.5);
    float m = 0.5 + 0.5 * v;
    float3 col = mix(float3(0.05, 0.06, 0.09), float3(0.95, 0.97, 1.0), pow(m, 3.0));
    col += float3(0.0, 0.6, 1.0) * pow(0.5 + 0.5 * sin(v * 4.0 + t * 2.0), 8.0) * 0.5;
    col += float3(1.0, 0.2, 0.8) * pow(0.5 + 0.5 * cos(v * 3.0 - t * 1.5), 10.0) * 0.4;
    return half4(half3(col), 1.0);
}
"""

    val shaders: Map<String, String> = mapOf(
        "city" to CITY, "warp" to WARP, "matrix" to MATRIX,
        "grid" to GRID, "aurora" to AURORA, "chrome" to CHROME,
    )
}
