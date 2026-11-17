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
//   Step 4 (17 Nov 2026): the LIBRARY - 22 more (35 in all), each in ONE
//   category: Love, Beat, Cinema, Feel, Night, Fun, Retro (the app's
//   category list lives in editor_lab.dart). Shared helpers in COMMON:
//   heartD (heart shape), vnoise / fbm (soft noise), hueShift.
//   Step 5 (17 Nov 2026): COMBINE + more. 7 new templates (Spin, Dolly
//   Zoom, Aurora, Kaleido, Pop Art, Sketch, Duotone; new category Art) and
//   12 EFFECTS made for stacking (no zoom / no colour grade of their own:
//   Sparkle, Falling Hearts, Snowfall, Rain, Bokeh, Dust, Light Rays, Lens
//   Flare, Bubbles, Film Strip, Fireworks, Balloons). The renderer chains
//   passes: template -> effect -> effect (each pass reads the previous one
//   from an offscreen texture: uFlipIn = 1 flips it the right way up); only
//   the LAST pass (uFinal = 1) applies the user's look + the fade.
//   Step 6 (17 Nov 2026): 21 more EFFECTS (33 in all) from Ko's ideas -
//   big moon, sunrise, shooting stars, birds, falling leaves - and more:
//   Sky (Moon, Sunrise, Shooting Stars, Birds, Clouds, Night Sky, Rainbow),
//   Nature (Leaves, Sakura, Butterflies, Fireflies, Dandelion), Weather
//   (Lightning, Fog), Light (Prism, Spotlight, Gold Glitter, Neon Frame),
//   Party (Sky Lanterns, Heart Burst, Confetti). spectrum(h) in COMMON.
//   Project editor E1a (17 Nov 2026): "plain" (the clip as it is) for
//   clips with no template; uFade (x = fade in at the start, y = fade out
//   at the end) so clips in the middle of a project don't dip to black.
//   COMPOSE / COMPOSE_OES draw a photo or a video frame into the project's
//   frame (fill, or fit with a blurred / colour background).
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
uniform float uFinal;
uniform float uFlipIn;
uniform vec2 uFade;

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
vec3 photo(vec2 tc) {
    tc = clamp(tc, 0.001, 0.999);
    tc.y = mix(tc.y, 1.0 - tc.y, uFlipIn);
    return texture2D(uTex, tc).rgb;
}
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float ease(float k) { k = clamp(k, 0.0, 1.0); return k * k * (3.0 - 2.0 * k); }
// Soft fade in / out at the very start and end.
float fadeInOut() {
    return mix(1.0, smoothstep(0.0, 0.5, uTime), uFade.x)
         * mix(1.0, smoothstep(0.0, 0.5, uDur - uTime), uFade.y);
}
// Round-looking distance on the 9:16 frame.
vec2 aspectD(vec2 a, vec2 b) { return (a - b) * vec2(uOutAspect, 1.0); }
// Heart shape (> 0 inside), p about -1..1, point down, y up.
float heartD(vec2 p) {
    p.y -= 0.25;
    float a = atan(p.x, p.y) / 3.141593;
    float r = length(p);
    float h = abs(a);
    float d = (13.0 * h - 22.0 * h * h + 10.0 * h * h * h) / (6.0 - 5.0 * h);
    return d - r;
}
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float fbm(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) { s += a * vnoise(p); p *= 2.03; a *= 0.5; }
    return s;
}
// Rainbow colour, h 0..1 (0 = red).
vec3 spectrum(float h) {
    return clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}
vec3 hueShift(vec3 c, float ang) {
    mat3 toY = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);
    mat3 toR = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);
    vec3 y = toY * c;
    float h = atan(y.z, y.y) + ang;
    float ch = length(y.yz);
    y.y = ch * cos(h);
    y.z = ch * sin(h);
    return toR * y;
}

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
    if (uFinal < 0.5) return vec4(c, 1.0);
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

    // ================================================== project editor
    /** Plain: the clip as it is (still gets the user's look + fades). */
    private const val PLAIN = COMMON + """
void main() {
    gl_FragColor = finish(photo(coverUv(vUv, 1.0, vec2(0.0))));
}
"""

    private const val COMPOSE_BODY = """
uniform float uSrcAspect;
uniform float uOutAspect;
uniform float uFit;
uniform float uBgMode;
uniform vec3 uBgColor;
vec2 coverSrc(vec2 uv) {
    vec2 p = uv - 0.5;
    float r = uSrcAspect / uOutAspect;
    if (r > 1.0) p.x /= r; else p.y *= r;
    return p + 0.5;
}
vec2 containSrc(vec2 uv) {
    vec2 p = uv - 0.5;
    float r = uSrcAspect / uOutAspect;
    if (r > 1.0) p.y *= r; else p.x /= r;
    return p + 0.5;
}
void main() {
    vec3 c;
    if (uFit < 0.5) {
        c = src(clamp(coverSrc(vUv), 0.001, 0.999));
    } else {
        vec2 q = containSrc(vUv);
        float inside = step(0.0, q.x) * step(q.x, 1.0) * step(0.0, q.y) * step(q.y, 1.0);
        vec3 bg = uBgColor;
        if (uBgMode < 0.5) {
            vec2 b = coverSrc(vUv);
            vec3 s = vec3(0.0);
            for (int i = 0; i < 12; i++) {
                float a = float(i) * 0.5236;
                s += src(clamp(b + vec2(cos(a), sin(a)) * 0.045, 0.001, 0.999));
            }
            bg = s / 12.0 * 0.62;
        }
        c = mix(bg, src(clamp(q, 0.001, 0.999)), inside);
    }
    gl_FragColor = vec4(c, 1.0);
}
"""

    /** Photo (row 0 = top) into the project frame. uv: y up. */
    const val COMPOSE = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uSrc;
vec3 src(vec2 uv) { return texture2D(uSrc, vec2(uv.x, 1.0 - uv.y)).rgb; }
""" + COMPOSE_BODY

    /** Video frame (SurfaceTexture, with its transform) into the frame. */
    const val COMPOSE_OES = """#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUv;
uniform samplerExternalOES uSrc;
uniform mat4 uSt;
vec3 src(vec2 uv) { return texture2D(uSrc, (uSt * vec4(uv, 0.0, 1.0)).xy).rgb; }
""" + COMPOSE_BODY

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
float heart(vec2 p) { return smoothstep(-0.06, 0.04, heartD(p)); }
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

    // ================================================== step 4 library

    // ---------------- Love
    /** Pink Sparkle: pink glow grade, glitter stars twinkling in two sizes. */
    private const val PINK_SPARKLE = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.05 + 0.08 * e, vec2(0.0)));
    c = mix(c, c * vec3(1.08, 0.92, 1.04) + vec3(0.06, 0.01, 0.05), 0.8);
    c = c * 0.92 + 0.06;
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        vec2 g = vec2(vUv.x * uOutAspect, vUv.y) * (14.0 + fi * 12.0);
        g.y -= uTime * 0.15 * (1.0 + fi);
        vec2 cell = floor(g);
        float hs = hash(cell + fi * 9.0);
        if (hs > 0.88) {
            vec2 f = fract(g) - 0.5 - (vec2(hash(cell + 1.7), hash(cell + 4.3)) - 0.5) * 0.5;
            float tw = pow(0.5 + 0.5 * sin(uTime * (3.0 + 4.0 * hs) + hs * 60.0), 3.0);
            float st = max(0.0, 1.0 - abs(f.x) * 14.0 - abs(f.y) * 2.5) + max(0.0, 1.0 - abs(f.y) * 14.0 - abs(f.x) * 2.5);
            st += smoothstep(0.12, 0.0, length(f));
            c += vec3(1.0, 0.8, 0.92) * st * tw * 0.6;
        }
    }
    gl_FragColor = finish(c);
}
"""

    /** Love Frame: the whole photo placed inside a big heart that beats,
     *  pink glowing rim, a soft blurred dark-pink copy around it. */
    private const val LOVE_FRAME = COMMON + """
void main() {
    float b = fract(uTime * 1.1);
    float thump = exp(-b * 14.0) + 0.7 * step(0.22, b) * exp(-(b - 0.22) * 14.0);
    float grow = ease(uTime / 0.8);
    float size = 0.40 * mix(0.2, 1.0, grow) * (1.0 + 0.04 * thump);
    vec2 cen = vec2(0.5, 0.50);
    vec2 q = aspectD(vUv, cen) / size;
    float hd = heartD(q);
    float inside = smoothstep(-0.015, 0.015, hd);
    // Photo fitted to the heart's box (about 1.32 x 1.30 heart units),
    // keeping more of the top (faces).
    vec2 iuv = vec2(q.x / 1.32 + 0.5, (q.y + 0.75) / 1.30);
    float boxA = 1.32 / 1.30;
    vec2 tc = coverBox(iuv, boxA);
    if (uImgAspect < boxA) tc.y -= (1.0 - uImgAspect / boxA) * 0.35;
    vec3 p = photo(tc);
    vec2 btc = coverUv(vUv, 1.2, vec2(0.0));
    vec3 bg = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        bg += photo(btc + vec2(cos(a), sin(a)) * 0.03);
    }
    bg = mix(vec3(luma(bg / 8.0)), bg / 8.0, 0.5) * 0.4 + vec3(0.20, 0.03, 0.09);
    vec3 c = mix(bg, p, inside);
    float rim = exp(-abs(hd) * 28.0);
    c += vec3(1.0, 0.35, 0.6) * rim * (0.55 + 0.45 * thump);
    gl_FragColor = finish(c);
}
"""

    /** Petals: soft pink petals drifting down and flipping. */
    private const val PETALS = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.05 + 0.08 * e, vec2(0.0)));
    c = mix(c, c * vec3(1.05, 0.96, 0.98) + vec3(0.04, 0.01, 0.02), 0.7);
    for (int i = 0; i < 16; i++) {
        float fi = float(i);
        float sp = 0.05 + 0.05 * hash(vec2(fi, 1.0));
        float y = 1.1 - fract(hash(vec2(fi, 2.0)) + uTime * sp) * 1.25;
        float x = fract(hash(vec2(fi, 3.0)) + uTime * 0.03) + 0.05 * sin(uTime + fi);
        vec2 q = aspectD(vUv, vec2(x, y));
        float a = uTime * (1.0 + hash(vec2(fi, 4.0))) + fi;
        q = vec2(cos(a) * q.x + sin(a) * q.y, -sin(a) * q.x + cos(a) * q.y);
        float sz = 0.016 + 0.012 * hash(vec2(fi, 5.0));
        q /= sz;
        q.x /= 0.25 + 0.75 * abs(cos(uTime * 2.0 + fi));
        q.y *= 1.6;
        float m = smoothstep(1.0, 0.75, length(q));
        vec3 col = mix(vec3(1.0, 0.55, 0.7), vec3(1.0, 0.88, 0.92), clamp(0.5 + 0.5 * q.y, 0.0, 1.0));
        c = mix(c, col, m * 0.9);
    }
    gl_FragColor = finish(c);
}
"""

    // ---------------- Beat
    /** Shake: on every beat the camera shakes with motion blur, then calms. */
    private const val SHAKE = COMMON + """
void main() {
    float fr = fract(uTime * 2.0);
    float n = floor(uTime * 2.0);
    float amp = exp(-fr * 6.0);
    vec2 dir = normalize(vec2(hash(vec2(n, 1.0)) - 0.5, hash(vec2(n, 2.0)) - 0.5) + 0.001);
    vec2 off = dir * sin(fr * 40.0) * 0.03 * amp;
    vec3 c = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        float fi = float(i) / 5.0;
        c += photo(coverUv(vUv + off * (0.3 + fi), 1.12 + 0.03 * amp, vec2(0.0)));
    }
    c /= 6.0;
    c = (c - 0.5) * (1.0 + 0.2 * amp) + 0.5;
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 0.7;
    gl_FragColor = finish(c);
}
"""

    /** Split: on each beat the frame splits into 1, 2, 3 then 4 panels. */
    private const val SPLIT = COMMON + """
void main() {
    float bt = uTime * 2.0;
    float n = mod(floor(bt), 4.0);
    float fr = fract(bt);
    vec2 grid = vec2(1.0, 1.0);
    if (n > 0.5) grid = vec2(1.0, 2.0);
    if (n > 1.5) grid = vec2(1.0, 3.0);
    if (n > 2.5) grid = vec2(2.0, 2.0);
    vec2 g = vUv * grid;
    vec2 cell = floor(g);
    vec2 iuv = fract(g);
    float pa = uOutAspect * grid.y / grid.x;
    float z = 1.0 + 0.08 * exp(-fr * 5.0) + 0.04 * hash(cell + n);
    vec3 c = photo(coverBox((iuv - 0.5) / z + 0.5, pa));
    float dx = min(iuv.x, 1.0 - iuv.x) * uRes.x / grid.x;
    float dy = min(iuv.y, 1.0 - iuv.y) * uRes.y / grid.y;
    float edge = 1.0 - smoothstep(2.0, 4.0, min(dx, dy));
    c = mix(c, vec3(1.0), edge * step(1.5, grid.x * grid.y));
    c += exp(-fr * 10.0) * 0.25;
    gl_FragColor = finish(c);
}
"""

    /** Zoom Blur: a burst of radial zoom blur and light on every beat. */
    private const val ZOOM_BLUR = COMMON + """
void main() {
    float fr = fract(uTime * 1.5);
    float amp = exp(-fr * 5.0);
    vec3 c = vec3(0.0);
    for (int i = 0; i < 10; i++) {
        float s = 1.0 - amp * 0.12 * float(i) / 9.0;
        c += photo(coverUv((vUv - 0.5) * s + 0.5, 1.06 + 0.10 * amp, vec2(0.0)));
    }
    c /= 10.0;
    c += amp * 0.15;
    c = (c - 0.5) * 1.1 + 0.5;
    gl_FragColor = finish(c);
}
"""

    // ---------------- Cinema
    /** Letterbox: black movie bars slide in, cool film grade, slow side pan. */
    private const val LETTERBOX = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    float bars = ease(uTime / 1.0) * 0.12;
    vec3 c = photo(coverUv(vUv, 1.15, vec2(mix(-0.04, 0.04, e), 0.0)));
    float l = luma(c);
    c = mix(vec3(l), c, 0.75);
    c = mix(c * vec3(0.85, 1.0, 1.06), c * vec3(1.1, 1.0, 0.86), l);
    c = (c - 0.5) * 1.12 + 0.48;
    c *= step(bars, vUv.y) * step(vUv.y, 1.0 - bars);
    gl_FragColor = finish(c);
}
"""

    /** Ken Burns: the classic slow zoom + diagonal glide across the photo. */
    private const val KEN_BURNS = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, mix(1.30, 1.10, e), vec2(mix(-0.05, 0.05, e), mix(0.04, -0.03, e))));
    c = (c - 0.5) * 1.05 + 0.5;
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 0.6;
    gl_FragColor = finish(c);
}
"""

    /** Light Leak: warm orange and pink light blobs drifting over the photo. */
    private const val LIGHT_LEAK = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.05 + 0.07 * e, vec2(0.0)));
    vec2 p1 = vec2(-0.2 + 1.4 * fract(uTime * 0.09), 0.75 + 0.15 * sin(uTime * 0.5));
    vec2 p2 = vec2(1.2 - 1.4 * fract(uTime * 0.07 + 0.4), 0.25 + 0.1 * cos(uTime * 0.6));
    float d1 = length(aspectD(vUv, p1));
    float d2 = length(aspectD(vUv, p2));
    vec3 leak = vec3(1.0, 0.45, 0.15) * exp(-d1 * d1 * 9.0) + vec3(1.0, 0.2, 0.6) * exp(-d2 * d2 * 9.0);
    c = 1.0 - (1.0 - c) * (1.0 - leak * 0.75);
    gl_FragColor = finish(c);
}
"""

    // ---------------- Feel
    /** Color Return: starts black & white, colour spreads out from the
     *  middle. */
    private const val COLOR_RETURN = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.05 + 0.08 * e, vec2(0.0)));
    float k = ease((uTime - 0.8) / (uDur * 0.6));
    float reach = k * 0.75;
    float r = length(aspectD(vUv, vec2(0.5)));
    float m = 1.0 - smoothstep(reach - 0.15, reach, r);
    m *= step(0.001, k);
    vec3 bw = vec3((luma(c) - 0.5) * 1.1 + 0.5);
    c = mix(bw, c * 1.04, m);
    gl_FragColor = finish(c);
}
"""

    /** Fog: soft mist drifting across, stronger near the bottom. */
    private const val FOG = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.05 + 0.06 * e, vec2(0.0)));
    c = mix(vec3(luma(c)), c, 0.8) * vec3(0.95, 1.0, 1.03);
    float f = fbm(vec2(vUv.x * uOutAspect * 3.0 + uTime * 0.08, vUv.y * 3.0 - uTime * 0.02));
    float f2 = fbm(vec2(vUv.x * uOutAspect * 6.0 - uTime * 0.05, vUv.y * 5.0) + 7.0);
    float amt = smoothstep(0.35, 0.8, f * 0.7 + f2 * 0.4) * (0.45 + 0.55 * (1.0 - vUv.y));
    c = mix(c, vec3(0.86, 0.89, 0.93), amt * 0.65);
    gl_FragColor = finish(c);
}
"""

    /** Lonely: a slow pull back, faded cool colours, the dark closes in. */
    private const val LONELY = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, mix(1.30, 1.02, e), vec2(0.0, 0.01 * e)));
    c = mix(vec3(luma(c)), c, 0.35) * vec3(0.88, 0.95, 1.08);
    c *= 0.95 + 0.05 * sin(uTime * 1.2);
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * mix(0.6, 2.2, e);
    gl_FragColor = finish(c);
}
"""

    // ---------------- Night
    /** Fireflies: night tint, warm glowing dots wandering and blinking. */
    private const val FIREFLIES = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.05 + 0.06 * e, vec2(0.0)));
    c = c * 0.55 * vec3(0.82, 0.95, 1.12);
    for (int i = 0; i < 18; i++) {
        float fi = float(i);
        vec2 p = vec2(hash(vec2(fi, 1.0)), hash(vec2(fi, 2.0)) * 0.9 + 0.05);
        p += 0.08 * vec2(sin(uTime * 0.5 * (hash(vec2(fi, 3.0)) + 0.5) + fi), cos(uTime * 0.4 * (hash(vec2(fi, 4.0)) + 0.5) + fi * 1.3));
        float blink = 0.5 + 0.5 * sin(uTime * (1.5 + 2.0 * hash(vec2(fi, 5.0))) + fi * 3.0);
        float d = length(aspectD(vUv, p));
        float g = exp(-d * d * 6000.0) + exp(-d * d * 300.0) * 0.35;
        c += vec3(1.0, 0.88, 0.4) * g * blink;
    }
    gl_FragColor = finish(c);
}
"""

    /** Starry Night: deep blue night, stars twinkling in the dark parts,
     *  a shooting star every few seconds. */
    private const val STARRY = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 p = photo(coverUv(vUv, 1.04 + 0.06 * e, vec2(0.0)));
    vec3 c = p * vec3(0.6, 0.7, 1.0) * 0.8 + vec3(0.0, 0.02, 0.08);
    float dark = (1.0 - luma(p)) * smoothstep(0.25, 0.85, vUv.y);
    vec2 g = vec2(vUv.x * uOutAspect, vUv.y) * 40.0;
    vec2 cell = floor(g);
    float hs = hash(cell);
    if (hs > 0.92) {
        vec2 f = fract(g) - 0.5 - (vec2(hash(cell + 2.0), hash(cell + 5.0)) - 0.5) * 0.6;
        float tw = 0.4 + 0.6 * pow(0.5 + 0.5 * sin(uTime * (2.0 + 3.0 * hs) + hs * 50.0), 2.0);
        c += vec3(0.9, 0.95, 1.0) * smoothstep(0.09, 0.0, length(f)) * tw * (0.25 + dark) * 1.4;
    }
    float sn = floor(uTime / 3.0);
    float sk = fract(uTime / 3.0) / 0.3;
    if (sk < 1.0) {
        vec2 a = vec2(0.2 + 0.6 * hash(vec2(sn, 1.0)), 0.95);
        vec2 dir = normalize(vec2(0.6, -0.35));
        vec2 head = a + dir * sk * 0.5;
        vec2 q = aspectD(vUv, head);
        vec2 dd = dir * vec2(uOutAspect, 1.0);
        float along = dot(q, -normalize(dd));
        float side = abs(dot(q, vec2(-normalize(dd).y, normalize(dd).x)));
        float tail = smoothstep(0.15, 0.0, along) * step(0.0, along) * (1.0 - smoothstep(0.0, 0.004, side));
        c += vec3(1.0) * tail * (1.0 - sk);
    }
    gl_FragColor = finish(c);
}
"""

    // ---------------- Fun
    /** Confetti: colourful paper pieces raining down and fluttering. */
    private const val CONFETTI = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.05 + 0.06 * e, vec2(0.0)));
    c = (c - 0.5) * 1.06 + 0.52;
    for (int i = 0; i < 40; i++) {
        float fi = float(i);
        float sp = 0.10 + 0.08 * hash(vec2(fi, 1.0));
        float y = 1.1 - fract(hash(vec2(fi, 2.0)) + uTime * sp) * 1.25;
        float x = hash(vec2(fi, 3.0)) + 0.04 * sin(uTime * 2.0 + fi);
        vec2 q = aspectD(vUv, vec2(x, y));
        float a = uTime * (2.0 + 2.0 * hash(vec2(fi, 4.0))) + fi;
        q = vec2(cos(a) * q.x + sin(a) * q.y, -sin(a) * q.x + cos(a) * q.y);
        float fl = abs(cos(uTime * 4.0 + fi * 1.7));
        float m = step(abs(q.x), 0.013) * step(abs(q.y), 0.008 * fl + 0.0015);
        vec3 col = clamp(hueShift(vec3(1.0, 0.25, 0.3), hash(vec2(fi, 5.0)) * 6.283), 0.0, 1.0);
        c = mix(c, col * (0.7 + 0.3 * fl), m);
    }
    gl_FragColor = finish(c);
}
"""

    /** Mirror: on each beat the photo flips into a new mirror pattern. */
    private const val MIRROR = COMMON + """
void main() {
    float n = mod(floor(uTime * 1.0), 4.0);
    float fr = fract(uTime * 1.0);
    vec2 uv = vUv;
    if (n > 0.5 && n < 1.5) uv.x = 0.5 - abs(uv.x - 0.5);
    if (n > 1.5 && n < 2.5) uv.y = 0.5 + abs(uv.y - 0.5);
    if (n > 2.5) uv = vec2(0.5 - abs(uv.x - 0.5), 0.5 + abs(uv.y - 0.5));
    vec3 c = photo(coverUv(uv, 1.08 + 0.04 * exp(-fr * 6.0), vec2(0.0)));
    c += exp(-fr * 10.0) * 0.25;
    gl_FragColor = finish(c);
}
"""

    /** Comic: bold colours, black outlines and halftone dots, punch on
     *  the beat. */
    private const val COMIC = COMMON + """
float L(vec2 uv, float z) { return luma(photo(coverUv(uv, z, vec2(0.0)))); }
void main() {
    float fr = fract(uTime * 1.0);
    float z = 1.06 + 0.06 * exp(-fr * 6.0);
    vec3 c = photo(coverUv(vUv, z, vec2(0.0)));
    c = mix(vec3(luma(c)), c, 1.5);
    c = floor(clamp(c, 0.0, 1.0) * 4.0 + 0.5) / 4.0;
    vec2 px = 1.5 / uRes;
    float gx = L(vUv + vec2(px.x, 0.0), z) - L(vUv - vec2(px.x, 0.0), z);
    float gy = L(vUv + vec2(0.0, px.y), z) - L(vUv - vec2(0.0, px.y), z);
    float edge = smoothstep(0.06, 0.16, length(vec2(gx, gy)));
    vec2 hp = vUv * uRes / 7.0;
    hp = vec2(hp.x + hp.y, hp.y - hp.x) * 0.7071;
    float l = luma(c);
    float dotm = smoothstep(0.05, 0.0, length(fract(hp) - 0.5) - (1.0 - l) * 0.45);
    c *= 1.0 - dotm * 0.35;
    c = mix(c, vec3(0.05), edge);
    gl_FragColor = finish(c);
}
"""

    /** Rainbow: waves of colour sweeping across the photo. */
    private const val RAINBOW = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 c = photo(coverUv(vUv, 1.05 + 0.06 * e, vec2(0.0)));
    float w = sin((vUv.x * 0.8 + vUv.y) * 4.0 - uTime * 2.0);
    c = mix(c, clamp(hueShift(c, w * 1.2 + uTime * 0.6), 0.0, 1.0), 0.65);
    c = mix(vec3(luma(c)), c, 1.25);
    gl_FragColor = finish(c);
}
"""

    /** Disco: dark room, coloured spotlights sweeping, mirror-ball dots,
     *  flash on the beat. */
    private const val DISCO = COMMON + """
void main() {
    vec3 p = photo(coverUv(vUv, 1.06, vec2(0.0)));
    vec3 c = p * 0.35 * vec3(0.9, 0.8, 1.1);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        vec2 sp = vec2(0.5 + 0.38 * sin(uTime * (0.7 + 0.2 * fi) + fi * 2.1), 0.5 + 0.35 * cos(uTime * (0.6 + 0.15 * fi) + fi * 1.3));
        vec3 col = clamp(hueShift(vec3(1.0, 0.2, 0.4), fi * 2.1 + uTime * 0.5), 0.0, 1.0);
        float d = length(aspectD(vUv, sp));
        float spot = smoothstep(0.22, 0.12, d);
        c += p * col * spot * 1.6 + col * spot * 0.08;
    }
    vec2 q = (vUv - 0.5) * vec2(uOutAspect, 1.0);
    float a = uTime * 0.4;
    q = vec2(cos(a) * q.x + sin(a) * q.y, -sin(a) * q.x + cos(a) * q.y) * 26.0;
    vec2 cell = floor(q);
    if (hash(cell) > 0.9) c += vec3(1.0) * smoothstep(0.18, 0.0, length(fract(q) - 0.5)) * 0.35;
    float fr = fract(uTime * 2.0);
    c += exp(-fr * 9.0) * 0.12;
    gl_FragColor = finish(c);
}
"""

    // ---------------- Retro
    /** VHS: tape colour bleed, wobbly lines, a tracking band rolling down,
     *  noise at the bottom. */
    private const val VHS = COMMON + """
void main() {
    float t = uTime;
    vec2 uv = vUv;
    float bandY = fract(1.0 - t * 0.15);
    float band = smoothstep(0.035, 0.0, abs(uv.y - bandY));
    uv.x += (hash(vec2(floor(uv.y * 240.0), floor(t * 30.0))) - 0.5) * 0.003;
    uv.x += band * (hash(vec2(floor(t * 30.0), floor(uv.y * 90.0))) - 0.5) * 0.04;
    float z = 1.04;
    float r = photo(coverUv(uv + vec2(0.005, 0.0), z, vec2(0.0))).r;
    float g = photo(coverUv(uv, z, vec2(0.0))).g;
    float b = photo(coverUv(uv - vec2(0.005, 0.0), z, vec2(0.0))).b;
    vec3 c = vec3(r, g, b);
    c = mix(vec3(luma(c)), c, 0.8) * vec3(1.05, 0.98, 1.06) + 0.03;
    c *= 0.93 + 0.07 * sin(vUv.y * uRes.y * 1.5708);
    c += band * hash(vUv * vec2(400.0, 80.0) + t) * 0.45;
    c = mix(c, vec3(hash(vUv * vec2(300.0, 50.0) + t * 3.0)), smoothstep(0.035, 0.0, vUv.y) * 0.8);
    gl_FragColor = finish(c);
}
"""

    /** Old TV: switches on like an old set, curved glass, scanlines, RGB
     *  dots, flicker. */
    private const val OLD_TV = COMMON + """
void main() {
    float on = ease(uTime / 0.6);
    vec2 q = vUv - 0.5;
    q.y /= max(on, 0.02);
    vec2 cq = q * (1.0 + dot(q, q) * 0.28);
    vec2 uv = cq + 0.5;
    float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
    vec3 c = photo(coverUv(uv, 1.02, vec2(0.0)));
    c *= 0.82 + 0.18 * sin(uv.y * uRes.y * 1.2);
    float col = mod(floor(vUv.x * uRes.x), 3.0);
    vec3 m = vec3(0.85);
    if (col < 0.5) m.r = 1.1; else if (col < 1.5) m.g = 1.1; else m.b = 1.1;
    c *= m;
    c *= 0.94 + 0.06 * hash(vec2(floor(uTime * 20.0), 1.0));
    vec2 eg = min(uv, 1.0 - uv);
    c *= smoothstep(0.0, 0.03, min(eg.x, eg.y));
    vec2 d = uv - 0.5;
    c *= 1.0 - dot(d, d) * 1.2;
    c *= inside;
    c += (1.0 - on) * smoothstep(0.02, 0.0, abs(vUv.y - 0.5)) * 1.5;
    gl_FragColor = finish(c);
}
"""

    /** Pixel: the photo breaks into big 8-bit pixels on the beat and
     *  sharpens again. */
    private const val PIXEL = COMMON + """
void main() {
    float fr = fract(uTime * 0.75);
    float size = 1.0 + 46.0 * exp(-fr * 3.5);
    vec2 cellPx = floor(vUv * uRes / size);
    vec2 uv = (cellPx + 0.5) * size / uRes;
    vec3 c = photo(coverUv(uv, 1.05, vec2(0.0)));
    float big = smoothstep(4.0, 12.0, size);
    c = mix(c, floor(c * 6.0 + 0.5) / 6.0, big);
    c = mix(vec3(luma(c)), c, 1.0 + 0.3 * big);
    gl_FragColor = finish(c);
}
"""

    // ================================================== step 5 templates

    /** Spin (Beat): on every beat the picture twists a little and snaps
     *  back. */
    private const val SPIN = COMMON + """
void main() {
    float bt = uTime * 2.0;
    float n = floor(bt);
    float fr = fract(bt);
    float dirS = mod(n, 2.0) < 0.5 ? 1.0 : -1.0;
    float ang = dirS * 0.16 * exp(-fr * 5.0) * sin(fr * 3.14159 + 1.2);
    vec2 q = (vUv - 0.5) * vec2(uOutAspect, 1.0);
    q = vec2(cos(ang) * q.x - sin(ang) * q.y, sin(ang) * q.x + cos(ang) * q.y);
    vec2 uv = q / vec2(uOutAspect, 1.0) + 0.5;
    vec3 c = photo(coverUv(uv, 1.22 + 0.06 * exp(-fr * 6.0), vec2(0.0)));
    c = (c - 0.5) * 1.08 + 0.5;
    c += exp(-fr * 12.0) * 0.12;
    gl_FragColor = finish(c);
}
"""

    /** Dolly Zoom (Cinema): the "vertigo" movie shot - the middle comes
     *  closer while the edges stretch away. */
    private const val VERTIGO = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    float k = 0.55 * sin(e * 3.14159);
    vec2 q = (vUv - 0.5) * vec2(uOutAspect, 1.0);
    float r2 = dot(q, q);
    vec2 uv = q * (1.0 - k * 0.45 + k * r2 * 2.2) / vec2(uOutAspect, 1.0) + 0.5;
    vec3 c = photo(coverUv(uv, 1.1, vec2(0.0)));
    float l = luma(c);
    c = mix(c * vec3(0.92, 1.0, 1.06), c * vec3(1.06, 1.0, 0.9), l);
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 0.9;
    gl_FragColor = finish(c);
}
"""

    /** Aurora (Night): night colours and green / violet northern lights
     *  waving across the top. */
    private const val AURORA = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 p = photo(coverUv(vUv, 1.04 + 0.05 * e, vec2(0.0)));
    vec3 c = p * 0.6 * vec3(0.75, 0.85, 1.1);
    float x = vUv.x * uOutAspect * 2.0;
    float band = fbm(vec2(x * 1.5 + uTime * 0.1, uTime * 0.05));
    float y0 = 0.74 + 0.14 * (band - 0.5) * 2.0;
    float folds = 0.55 + 0.45 * sin(x * 18.0 + fbm(vec2(x * 3.0, uTime * 0.2)) * 6.0);
    float curtain = exp(-pow((vUv.y - y0) * 7.0, 2.0)) * folds;
    curtain += exp(-pow((vUv.y - y0 - 0.08) * 4.0, 2.0)) * 0.35;
    vec3 col = mix(vec3(0.1, 1.0, 0.6), vec3(0.65, 0.3, 1.0), smoothstep(y0 - 0.05, y0 + 0.15, vUv.y));
    c += col * curtain * (0.35 + 0.6 * (1.0 - luma(p)));
    gl_FragColor = finish(c);
}
"""

    /** Kaleido (Fun): the photo folds into a slowly turning kaleidoscope. */
    private const val KALEIDO = COMMON + """
void main() {
    vec2 q = (vUv - 0.5) * vec2(uOutAspect, 1.0);
    float r = length(q);
    float seg = 6.2832 / 6.0;
    float a = atan(q.y, q.x) + uTime * 0.25;
    a = mod(a, seg);
    a = abs(a - seg * 0.5);
    vec2 kq = vec2(cos(a), sin(a)) * r;
    vec2 kuv = kq / vec2(uOutAspect, 1.0) * 0.9 + 0.5;
    float k = ease((uTime - 0.4) / 1.2);
    vec2 uv = mix(vUv, kuv, k);
    vec3 c = photo(coverUv(uv, 1.05, vec2(0.0)));
    c = mix(vec3(luma(c)), c, 1.2);
    vec2 d = vUv - 0.5;
    c *= 1.0 - dot(d, d) * 0.8;
    gl_FragColor = finish(c);
}
"""

    /** Pop Art (Art): four panels, each in bold poster colours that swap
     *  on the beat. */
    private const val POP_ART = COMMON + """
vec3 pal(float i, float lvl) {
    vec3 a; vec3 b; vec3 c;
    if (i < 0.5) { a = vec3(0.95, 0.15, 0.45); b = vec3(1.0, 0.85, 0.1); c = vec3(0.1, 0.1, 0.35); }
    else if (i < 1.5) { a = vec3(0.1, 0.75, 0.95); b = vec3(1.0, 0.45, 0.75); c = vec3(0.2, 0.05, 0.3); }
    else if (i < 2.5) { a = vec3(1.0, 0.55, 0.1); b = vec3(0.4, 1.0, 0.5); c = vec3(0.05, 0.15, 0.3); }
    else { a = vec3(0.6, 0.3, 1.0); b = vec3(0.3, 1.0, 0.95); c = vec3(0.3, 0.05, 0.1); }
    if (lvl < 0.5) return c;
    if (lvl < 1.5) return a;
    return b;
}
void main() {
    vec2 g = vUv * 2.0;
    vec2 cell = floor(g);
    vec2 iuv = fract(g);
    vec3 p = photo(coverBox((iuv - 0.5) / 1.04 + 0.5, uOutAspect));
    float l = luma(p);
    float lvl = step(0.32, l) + step(0.6, l);
    float idx = mod(cell.x + cell.y * 2.0 + floor(uTime * 2.0), 4.0);
    vec3 c = pal(idx, lvl);
    float dx = min(iuv.x, 1.0 - iuv.x) * uRes.x * 0.5;
    float dy = min(iuv.y, 1.0 - iuv.y) * uRes.y * 0.5;
    c = mix(c, vec3(1.0), 1.0 - smoothstep(2.0, 4.0, min(dx, dy)));
    gl_FragColor = finish(c);
}
"""

    /** Sketch (Art): a pencil drawing on paper that slowly fills with the
     *  photo's colours. */
    private const val SKETCH = COMMON + """
float L(vec2 uv) { return luma(photo(coverUv(uv, 1.05, vec2(0.0)))); }
void main() {
    vec2 px = 1.0 / uRes;
    float l = L(vUv);
    float s1 = 0.0;
    float s2 = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        vec2 o = vec2(cos(a), sin(a));
        s1 += L(vUv + o * px * 1.5);
        s2 += L(vUv + o * px * 4.5);
    }
    s1 /= 8.0;
    s2 /= 8.0;
    float line = smoothstep(0.01, 0.07, s2 - s1);
    float hatch = step(0.55, fract((vUv.x * uRes.x + vUv.y * uRes.y) / 7.0)) * (1.0 - smoothstep(0.15, 0.45, l));
    vec3 paper = vec3(0.97, 0.95, 0.91) * (0.97 + 0.03 * hash(floor(vUv * uRes / 2.0)));
    vec3 sk = paper * (1.0 - line * 0.85) * (1.0 - hatch * 0.25);
    vec3 col = photo(coverUv(vUv, 1.05, vec2(0.0)));
    float k = ease((uTime - uDur * 0.45) / 2.0) * 0.75;
    vec3 c = mix(sk, sk * col * 1.15, k);
    gl_FragColor = finish(c);
}
"""

    /** Duotone (Art): the photo in two strong colours that slowly change. */
    private const val DUOTONE = COMMON + """
void main() {
    float e = ease(uTime / uDur);
    vec3 p = photo(coverUv(vUv, 1.05 + 0.08 * e, vec2(0.0)));
    float l = clamp((luma(p) - 0.5) * 1.3 + 0.5, 0.0, 1.0);
    vec3 dark = clamp(hueShift(vec3(0.15, 0.05, 0.45), uTime * 0.25), 0.0, 1.0);
    vec3 light = clamp(hueShift(vec3(1.0, 0.55, 0.45), uTime * 0.25), 0.0, 1.0);
    vec3 c = mix(dark, light, l);
    gl_FragColor = finish(c);
}
"""

    // ================================================== step 5 EFFECTS
    // Made to be stacked on any template: no zoom, no colour grade.

    private const val FX_SPARKLE = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        vec2 g = vec2(vUv.x * uOutAspect, vUv.y) * (12.0 + fi * 10.0);
        g.y -= uTime * 0.12 * (1.0 + fi);
        vec2 cell = floor(g);
        float hs = hash(cell + fi * 9.0);
        if (hs > 0.86) {
            vec2 f = fract(g) - 0.5 - (vec2(hash(cell + 1.7), hash(cell + 4.3)) - 0.5) * 0.5;
            float tw = pow(0.5 + 0.5 * sin(uTime * (3.0 + 4.0 * hs) + hs * 60.0), 3.0);
            float st = max(0.0, 1.0 - abs(f.x) * 14.0 - abs(f.y) * 2.5) + max(0.0, 1.0 - abs(f.y) * 14.0 - abs(f.x) * 2.5);
            st += smoothstep(0.12, 0.0, length(f));
            c += vec3(1.0, 0.92, 0.75) * st * tw * 0.7;
        }
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_HEARTS = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 14; i++) {
        float fi = float(i);
        float sp = 0.07 + 0.06 * hash(vec2(fi, 2.0));
        float y = 1.1 - fract(hash(vec2(fi, 1.0)) + uTime * sp) * 1.25;
        vec2 p = vec2(hash(vec2(fi, 3.0)) + 0.04 * sin(uTime * 1.5 + fi), y);
        float s = 0.03 + 0.03 * hash(vec2(fi, 4.0));
        vec2 q = aspectD(vUv, p) / s;
        float a = 0.4 * sin(uTime * 2.0 + fi);
        q = vec2(cos(a) * q.x - sin(a) * q.y, sin(a) * q.x + cos(a) * q.y);
        float m = smoothstep(-0.06, 0.04, heartD(q * 0.8));
        vec3 col = mix(vec3(1.0, 0.2, 0.35), vec3(1.0, 0.55, 0.75), hash(vec2(fi, 6.0)));
        c = mix(c, col, m * 0.8);
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_SNOW = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
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

    private const val FX_RAIN = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0))) * 0.94;
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        float cols = 70.0 + 40.0 * fi;
        vec2 q = vUv + vec2(vUv.y * 0.12, 0.0);
        float col = floor(q.x * cols);
        float lx = fract(q.x * cols) - 0.5;
        float on = step(0.45, hash(vec2(col, fi)));
        float y = fract(q.y * (1.5 + fi) + uTime * (1.4 + 0.8 * hash(vec2(col, 2.0 + fi))) + hash(vec2(col, 5.0)) * 9.0);
        float line = (1.0 - smoothstep(0.0, 0.14, abs(lx))) * smoothstep(0.0, 0.05, y) * (1.0 - smoothstep(0.12, 0.3, y));
        c += vec3(0.8, 0.88, 1.0) * line * on * (0.2 + 0.1 * fi);
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_BOKEH = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 14; i++) {
        float fi = float(i);
        vec2 p = vec2(fract(hash(vec2(fi, 1.0)) + uTime * 0.015 * (hash(vec2(fi, 7.0)) - 0.5)),
                      fract(hash(vec2(fi, 2.0)) + uTime * (0.01 + 0.02 * hash(vec2(fi, 3.0)))));
        float r = 0.035 + 0.05 * hash(vec2(fi, 4.0));
        float d = length(aspectD(vUv, p));
        float disc = smoothstep(r, r * 0.85, d);
        float ring = smoothstep(r * 0.75, r * 0.95, d) * disc;
        vec3 col = clamp(hueShift(vec3(1.0, 0.6, 0.3), hash(vec2(fi, 5.0)) * 6.283), 0.0, 1.0);
        float tw = 0.6 + 0.4 * sin(uTime * (0.8 + hash(vec2(fi, 6.0))) + fi);
        c += col * (disc * 0.22 + ring * 0.25) * tw;
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_DUST = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    float tt = floor(uTime * 18.0);
    c += (hash(vUv * vec2(731.0, 397.0) + fract(uTime * 7.13) * 100.0) - 0.5) * 0.10;
    c *= 0.95 + 0.05 * hash(vec2(tt, 9.0));
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        if (hash(vec2(tt, 7.0 + fi)) > 0.5) {
            float dx = abs(vUv.x - hash(vec2(tt, 11.0 + fi)));
            c += (1.0 - smoothstep(0.0, 0.0015, dx)) * 0.28;
        }
    }
    vec2 g = vUv * vec2(48.0, 85.0);
    vec2 cell = floor(g);
    if (hash(cell + tt * 1.37) > 0.998) {
        float dd = length(fract(g) - 0.5);
        c *= mix(0.3, 1.0, smoothstep(0.1, 0.38, dd));
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_RAYS = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    vec2 src = vec2(0.12 + 0.1 * sin(uTime * 0.3), 1.12);
    vec2 d = aspectD(vUv, src);
    float ang = atan(d.x, -d.y);
    float r = length(d);
    float rays = (0.5 + 0.5 * sin(ang * 22.0 + uTime * 0.8)) * (0.5 + 0.5 * sin(ang * 13.0 - uTime * 0.5));
    rays = pow(rays, 2.5) * exp(-r * 1.3) * smoothstep(0.0, 0.3, r);
    c += vec3(1.0, 0.9, 0.7) * rays * 0.55;
    c += vec3(1.0, 0.85, 0.6) * exp(-r * r * 8.0) * 0.25;
    gl_FragColor = finish(c);
}
"""

    private const val FX_FLARE = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    float k = uTime / uDur;
    vec2 src = vec2(mix(0.15, 0.85, k), 0.78 + 0.08 * sin(k * 3.14159));
    float ds = length(aspectD(vUv, src));
    c += vec3(1.0, 0.85, 0.65) * (exp(-ds * ds * 60.0) * 0.8 + exp(-ds * 4.0) * 0.12);
    float streak = exp(-abs(vUv.y - src.y) * 220.0) * exp(-abs(vUv.x - src.x) * 2.5);
    c += vec3(0.6, 0.8, 1.0) * streak * 0.5;
    for (int i = 1; i < 6; i++) {
        float fi = float(i);
        vec2 gp = src + (vec2(0.5) - src) * (0.4 + fi * 0.35);
        float dg = length(aspectD(vUv, gp));
        float r = 0.012 + 0.012 * fi;
        vec3 col = mix(vec3(1.0, 0.5, 0.3), vec3(0.4, 0.8, 1.0), fi / 5.0);
        c += col * smoothstep(r, r * 0.5, dg) * 0.1;
        c += col * smoothstep(r * 1.15, r, dg) * smoothstep(r * 0.85, r, dg) * 0.12;
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_BUBBLES = COMMON + """
void main() {
    vec2 off = vec2(0.0);
    float rim = 0.0;
    float hi = 0.0;
    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float y = -0.1 + fract(hash(vec2(fi, 1.0)) + uTime * (0.05 + 0.04 * hash(vec2(fi, 2.0)))) * 1.25;
        vec2 p = vec2(hash(vec2(fi, 3.0)) + 0.03 * sin(uTime * 1.7 + fi * 2.0), y);
        float r = 0.025 + 0.03 * hash(vec2(fi, 4.0));
        vec2 q = aspectD(vUv, p);
        float d = length(q);
        float inside = smoothstep(r, r * 0.92, d);
        off += -q * inside * 0.6;
        rim += smoothstep(r * 0.8, r * 0.98, d) * inside;
        hi += smoothstep(r * 0.3, 0.0, length(q - vec2(-0.35, 0.4) * r)) * inside;
    }
    vec3 c = photo(coverUv(vUv + off / vec2(uOutAspect, 1.0), 1.0, vec2(0.0)));
    c += vec3(0.75, 0.9, 1.0) * rim * 0.45 + vec3(1.0) * hi * 0.6;
    gl_FragColor = finish(c);
}
"""

    private const val FX_FILM = COMMON + """
void main() {
    float jit = (hash(vec2(floor(uTime * 12.0), 3.0)) - 0.5) * 0.002;
    vec3 c = photo(coverUv(vUv + vec2(0.0, jit), 1.0, vec2(0.0)));
    float sw = 0.075;
    float side = step(vUv.x, sw) + step(1.0 - sw, vUv.x);
    float cx = vUv.x < 0.5 ? sw * 0.5 : 1.0 - sw * 0.5;
    float y = fract(vUv.y * 22.0 + uTime * 1.6);
    vec2 h = vec2(abs(vUv.x - cx) / (sw * 0.28), abs(y - 0.5) / 0.26);
    float hole = 1.0 - smoothstep(0.75, 1.0, length(max(h - 0.4, 0.0)) / 0.6 + 0.4 * step(0.0, -1.0));
    c = mix(c, vec3(0.04), side);
    c = mix(c, vec3(0.88, 0.86, 0.8), side * hole);
    float edgeL = smoothstep(0.004, 0.0, abs(vUv.x - sw)) + smoothstep(0.004, 0.0, abs(vUv.x - (1.0 - sw)));
    c = mix(c, vec3(0.0), edgeL * 0.6);
    gl_FragColor = finish(c);
}
"""

    private const val FX_FIREWORKS = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int b = 0; b < 2; b++) {
        float bi = floor(uTime / 1.2) - float(b);
        float bt = uTime - bi * 1.2;
        if (bi < 0.0) continue;
        vec2 cen = vec2(0.2 + 0.6 * hash(vec2(bi, 1.0)), 0.55 + 0.35 * hash(vec2(bi, 2.0)));
        vec3 col = clamp(hueShift(vec3(1.0, 0.25, 0.3), hash(vec2(bi, 3.0)) * 6.283), 0.0, 1.0) + 0.2;
        float fade = clamp(1.0 - bt / 1.7, 0.0, 1.0);
        for (int i = 0; i < 24; i++) {
            float fi = float(i);
            float a = fi / 24.0 * 6.2832 + hash(vec2(bi, 4.0));
            float sp = 0.22 + 0.08 * hash(vec2(bi, fi));
            float dist = sp * bt * (1.0 - 0.3 * bt);
            vec2 p = cen + vec2(cos(a) / uOutAspect, sin(a)) * dist;
            p.y -= 0.07 * bt * bt;
            float d = length(aspectD(vUv, p));
            float g = exp(-d * d * 20000.0) + exp(-d * d * 1500.0) * 0.25;
            c += col * g * fade * 1.3;
        }
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_BALLOONS = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float x = 0.1 + 0.8 * hash(vec2(fi, 1.0)) + 0.02 * sin(uTime * 1.2 + fi);
        float y = -0.2 + fract(hash(vec2(fi, 2.0)) + uTime * 0.06 * (1.0 + hash(vec2(fi, 3.0)))) * 1.45;
        float s = 0.045 + 0.02 * hash(vec2(fi, 4.0));
        vec2 q = aspectD(vUv, vec2(x, y));
        float e = length(q / vec2(s * 0.85, s));
        float body = smoothstep(1.0, 0.95, e);
        vec3 col = clamp(hueShift(vec3(1.0, 0.2, 0.25), hash(vec2(fi, 5.0)) * 6.283), 0.0, 1.0);
        col *= 0.7 + 0.4 * (1.0 - clamp(length(q / s - vec2(-0.3, 0.35)), 0.0, 1.0));
        col += vec3(1.0) * smoothstep(0.25, 0.0, length(q / s - vec2(-0.35, 0.45))) * 0.7;
        float str = (1.0 - smoothstep(0.0, 0.0018, abs(q.x - 0.008 * sin(q.y * 60.0 + uTime * 3.0))))
                    * step(q.y, -s * 0.95) * step(-s - 0.13, q.y);
        c = mix(c, vec3(0.92), str * 0.7);
        c = mix(c, col, body);
    }
    gl_FragColor = finish(c);
}
"""

    // ================================================== step 6 EFFECTS

    // ---------------- Sky
    private const val FX_MOON = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    float k = ease(uTime / uDur);
    float top = smoothstep(0.35, 1.0, vUv.y);
    c = mix(c, c * vec3(0.78, 0.86, 1.08), 0.35 + 0.35 * top);
    vec2 mc = vec2(0.76, 0.80 + 0.06 * k);
    float R = 0.075;
    vec2 q = aspectD(vUv, mc);
    float d = length(q);
    float out_ = max(d - R, 0.0);
    c += vec3(0.85, 0.9, 1.0) * (exp(-out_ * 9.0) * 0.30 + exp(-out_ * 35.0) * 0.35) * step(R, d);
    vec2 n = q / R;
    float tex = 0.92 + 0.08 * fbm(n * 3.0 + 3.0);
    tex -= 0.08 * smoothstep(0.55, 0.75, vnoise(n * 5.0 + 1.0));
    tex -= 0.05 * smoothstep(0.6, 0.8, vnoise(n * 11.0 + 7.0));
    float shade = 0.82 + 0.18 * sqrt(max(0.0, 1.0 - dot(n, n)));
    vec3 mcol = min(vec3(1.08, 1.05, 0.96) * tex * shade, 1.0);
    c = mix(c, mcol, smoothstep(R + 0.002, R - 0.002, d));
    gl_FragColor = finish(c);
}
"""

    private const val FX_SUNRISE = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    float k = ease(uTime / uDur);
    c = mix(c, c * vec3(1.1, 0.98, 0.84) + vec3(0.04, 0.015, 0.0), 0.3 + 0.4 * k);
    vec2 sc = vec2(0.24, mix(0.72, 0.88, k));
    vec2 q = aspectD(vUv, sc);
    float d = length(q);
    float R = 0.055;
    c += vec3(1.0, 0.55, 0.2) * exp(-d * 3.0) * (0.2 + 0.2 * k);
    float ang = atan(q.y, q.x);
    float rays = pow(0.5 + 0.5 * sin(ang * 16.0 + uTime * 0.3), 6.0) * exp(-d * 2.5);
    c += vec3(1.0, 0.8, 0.5) * rays * 0.25;
    c += vec3(1.0, 0.85, 0.55) * exp(-max(d - R, 0.0) * 25.0) * 0.5;
    c = mix(c, vec3(1.0, 0.93, 0.72), smoothstep(R, R - 0.004, d));
    gl_FragColor = finish(c);
}
"""

    private const val FX_SHOOTING = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    vec2 dir = normalize(vec2(-0.75, -0.45));
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float per = 1.6 + 1.4 * hash(vec2(fi, 1.0));
        float x = uTime / per + hash(vec2(fi, 2.0));
        float ph = fract(x);
        float n = floor(x);
        if (ph < 0.4) {
            float sk = ph / 0.4;
            vec2 a = vec2(0.35 + 0.65 * hash(vec2(n, fi)), 0.72 + 0.26 * hash(vec2(n, fi + 9.0)));
            vec2 head = a + vec2(dir.x / uOutAspect, dir.y) * sk * 0.55;
            vec2 q = aspectD(vUv, head);
            float along = dot(q, -dir);
            float side = abs(dot(q, vec2(-dir.y, dir.x)));
            float tail = step(0.0, along) * exp(-along * 9.0) * (1.0 - smoothstep(0.0, 0.003 + along * 0.01, side));
            float hd = exp(-dot(q, q) * 9000.0);
            c += vec3(0.9, 0.95, 1.0) * (tail + hd * 1.5) * (1.0 - sk * 0.7);
        }
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_BIRDS = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int f = 0; f < 2; f++) {
        float ff = float(f);
        float lead = -0.3 + fract(uTime * (0.055 + 0.02 * ff) + ff * 0.5) * 1.6;
        float baseY = 0.80 - ff * 0.14;
        float sz = 0.03 - ff * 0.008;
        for (int i = 0; i < 7; i++) {
            float fi = float(i);
            float o = fi - 3.0;
            vec2 p = vec2(lead - abs(o) * 0.055, baseY - abs(o) * 0.03 + 0.008 * sin(uTime * 2.0 + fi));
            vec2 q = aspectD(vUv, p) / sz;
            if (abs(q.x) < 1.3 && abs(q.y) < 1.6) {
                float flap = sin(uTime * 9.0 + fi * 1.3 + ff * 2.0);
                float x = clamp(q.x, -1.0, 1.0);
                float yw = flap * 0.6 * abs(x) - 0.25 * x * x;
                float th = 0.16 * (1.0 - abs(x)) + 0.05;
                float m = (1.0 - smoothstep(th * 0.6, th, abs(q.y - yw))) * step(abs(q.x), 1.0);
                m = max(m, smoothstep(0.25, 0.15, length(q * vec2(1.0, 1.6))));
                c = mix(c, vec3(0.06, 0.06, 0.08), m * (0.9 - 0.25 * ff));
            }
        }
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_CLOUDS = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    float x = vUv.x * uOutAspect;
    float f = fbm(vec2(x * 2.5 + uTime * 0.03, vUv.y * 4.0));
    float f2 = fbm(vec2(x * 5.0 + uTime * 0.05, vUv.y * 7.0) + 4.0);
    float amt = smoothstep(0.48, 0.75, f * 0.8 + f2 * 0.3) * smoothstep(0.66, 0.92, vUv.y);
    vec3 cloud = mix(vec3(0.8, 0.83, 0.9), vec3(1.0), smoothstep(0.5, 0.8, f2));
    c = mix(c, cloud, amt * 0.8);
    gl_FragColor = finish(c);
}
"""

    private const val FX_NIGHT_SKY = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    float top = smoothstep(0.4, 1.0, vUv.y);
    c = mix(c, c * 0.4 + vec3(0.02, 0.03, 0.12), top * 0.85);
    float band = exp(-pow((vUv.y - 0.9 + (vUv.x - 0.5) * 0.4) * 6.0, 2.0)) * top;
    c += vec3(0.35, 0.3, 0.55) * band * fbm(vUv * vec2(8.0, 4.0) + uTime * 0.02) * 0.6;
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        vec2 g = vec2(vUv.x * uOutAspect, vUv.y) * (30.0 + fi * 25.0);
        vec2 cell = floor(g);
        float hs = hash(cell + fi * 3.0);
        if (hs > 0.9) {
            vec2 ff = fract(g) - 0.5 - (vec2(hash(cell + 2.0), hash(cell + 5.0)) - 0.5) * 0.6;
            float tw = 0.4 + 0.6 * pow(0.5 + 0.5 * sin(uTime * (2.0 + 3.0 * hs) + hs * 50.0), 2.0);
            c += vec3(0.9, 0.95, 1.0) * smoothstep(0.1, 0.0, length(ff)) * tw * top * (1.3 - 0.4 * fi);
        }
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_RAINBOW = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    vec2 q = aspectD(vUv, vec2(0.5, 0.12));
    float r = length(q);
    float t = (r - 0.48) / 0.075;
    float a = smoothstep(0.0, 0.15, t) * smoothstep(1.0, 0.85, t) * ease(uTime / 1.5) * smoothstep(0.2, 0.4, vUv.y);
    c += spectrum((1.0 - clamp(t, 0.0, 1.0)) * 0.8) * a * 0.28;
    gl_FragColor = finish(c);
}
"""

    // ---------------- Nature
    private const val FX_LEAVES = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float sp = 0.06 + 0.05 * hash(vec2(fi, 1.0));
        float y = 1.15 - fract(hash(vec2(fi, 2.0)) + uTime * sp) * 1.3;
        float x = hash(vec2(fi, 3.0)) + 0.07 * sin(uTime * 1.1 + fi * 2.0);
        vec2 q = aspectD(vUv, vec2(x, y));
        float a = uTime * (0.8 + hash(vec2(fi, 4.0))) + fi;
        q = vec2(cos(a) * q.x + sin(a) * q.y, -sin(a) * q.x + cos(a) * q.y);
        float sz = 0.025 + 0.015 * hash(vec2(fi, 5.0));
        q /= sz;
        q.x /= 0.3 + 0.7 * abs(cos(uTime * 1.5 + fi));
        float w = (1.0 - q.y * q.y) * 0.45;
        float m = smoothstep(0.03, -0.03, abs(q.x) - w) * step(abs(q.y), 1.0);
        float vein = 1.0 - smoothstep(0.02, 0.06, abs(q.x));
        vec3 col = mix(vec3(0.85, 0.3, 0.08), vec3(0.95, 0.7, 0.15), hash(vec2(fi, 6.0)));
        col = mix(col, col * 0.6, vein);
        c = mix(c, col, m * 0.95);
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_SAKURA = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 16; i++) {
        float fi = float(i);
        float sp = 0.05 + 0.05 * hash(vec2(fi, 1.0));
        float y = 1.1 - fract(hash(vec2(fi, 2.0)) + uTime * sp) * 1.25;
        float x = fract(hash(vec2(fi, 3.0)) + uTime * 0.025) + 0.04 * sin(uTime + fi);
        vec2 q = aspectD(vUv, vec2(x, y));
        float sz = 0.016 + 0.01 * hash(vec2(fi, 4.0));
        q /= sz;
        float a = atan(q.y, q.x) + uTime * (1.0 + hash(vec2(fi, 5.0)));
        float r = length(q);
        float petal = 0.55 + 0.45 * abs(cos(2.5 * a));
        float m = smoothstep(petal, petal - 0.12, r);
        vec3 col = mix(vec3(1.0, 0.72, 0.82), vec3(1.0, 0.92, 0.95), smoothstep(0.9, 0.2, r));
        col = mix(col, vec3(1.0, 0.5, 0.65), smoothstep(0.22, 0.0, r));
        c = mix(c, col, m * 0.92);
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_BUTTERFLIES = COMMON + """
float ell(vec2 p, vec2 cen, vec2 rad) { return smoothstep(1.0, 0.88, length((p - cen) / rad)); }
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        vec2 p = vec2(fract(hash(vec2(fi, 1.0)) + uTime * 0.045) * 1.2 - 0.1,
                      0.25 + 0.55 * hash(vec2(fi, 2.0)) + 0.05 * sin(uTime * 1.6 + fi * 2.0));
        float s = 0.03 + 0.01 * hash(vec2(fi, 3.0));
        vec2 q = aspectD(vUv, p) / s;
        if (abs(q.x) < 1.4 && abs(q.y) < 1.4) {
            float flap = 0.2 + 0.8 * abs(sin(uTime * 11.0 + fi * 1.7));
            vec2 w = vec2(abs(q.x) / flap, q.y);
            float up = ell(w, vec2(0.55, 0.32), vec2(0.55, 0.5));
            float lo = ell(w, vec2(0.42, -0.32), vec2(0.4, 0.36));
            float m = max(up, lo);
            vec3 col = clamp(hueShift(vec3(1.0, 0.45, 0.1), hash(vec2(fi, 4.0)) * 6.283), 0.0, 1.0);
            col = mix(col * 0.35, col, smoothstep(1.0, 0.4, length(w - vec2(0.5, 0.0))));
            float body = (1.0 - smoothstep(0.05, 0.09, abs(q.x))) * step(abs(q.y), 0.6);
            c = mix(c, col, m * 0.95);
            c = mix(c, vec3(0.08), body);
        }
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_FIREFLIES = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0))) * 0.85;
    for (int i = 0; i < 18; i++) {
        float fi = float(i);
        vec2 p = vec2(hash(vec2(fi, 1.0)), hash(vec2(fi, 2.0)) * 0.9 + 0.05);
        p += 0.08 * vec2(sin(uTime * 0.5 * (hash(vec2(fi, 3.0)) + 0.5) + fi), cos(uTime * 0.4 * (hash(vec2(fi, 4.0)) + 0.5) + fi * 1.3));
        float blink = 0.5 + 0.5 * sin(uTime * (1.5 + 2.0 * hash(vec2(fi, 5.0))) + fi * 3.0);
        float d = length(aspectD(vUv, p));
        c += vec3(1.0, 0.88, 0.4) * (exp(-d * d * 6000.0) + exp(-d * d * 300.0) * 0.35) * blink;
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_DANDELION = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float y = -0.1 + fract(hash(vec2(fi, 1.0)) + uTime * (0.03 + 0.03 * hash(vec2(fi, 2.0)))) * 1.25;
        float x = fract(hash(vec2(fi, 3.0)) + uTime * 0.02) + 0.05 * sin(uTime * 0.9 + fi * 1.7);
        vec2 q = aspectD(vUv, vec2(x, y));
        float s = 0.022 + 0.012 * hash(vec2(fi, 4.0));
        q /= s;
        float r = length(q);
        float a = atan(q.y, q.x) + uTime * 0.5 + fi;
        float spoke = (1.0 - smoothstep(0.0, 0.12, abs(fract(a / 0.5236) - 0.5) * r)) * step(r, 1.0) * step(0.15, r);
        float tip = smoothstep(0.25, 0.0, abs(r - 0.95)) * 0.6;
        float core = smoothstep(0.2, 0.08, r);
        c = mix(c, vec3(1.0), clamp(spoke * 0.55 + tip * 0.5 + core, 0.0, 1.0) * 0.85);
    }
    gl_FragColor = finish(c);
}
"""

    // ---------------- Weather
    private const val FX_LIGHTNING = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0))) * vec3(0.8, 0.86, 0.95);
    float per = 2.6;
    float n = floor(uTime / per);
    float tt = uTime - n * per;
    float flash = (exp(-tt * 14.0) + 0.6 * step(0.12, tt) * exp(-(tt - 0.12) * 12.0)) * step(0.0, tt);
    float x0 = 0.25 + 0.5 * hash(vec2(n, 1.0));
    float yEnd = 0.35 + 0.2 * hash(vec2(n, 2.0));
    float bx = x0 + (vnoise(vec2(vUv.y * 8.0, n * 7.0)) - 0.5) * 0.14 + (vnoise(vec2(vUv.y * 32.0, n * 7.0 + 5.0)) - 0.5) * 0.035;
    float dist = abs(vUv.x - bx) * uOutAspect;
    float on = step(yEnd, vUv.y) * smoothstep(yEnd, yEnd + 0.05, vUv.y);
    float bolt = (exp(-dist * dist * 60000.0) + exp(-dist * dist * 1500.0) * 0.35) * on;
    c += vec3(0.85, 0.9, 1.0) * (bolt * 1.5 + 0.35) * flash;
    gl_FragColor = finish(c);
}
"""

    private const val FX_FOG = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    float f = fbm(vec2(vUv.x * uOutAspect * 3.0 + uTime * 0.08, vUv.y * 3.0 - uTime * 0.02));
    float f2 = fbm(vec2(vUv.x * uOutAspect * 6.0 - uTime * 0.05, vUv.y * 5.0) + 7.0);
    float amt = smoothstep(0.35, 0.8, f * 0.7 + f2 * 0.4) * (0.45 + 0.55 * (1.0 - vUv.y));
    c = mix(c, vec3(0.86, 0.89, 0.93), amt * 0.6);
    gl_FragColor = finish(c);
}
"""

    // ---------------- Light
    private const val FX_PRISM = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    float pos = -0.3 + fract(uTime * 0.12) * 1.8;
    float u = (vUv.x * uOutAspect + vUv.y * 0.55) - pos;
    float band = smoothstep(0.0, 0.05, u) * smoothstep(0.28, 0.18, u);
    vec3 col = spectrum(clamp(u / 0.28, 0.0, 1.0) * 0.85);
    c = 1.0 - (1.0 - c) * (1.0 - col * band * 0.45);
    float u2 = (vUv.x * uOutAspect - vUv.y * 0.3) - (1.2 - fract(uTime * 0.08 + 0.5) * 1.6);
    float band2 = smoothstep(0.0, 0.03, u2) * smoothstep(0.12, 0.07, u2);
    c = 1.0 - (1.0 - c) * (1.0 - spectrum(clamp(u2 / 0.12, 0.0, 1.0) * 0.85) * band2 * 0.3);
    gl_FragColor = finish(c);
}
"""

    private const val FX_SPOTLIGHT = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    vec2 apex = vec2(0.5 + 0.22 * sin(uTime * 0.7), 1.08);
    vec2 d = aspectD(vUv, apex);
    float ang = atan(d.x, -d.y);
    float cone = smoothstep(0.24, 0.15, abs(ang - 0.25 * sin(uTime * 0.7 + 0.6)));
    c = c * mix(0.45, 1.25, cone) + vec3(1.0, 0.95, 0.8) * cone * 0.08;
    vec2 g = vec2(vUv.x * uOutAspect, vUv.y) * 30.0 + vec2(0.0, uTime * 0.6);
    vec2 cell = floor(g);
    if (hash(cell) > 0.93) c += vec3(1.0, 0.95, 0.85) * smoothstep(0.12, 0.0, length(fract(g) - 0.5)) * cone * 0.5;
    gl_FragColor = finish(c);
}
"""

    private const val FX_GOLD = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float s = 12.0 + fi * 10.0;
        vec2 g = vec2(vUv.x * uOutAspect, vUv.y) * s;
        g.y += uTime * (0.6 + 0.4 * fi) * (s / 12.0) * 0.5;
        g.x += sin(uTime * 0.8 + fi + g.y * 0.4) * 0.25;
        vec2 cell = floor(g);
        float hs = hash(cell + fi * 13.0);
        if (hs > 0.6) {
            vec2 f = fract(g) - 0.5 - (vec2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5) * 0.5;
            float tw = pow(0.5 + 0.5 * sin(uTime * (4.0 + 6.0 * hs) + hs * 80.0), 4.0);
            float dot_ = smoothstep(0.07, 0.02, length(f));
            float st = (max(0.0, 1.0 - abs(f.x) * 16.0 - abs(f.y) * 3.0) + max(0.0, 1.0 - abs(f.y) * 16.0 - abs(f.x) * 3.0)) * tw;
            c += vec3(1.0, 0.78, 0.3) * (dot_ * 0.7 + st * 0.8);
        }
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_NEON_FRAME = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    vec2 p = (vUv - 0.5) * uRes;
    float inset = uRes.x * 0.045;
    float r = uRes.x * 0.06;
    vec2 q = abs(p) - (uRes * 0.5 - inset) + r;
    float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float px = uRes.x / 720.0;
    float line = exp(-abs(sd) / (2.2 * px));
    float glow = exp(-abs(sd) / (16.0 * px));
    float h = atan(p.y, p.x) / 6.2832 + uTime * 0.15;
    vec3 col = mix(vec3(1.0, 0.2, 0.75), vec3(0.15, 0.85, 1.0), 0.5 + 0.5 * sin(h * 6.2832 * 2.0));
    float pulse = 0.8 + 0.2 * sin(uTime * 6.0);
    c += col * (line * 1.3 + glow * 0.45) * pulse;
    gl_FragColor = finish(c);
}
"""

    // ---------------- Party
    private const val FX_LANTERNS = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    c = mix(c, c * vec3(0.85, 0.82, 0.95), 0.3);
    for (int i = 0; i < 10; i++) {
        float fi = float(i);
        float depth = hash(vec2(fi, 9.0));
        float y = -0.15 + fract(hash(vec2(fi, 1.0)) + uTime * (0.025 + 0.035 * depth)) * 1.35;
        float x = hash(vec2(fi, 2.0)) + 0.03 * sin(uTime * 0.7 + fi * 1.9);
        float s = 0.018 + 0.03 * depth;
        vec2 q = aspectD(vUv, vec2(x, y)) / s;
        float flick = 0.85 + 0.15 * sin(uTime * 9.0 + fi * 3.0);
        c += vec3(1.0, 0.55, 0.15) * exp(-dot(q, q) * 0.35) * 0.30 * flick * (0.5 + 0.5 * depth);
        float w = 0.55 + 0.12 * q.y;
        float m = smoothstep(0.06, -0.06, abs(q.x) - w) * smoothstep(0.06, -0.06, abs(q.y) - 0.8);
        vec3 col = mix(vec3(1.0, 0.45, 0.1), vec3(1.0, 0.9, 0.5), smoothstep(0.8, -0.8, q.y)) * flick;
        c = mix(c, col, m * (0.6 + 0.4 * depth));
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_HEART_BURST = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    float n = floor(uTime);
    float bt = fract(uTime);
    vec2 cen = vec2(0.5, 0.55);
    for (int i = 0; i < 10; i++) {
        float fi = float(i);
        float a = fi / 10.0 * 6.2832 + hash(vec2(n, 1.0));
        float dist = (0.12 + 0.1 * hash(vec2(n, fi))) * ease(bt * 1.4);
        vec2 p = cen + vec2(cos(a) / uOutAspect, sin(a)) * dist;
        float s = 0.03 * (0.4 + bt);
        vec2 q = aspectD(vUv, p) / s;
        float m = smoothstep(-0.06, 0.04, heartD(q * 0.8)) * (1.0 - smoothstep(0.55, 1.0, bt));
        vec3 col = mix(vec3(1.0, 0.2, 0.4), vec3(1.0, 0.6, 0.8), hash(vec2(fi, n)));
        c = mix(c, col, m * 0.85);
    }
    gl_FragColor = finish(c);
}
"""

    private const val FX_CONFETTI = COMMON + """
void main() {
    vec3 c = photo(coverUv(vUv, 1.0, vec2(0.0)));
    for (int i = 0; i < 40; i++) {
        float fi = float(i);
        float sp = 0.10 + 0.08 * hash(vec2(fi, 1.0));
        float y = 1.1 - fract(hash(vec2(fi, 2.0)) + uTime * sp) * 1.25;
        float x = hash(vec2(fi, 3.0)) + 0.04 * sin(uTime * 2.0 + fi);
        vec2 q = aspectD(vUv, vec2(x, y));
        float a = uTime * (2.0 + 2.0 * hash(vec2(fi, 4.0))) + fi;
        q = vec2(cos(a) * q.x + sin(a) * q.y, -sin(a) * q.x + cos(a) * q.y);
        float fl = abs(cos(uTime * 4.0 + fi * 1.7));
        float m = step(abs(q.x), 0.013) * step(abs(q.y), 0.008 * fl + 0.0015);
        vec3 col = clamp(hueShift(vec3(1.0, 0.25, 0.3), hash(vec2(fi, 5.0)) * 6.283), 0.0, 1.0);
        c = mix(c, col * (0.7 + 0.3 * fl), m);
    }
    gl_FragColor = finish(c);
}
"""

    val fragments: Map<String, String> = mapOf(
        "plain" to PLAIN,
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
        "pink_sparkle" to PINK_SPARKLE,
        "love_frame" to LOVE_FRAME,
        "petals" to PETALS,
        "shake" to SHAKE,
        "split" to SPLIT,
        "zoom_blur" to ZOOM_BLUR,
        "letterbox" to LETTERBOX,
        "ken_burns" to KEN_BURNS,
        "light_leak" to LIGHT_LEAK,
        "color_return" to COLOR_RETURN,
        "fog" to FOG,
        "lonely" to LONELY,
        "fireflies" to FIREFLIES,
        "starry" to STARRY,
        "confetti" to CONFETTI,
        "mirror" to MIRROR,
        "comic" to COMIC,
        "rainbow" to RAINBOW,
        "disco" to DISCO,
        "vhs" to VHS,
        "old_tv" to OLD_TV,
        "pixel" to PIXEL,
        "spin" to SPIN,
        "vertigo" to VERTIGO,
        "aurora" to AURORA,
        "kaleido" to KALEIDO,
        "pop_art" to POP_ART,
        "sketch" to SKETCH,
        "duotone" to DUOTONE,
        "fx_sparkle" to FX_SPARKLE,
        "fx_hearts" to FX_HEARTS,
        "fx_snow" to FX_SNOW,
        "fx_rain" to FX_RAIN,
        "fx_bokeh" to FX_BOKEH,
        "fx_dust" to FX_DUST,
        "fx_rays" to FX_RAYS,
        "fx_flare" to FX_FLARE,
        "fx_bubbles" to FX_BUBBLES,
        "fx_film" to FX_FILM,
        "fx_fireworks" to FX_FIREWORKS,
        "fx_balloons" to FX_BALLOONS,
        "fx_moon" to FX_MOON,
        "fx_sunrise" to FX_SUNRISE,
        "fx_shooting" to FX_SHOOTING,
        "fx_birds" to FX_BIRDS,
        "fx_clouds" to FX_CLOUDS,
        "fx_night_sky" to FX_NIGHT_SKY,
        "fx_rainbow" to FX_RAINBOW,
        "fx_leaves" to FX_LEAVES,
        "fx_sakura" to FX_SAKURA,
        "fx_butterflies" to FX_BUTTERFLIES,
        "fx_fireflies" to FX_FIREFLIES,
        "fx_dandelion" to FX_DANDELION,
        "fx_lightning" to FX_LIGHTNING,
        "fx_fog" to FX_FOG,
        "fx_prism" to FX_PRISM,
        "fx_spotlight" to FX_SPOTLIGHT,
        "fx_gold" to FX_GOLD,
        "fx_neon_frame" to FX_NEON_FRAME,
        "fx_lanterns" to FX_LANTERNS,
        "fx_heart_burst" to FX_HEART_BURST,
        "fx_confetti" to FX_CONFETTI,
    )

    /** The time used for the template's thumbnail card. */
    fun thumbTime(id: String): Float = when (id) {
        "memory" -> 3.6f
        "color_return" -> 5.0f
        "love_frame" -> 2.0f
        "sketch" -> 6.5f
        "kaleido" -> 3.0f
        "vertigo" -> 4.0f
        "fx_fireworks" -> 1.9f
        "fx_shooting" -> 0.35f
        "fx_lightning" -> 2.65f
        "fx_heart_burst" -> 2.45f
        "fx_rainbow" -> 4.0f
        else -> 2.6f
    }
}
