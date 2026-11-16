package com.aungdev.fly_lab

// Fly Lab EDITOR - the photo -> video templates.
// Each template is ONE fragment shader (GLSL ES 1.0) used for BOTH the live
// preview and the mp4 export, so what Ko sees is exactly what is saved.
// Fly's own designs (not copies of any other app's effects).
//
// Step 1 (16 Nov 2026): Cinematic, Pulse Zoom, Retro Film - WORKS.
// Step 2 (16 Nov 2026): cut-out templates (3D Pop, Future ...) - DROPPED
//   on 17 Nov 2026 (Ko: "no background removal"); PhotoMatting.kt and the
//   multiclass model were removed.
// Step 3 (17 Nov 2026): template pack by FEELING + the user's own look.
//   10 new templates (no cut-out needed): Dreamy, Heartbeat, Glitch,
//   Sad Rain, Golden Hour, Flash Beat, Memory (polaroid), Neon Lines,
//   Snow Sparkle, Focus In.
//   Every template ends with finish(c): the user's FILTER (with strength)
//   and ADJUST sliders (brightness, contrast, saturation, warmth, vignette,
//   grain) are applied on top, then the fade in / out.
//
// Uniforms every template gets:
//   uTex        the photo (row 0 = top of the photo)
//   uTime       seconds since the start of the video
//   uDur        video length in seconds
//   uImgAspect  photo width / height
//   uOutAspect  video width / height (9:16 = 0.5625)
//   uRes        video size in pixels
//   uAdj        brightness (-0.25..0.25), contrast (0.6..1.5),
//               saturation (0..2), warmth (-1..1)
//   uAdj2       vignette (0..1), grain (0..1), filter id, filter strength
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
uniform vec2 uRes;
uniform vec4 uAdj;
uniform vec4 uAdj2;

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
// The same "cover" fit for a box of any aspect (uv 0..1 inside the box).
vec2 coverBox(vec2 uv, float boxAspect) {
    vec2 p = uv - 0.5;
    if (uImgAspect > boxAspect) {
        p.x *= boxAspect / uImgAspect;
    } else {
        p.y *= uImgAspect / boxAspect;
    }
    p += 0.5;
    return vec2(p.x, 1.0 - p.y);
}
vec3 photo(vec2 tc) { return texture2D(uTex, clamp(tc, 0.001, 0.999)).rgb; }
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float ease(float k) { k = clamp(k, 0.0, 1.0); return k * k * (3.0 - 2.0 * k); }
// Soft fade in / out at the very start and end.
float fadeInOut() {
    return smoothstep(0.0, 0.5, uTime) * smoothstep(0.0, 0.5, uDur - uTime);
}
// Round-looking distance on the 9:16 frame.
vec2 aspectD(vec2 a, vec2 b) { return (a - b) * vec2(uOutAspect, 1.0); }

// The user's filters: 0 none, 1 vivid, 2 warm, 3 cool, 4 b&w, 5 fade,
// 6 pink, 7 moody.
vec3 filterColor(vec3 c, float f) {
    float l = luma(c);
    if (f < 0.5) return c;
    if (f < 1.5) return (mix(vec3(l), c, 1.35) - 0.5) * 1.10 + 0.5;
    if (f < 2.5) return c * vec3(1.08, 1.0, 0.86) + vec3(0.03, 0.01, 0.0);
    if (f < 3.5) return c * vec3(0.90, 1.0, 1.10) + vec3(0.0, 0.01, 0.03);
    if (f < 4.5) return vec3((l - 0.5) * 1.15 + 0.5);
    if (f < 5.5) return mix(c, vec3(l), 0.25) * 0.82 + 0.12;
    if (f < 6.5) return c * vec3(1.06, 0.92, 1.02) + vec3(0.06, 0.0, 0.05);
    vec3 m = mix(vec3(l), c, 0.70);
    return ((m - 0.5) * 1.15 + 0.45) * vec3(0.95, 1.0, 1.05);
}
// Every template ends here: filter + the user's sliders + fade.
vec4 finish(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    c = mix(c, filterColor(c, uAdj2.z), uAdj2.w);
    c += uAdj.x;
    c = (c - 0.5) * uAdj.y + 0.5;
    float l = luma(c);
    c = mix(vec3(l), c, uAdj.z);
    c += vec3(0.07, 0.0, -0.07) * uAdj.w;
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * uAdj2.x * 1.8;
    c += (hash(vUv * vec2(611.0, 373.0) + fract(uTime * 5.31) * 50.0) - 0.5) * 0.14 * uAdj2.y;
    return vec4(clamp(c, 0.0, 1.0) * fadeInOut(), 1.0);
}
"""

    // ================================================== step 1 templates

    /** Cinematic Zoom: slow push-in with a gentle drift, teal / orange
     *  film grade, a warm light leak sweeping across, vignette. */
    private const val CINEMATIC = COMMON + """
void main() {
    float e = ease(uTime / uDur);
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
    gl_FragColor = finish(c);
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
    gl_FragColor = finish(c);
}
"""

    /** Retro Film: old projector look - sepia, faded blacks, grain, flicker,
     *  gate weave, scratches and round dust, heavy vignette, slow push-in. */
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
    vec2 g = vUv * vec2(48.0, 85.0);
    vec2 cell = floor(g);
    if (hash(cell + tt * 1.37) > 0.9985) {
        float dd = length(fract(g) - 0.5);
        c *= mix(0.35, 1.0, smoothstep(0.12, 0.38, dd));
    }
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 1.4;
    gl_FragColor = finish(c);
}
"""

    // ================================================== step 3 templates

    /** Dreamy (love / soft): soft glow, pastel colours, slow float-in,
     *  round bokeh lights drifting up. */
    private const val DREAMY = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec2 tc = coverUv(vUv, 1.06 + 0.10 * e, vec2(0.0, -0.01 * (e - 0.5)));
    vec3 c = photo(tc);
    vec3 b = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        b += photo(tc + vec2(cos(a), sin(a)) * 0.012);
    }
    b /= 8.0;
    c = mix(c, max(c, b), 0.6) + b * 0.12;
    c = mix(c, c * vec3(1.05, 0.95, 1.08) + vec3(0.04, 0.02, 0.06), 0.6);
    c = c * 0.90 + 0.07;
    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float sp = 0.02 + 0.03 * hash(vec2(fi, 3.0));
        vec2 p = vec2(hash(vec2(fi, 1.0)), fract(hash(vec2(fi, 2.0)) + uTime * sp));
        p.x += 0.03 * sin(uTime * 0.7 + fi * 1.7);
        float r = 0.02 + 0.04 * hash(vec2(fi, 4.0));
        float m = smoothstep(r, r * 0.55, length(aspectD(vUv, p)));
        vec3 col = mix(vec3(1.0, 0.78, 0.88), vec3(0.80, 0.86, 1.0), hash(vec2(fi, 5.0)));
        c += col * m * 0.20;
    }
    gl_FragColor = finish(c);
}
"""

    /** Heartbeat (love): pink glow, a double "thump-thump" zoom like a heart
     *  beat, little hearts floating up. */
    private const val HEARTBEAT = COMMON + """
float heart(vec2 p) {
    p.y -= 0.25;
    float a = atan(p.x, p.y) / 3.141593;
    float r = length(p);
    float h = abs(a);
    float d = (13.0 * h - 22.0 * h * h + 10.0 * h * h * h) / (6.0 - 5.0 * h);
    return smoothstep(-0.06, 0.04, d - r);
}
void main() {
    float b = fract(uTime * 1.1);
    float thump = exp(-b * 14.0) + 0.7 * step(0.22, b) * exp(-(b - 0.22) * 14.0);
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.06 + 0.06 * e + 0.045 * thump, vec2(0.0)));
    c = mix(c, c * vec3(1.06, 0.93, 1.0) + vec3(0.05, 0.0, 0.03), 0.7);
    vec2 d = vUv - 0.5;
    float edge = smoothstep(0.12, 0.45, dot(d, d));
    c += vec3(1.0, 0.25, 0.5) * edge * (0.18 + 0.25 * thump);
    for (int i = 0; i < 10; i++) {
        float fi = float(i);
        float sp = 0.06 + 0.06 * hash(vec2(fi, 2.0));
        float y = fract(hash(vec2(fi, 1.0)) + uTime * sp);
        vec2 p = vec2(hash(vec2(fi, 3.0)) + 0.04 * sin(uTime * 1.3 + fi), y * 1.2 - 0.1);
        float s = 0.035 + 0.035 * hash(vec2(fi, 4.0));
        vec2 q = aspectD(vUv, p) / s;
        float m = heart(q * 0.8) * smoothstep(1.1, 0.7, y) * smoothstep(0.0, 0.1, y);
        c = mix(c, vec3(1.0, 0.32, 0.55), m * 0.7);
    }
    gl_FragColor = finish(c);
}
"""

    /** Glitch (cool / energy): calm moments with a small RGB split, then
     *  glitch bursts - torn slices, colour blocks, scanlines. */
    private const val GLITCH = COMMON + """
void main() {
    float f = floor(uTime * 12.0);
    float burst = step(0.55, hash(vec2(floor(uTime * 3.0), 7.0)));
    float k = clamp(uTime / uDur, 0.0, 1.0);
    vec2 uv = vUv;
    float slice = floor(uv.y * 24.0);
    uv.x += (hash(vec2(slice, f)) - 0.5) * 0.09 * burst * step(0.7, hash(vec2(slice, f + 3.0)));
    float zoom = 1.05 + 0.06 * k + 0.03 * burst;
    float sh = 0.003 + 0.014 * burst;
    float r = photo(coverUv(uv + vec2(sh, 0.0), zoom, vec2(0.0))).r;
    float g = photo(coverUv(uv, zoom, vec2(0.0))).g;
    float b = photo(coverUv(uv - vec2(sh, 0.0), zoom, vec2(0.0))).b;
    vec3 c = vec3(r, g, b);
    if (burst > 0.5 && hash(floor(vUv * vec2(8.0, 14.0)) + f) > 0.93) {
        c = vec3(c.g, c.b, c.r) * 1.3;
    }
    c *= 0.92 + 0.08 * sin(vUv.y * uRes.y * 1.6);
    c = (c - 0.5) * 1.1 + 0.5;
    c *= vec3(0.96, 1.02, 1.08);
    gl_FragColor = finish(c);
}
"""

    /** Sad Rain (sad / missing someone): blue, faded colours, a slow pull
     *  back, rain falling. */
    private const val SAD_RAIN = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.18 - 0.12 * e, vec2(0.0)));
    float l = luma(c);
    c = mix(c, vec3(l), 0.55) * vec3(0.84, 0.95, 1.12);
    c = c * 0.86 + 0.03;
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        float cols = 70.0 + 40.0 * fi;
        vec2 q = vUv + vec2(vUv.y * 0.12, 0.0);
        float col = floor(q.x * cols);
        float lx = fract(q.x * cols) - 0.5;
        float on = step(0.45, hash(vec2(col, fi)));
        float y = fract(q.y * (1.5 + fi) + uTime * (1.4 + 0.8 * hash(vec2(col, 2.0 + fi))) + hash(vec2(col, 5.0)) * 9.0);
        float line = (1.0 - smoothstep(0.0, 0.14, abs(lx))) * smoothstep(0.0, 0.05, y) * (1.0 - smoothstep(0.12, 0.3, y));
        c += vec3(0.75, 0.85, 1.0) * line * on * (0.16 + 0.08 * fi);
    }
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 1.1;
    gl_FragColor = finish(c);
}
"""

    /** Golden Hour (happy / warm memories): warm sun light moving across the
     *  top, lens ghosts, twinkling sparkles, slow push-in. */
    private const val GOLDEN = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.04 + 0.08 * e, vec2(0.0)));
    float l = luma(c);
    c = c * vec3(1.10, 1.0, 0.82) + vec3(0.04, 0.02, 0.0);
    c = mix(c, c * vec3(1.05, 0.95, 0.85), l);
    vec2 sun = vec2(mix(0.12, 0.88, e), 0.84);
    float ds = length(aspectD(vUv, sun));
    c += vec3(1.0, 0.72, 0.35) * (exp(-ds * ds * 18.0) * 0.55 + exp(-ds * 3.0) * 0.12);
    float ang = atan(vUv.y - sun.y, (vUv.x - sun.x) * uOutAspect);
    c += vec3(1.0, 0.8, 0.5) * pow(max(0.0, sin(ang * 9.0 + uTime * 0.4)), 8.0) * exp(-ds * 2.5) * 0.12;
    for (int i = 1; i < 5; i++) {
        float fi = float(i);
        vec2 gp = sun + (vec2(0.5) - sun) * (0.5 + fi * 0.38);
        float dg = length(aspectD(vUv, gp));
        float r = 0.02 + 0.015 * fi;
        c += mix(vec3(1.0, 0.6, 0.3), vec3(0.6, 0.9, 1.0), fi / 4.0) * smoothstep(r, r * 0.6, dg) * 0.08;
    }
    vec2 g = vec2(vUv.x * uOutAspect, vUv.y) * 22.0;
    vec2 cell = floor(g);
    float hs = hash(cell);
    if (hs > 0.94) {
        vec2 f = fract(g) - 0.5;
        float tw = 0.5 + 0.5 * sin(uTime * (2.0 + 3.0 * hs) + hs * 40.0);
        float star = max(0.0, 1.0 - abs(f.x) * 18.0 - abs(f.y) * 3.0) + max(0.0, 1.0 - abs(f.y) * 18.0 - abs(f.x) * 3.0);
        c += vec3(1.0, 0.9, 0.7) * star * tw * 0.5;
    }
    gl_FragColor = finish(c);
}
"""

    /** Flash Beat (hype): on every beat the shot jumps to a new zoom / spot
     *  with a white flash; every 4th beat goes black & white. */
    private const val FLASH = COMMON + """
void main() {
    float bt = uTime * 2.0;
    float n = floor(bt);
    float fr = fract(bt);
    float z = 1.04 + 0.24 * hash(vec2(n, 1.0));
    vec2 pan = (vec2(hash(vec2(n, 2.0)), hash(vec2(n, 3.0))) - 0.5) * 0.08 * (z - 1.0) * 4.0;
    vec3 c = photo(coverUv(vUv, z + 0.05 * fr, pan));
    float bw = step(3.0, mod(n, 4.0));
    c = mix(c, vec3((luma(c) - 0.5) * 1.3 + 0.5), bw);
    c = (c - 0.5) * 1.15 + 0.5;
    c += exp(-fr * 10.0) * 0.55;
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 0.8;
    gl_FragColor = finish(c);
}
"""

    /** Memory (nostalgic): the photo as a polaroid that drops in, sways a
     *  little and develops from pale to full colour, over a soft blurred
     *  copy of itself. */
    private const val MEMORY = COMMON + """
void main() {
    // Soft blurred background.
    vec2 btc = coverUv(vUv, 1.25, vec2(0.0));
    vec3 bg = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float a = float(i) * 0.5236;
        bg += photo(btc + vec2(cos(a), sin(a)) * 0.035);
    }
    bg = bg / 12.0 * 0.45;
    // The card: drops in (0..0.7 s), sways gently.
    float drop = ease(uTime / 0.7);
    float ang = mix(-0.28, 0.0, drop) + 0.045 * sin(uTime * 0.9);
    float sc = mix(1.35, 1.0, drop);
    vec2 q = vec2((vUv.x - 0.5) * uOutAspect, vUv.y - 0.5 - 0.02);
    float cs = cos(ang); float sn = sin(ang);
    q = vec2(cs * q.x + sn * q.y, -sn * q.x + cs * q.y) * sc;
    vec2 half_ = vec2(0.215, 0.27);
    vec2 inQ = abs(q) - half_;
    float card = 1.0 - smoothstep(-0.002, 0.002, max(inQ.x, inQ.y));
    float sh = 1.0 - smoothstep(0.0, 0.05, max(abs(q.x + 0.012) - half_.x, abs(q.y + 0.018) - half_.y));
    vec3 c = bg * (1.0 - sh * 0.5);
    // Photo window inside the white card (thicker bottom border).
    vec2 lo = vec2(-half_.x + 0.018, -half_.y + 0.075);
    vec2 hi = vec2(half_.x - 0.018, half_.y - 0.018);
    vec2 iuv = (q - lo) / (hi - lo);
    float inside = step(0.0, iuv.x) * step(iuv.x, 1.0) * step(0.0, iuv.y) * step(iuv.y, 1.0);
    vec2 ptc = coverBox(iuv, (hi.x - lo.x) / (hi.y - lo.y));
    vec3 p = photo(ptc);
    float dev = ease((uTime - 0.6) / 2.6);
    vec3 pale = mix(vec3(0.93, 0.92, 0.88), p, 0.25);
    p = mix(pale, p * vec3(1.04, 1.0, 0.92) + 0.02, dev);
    vec3 cardCol = mix(vec3(0.97, 0.96, 0.93), p, inside);
    c = mix(c, cardCol, card * clamp(uTime * 3.0, 0.0, 1.0));
    gl_FragColor = finish(c);
}
"""

    /** Neon Lines (night / cool): the photo's outlines glow in moving neon
     *  colours over a darkened photo, pulsing with the beat. */
    private const val NEON = COMMON + """
float L(vec2 uv, float z) { return luma(photo(coverUv(uv, z, vec2(0.0)))); }
void main() {
    float e = ease(uTime / uDur);
    float z = 1.05 + 0.07 * e;
    vec2 px = 1.6 / uRes;
    float tl = L(vUv + vec2(-px.x, px.y), z);
    float t = L(vUv + vec2(0.0, px.y), z);
    float tr = L(vUv + vec2(px.x, px.y), z);
    float l = L(vUv + vec2(-px.x, 0.0), z);
    float r = L(vUv + vec2(px.x, 0.0), z);
    float bl = L(vUv + vec2(-px.x, -px.y), z);
    float b = L(vUv + vec2(0.0, -px.y), z);
    float br = L(vUv + vec2(px.x, -px.y), z);
    float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
    float gy = (tl + 2.0 * t + tr) - (bl + 2.0 * b + br);
    float edge = smoothstep(0.10, 0.45, length(vec2(gx, gy)));
    vec3 base = photo(coverUv(vUv, z, vec2(0.0)));
    float beat = exp(-fract(uTime * 2.0) * 5.0);
    vec3 neon = mix(vec3(1.0, 0.2, 0.75), vec3(0.15, 0.9, 1.0), 0.5 + 0.5 * sin(vUv.y * 6.0 - uTime * 2.0));
    float reveal = ease(uTime / 1.5);
    vec3 dark = base * mix(1.0, 0.28, reveal);
    vec3 c = dark + neon * edge * (1.1 + 0.6 * beat) * reveal;
    gl_FragColor = finish(c);
}
"""

    /** Snow Sparkle (winter / calm): cool soft colours, snow falling in
     *  three depths, a slow push-in. */
    private const val SNOW = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.05 + 0.08 * e, vec2(0.0)));
    c = c * vec3(0.95, 1.0, 1.07) + vec3(0.02, 0.03, 0.05);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float s = 7.0 + fi * 6.0;
        vec2 g = vec2(vUv.x * uOutAspect, vUv.y) * s;
        g.y += uTime * (0.5 + 0.35 * fi) * (s / 10.0) * 0.6;
        g.x += sin(uTime * 0.6 + fi * 2.0 + g.y * 0.3) * 0.3;
        vec2 cell = floor(g);
        if (hash(cell + fi * 17.0) > 0.45) {
            vec2 pos = vec2(hash(cell + 3.1), hash(cell + 7.7)) * 0.6 - 0.3;
            float d = length(fract(g) - 0.5 - pos);
            float rad = 0.06 + 0.06 * hash(cell + 1.3);
            c += vec3(1.0) * smoothstep(rad, rad * 0.3, d) * (0.9 - 0.25 * fi);
        }
    }
    gl_FragColor = finish(c);
}
"""

    /** Focus In (cinema / reveal): starts blurred and close, snaps into
     *  focus with a soft flash, then a slow push-in. */
    private const val FOCUS = COMMON + """
void main() {
    float f = ease((uTime - 0.5) / 1.1);
    float blur = (1.0 - f) * 0.03;
    float zoom = mix(1.25, 1.05, f) + 0.08 * ease((uTime - 1.6) / max(uDur - 1.6, 0.1));
    vec2 tc = coverUv(vUv, zoom, vec2(0.0));
    vec3 c = photo(tc) * 2.0;
    for (int i = 0; i < 12; i++) {
        float a = float(i) * 0.5236;
        float rr = (i < 6) ? 0.5 : 1.0;
        c += photo(tc + vec2(cos(a), sin(a)) * blur * rr);
    }
    c /= 14.0;
    float flash = exp(-max(uTime - 1.5, 0.0) * 4.0) * step(1.2, uTime);
    c += flash * 0.35;
    c = (c - 0.5) * 1.06 + 0.5;
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 0.8;
    gl_FragColor = finish(c);
}
"""

    val fragments: Map<String, String> = mapOf(
        "cinematic" to CINEMATIC,
        "pulse" to PULSE,
        "retro" to RETRO,
        "dreamy" to DREAMY,
        "heartbeat" to HEARTBEAT,
        "glitch" to GLITCH,
        "sad_rain" to SAD_RAIN,
        "golden" to GOLDEN,
        "flash" to FLASH,
        "memory" to MEMORY,
        "neon" to NEON,
        "snow" to SNOW,
        "focus" to FOCUS,
    )
}
