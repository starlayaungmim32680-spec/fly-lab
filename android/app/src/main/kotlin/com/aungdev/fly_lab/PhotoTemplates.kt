package com.aungdev.fly_lab

// Fly Lab EDITOR step 1 (16 Nov 2026) - the photo -> video templates.
// Each template is ONE fragment shader (GLSL ES 1.0) used for BOTH the live
// preview and the mp4 export, so what Ko sees is exactly what is saved.
// Fly's own designs (not copies of any other app's effects).
//
// Uniforms every template gets:
//   uTex        the photo (row 0 = top of the photo)
//   uTime       seconds since the start of the video
//   uDur        video length in seconds
//   uImgAspect  photo width / height
//   uOutAspect  video width / height (9:16 = 0.5625)
// vUv: 0..1 over the video frame, y = 0 at the BOTTOM.

object PhotoTemplates {

    const val VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val COMMON = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform float uTime;
uniform float uDur;
uniform float uImgAspect;
uniform float uOutAspect;

// Screen uv -> photo texture coords: "cover" fit (fills the frame, crops
// the rest), zoom >= 1 goes closer, pan moves the view (photo units).
vec2 coverUv(vec2 uv, float zoom, vec2 pan) {
    vec2 p = (uv - 0.5) / zoom;
    if (uImgAspect > uOutAspect) {
        p.x *= uOutAspect / uImgAspect;
    } else {
        p.y *= uImgAspect / uOutAspect;
    }
    p += 0.5 + pan;
    return vec2(p.x, 1.0 - p.y);
}
vec3 photo(vec2 tc) { return texture2D(uTex, clamp(tc, 0.001, 0.999)).rgb; }
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
// Soft fade in / out at the very start and end.
float fadeInOut() {
    return smoothstep(0.0, 0.5, uTime) * smoothstep(0.0, 0.5, uDur - uTime);
}
"""

    /** Cinematic Zoom: slow push-in with a gentle drift, teal / orange
     *  film grade, a warm light leak sweeping across, vignette. */
    private const val CINEMATIC = COMMON + """
void main() {
    float k = clamp(uTime / uDur, 0.0, 1.0);
    float e = k * k * (3.0 - 2.0 * k);
    float zoom = 1.08 + 0.16 * e;
    vec2 pan = vec2(0.02 * (e - 0.5), -0.012 * (e - 0.5));
    vec3 c = photo(coverUv(vUv, zoom, pan));
    float l = luma(c);
    c = mix(c, c * vec3(0.90, 1.00, 1.08), 1.0 - l);
    c = mix(c, c * vec3(1.08, 1.00, 0.90), l);
    c = (c - 0.5) * 1.08 + 0.5;
    float x = (vUv.x + vUv.y * 0.6) - mod(uTime * 0.32, 2.4) + 0.4;
    c += vec3(1.0, 0.55, 0.25) * exp(-x * x * 16.0) * 0.45;
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 0.9;
    gl_FragColor = vec4(clamp(c, 0.0, 1.0) * fadeInOut(), 1.0);
}
"""

    /** Pulse Zoom: zoom punch on every beat (120 bpm), RGB split and a small
     *  shake with the punch, contrast pop and flash, a bigger hit every
     *  4th beat. */
    private const val PULSE = COMMON + """
void main() {
    float beat = fract(uTime * 2.0);
    float big = step(3.0, mod(floor(uTime * 2.0), 4.0));
    float pulse = exp(-beat * 7.0) * (1.0 + 0.6 * big);
    float zoom = 1.06 + 0.09 * pulse;
    float f = floor(uTime * 24.0);
    vec2 shake = vec2(hash(vec2(f, 1.0)) - 0.5, hash(vec2(f, 2.0)) - 0.5) * 0.014 * pulse;
    vec2 uv = vUv + shake;
    vec2 dir = (uv - 0.5) * 0.03 * pulse;
    float r = photo(coverUv(uv + dir, zoom, vec2(0.0))).r;
    float g = photo(coverUv(uv, zoom, vec2(0.0))).g;
    float b = photo(coverUv(uv - dir, zoom, vec2(0.0))).b;
    vec3 c = vec3(r, g, b);
    c = (c - 0.5) * (1.0 + 0.25 * pulse) + 0.5;
    c += 0.10 * pulse;
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 0.7;
    gl_FragColor = vec4(clamp(c, 0.0, 1.0) * fadeInOut(), 1.0);
}
"""

    /** Retro Film: old projector look - sepia, faded blacks, grain, flicker,
     *  gate weave, scratches and dust, heavy vignette, a slow push-in. */
    private const val RETRO = COMMON + """
void main() {
    float tt = floor(uTime * 18.0);
    vec2 weave = vec2(hash(vec2(tt, 3.0)) - 0.5, hash(vec2(tt, 4.0)) - 0.5) * vec2(0.003, 0.006);
    float zoom = 1.04 + 0.05 * clamp(uTime / uDur, 0.0, 1.0);
    vec3 c = photo(coverUv(vUv + weave, zoom, vec2(0.0)));
    float l = luma(c);
    c = mix(c, vec3(l) * vec3(1.07, 0.93, 0.74), 0.75);
    c = c * 0.85 + 0.07;
    c += (hash(vUv * vec2(731.0, 397.0) + fract(uTime * 7.13) * 100.0) - 0.5) * 0.11;
    c *= 0.92 + 0.08 * hash(vec2(tt, 9.0));
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        if (hash(vec2(tt, 7.0 + fi)) > 0.55) {
            float xs = hash(vec2(tt, 11.0 + fi));
            float dx = abs(vUv.x - xs);
            c += (1.0 - smoothstep(0.0, 0.0015, dx)) * 0.30;
        }
    }
    vec2 cell = floor(vUv * vec2(48.0, 85.0));
    if (hash(cell + tt * 1.37) > 0.9985) { c *= 0.35; }
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 1.4;
    gl_FragColor = vec4(clamp(c, 0.0, 1.0) * fadeInOut(), 1.0);
}
"""

    /** id -> fragment shader. */
    val fragments: Map<String, String> = linkedMapOf(
        "cinematic" to CINEMATIC,
        "pulse" to PULSE,
        "retro" to RETRO,
    )
}
