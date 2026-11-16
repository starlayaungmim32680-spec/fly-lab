# Fly Lab - notes (read this first)

Fly Lab is Ko's test bench for **Fly** camera effects (repo
`starlayaungmim32680-spec/fly-lab`, PC `C:\Users\AUNG\Desktop\fly_lab`,
package `com.aungdev.fly_lab`, installs next to Fly and Fly Dev, debug-signed,
never published). An effect is built and tested here first; only when it works
on Ko's phone (vivo V2250) is it copied into Fly (`starlayaungmim32680-spec/aung`,
see its AI_CONTEXT.md - same rules: Burmese, full files / zip, one step at a
time, Impeller OFF).

Build (arm64 only, ~20-150 s):
`$d = Get-Date; $bn = '{0:yy}{1:D3}{0:HHmm}' -f $d, $d.DayOfYear; flutter build apk --release --target-platform android-arm64 --build-number=$bn; Copy-Item build\app\outputs\flutter-apk\app-release.apk "FlyLab-bn$bn.apk" -Force`

## Files
- `android/app/src/main/kotlin/com/aungdev/fly_lab/LabCamera.kt` - camera
  (copy of Fly's BeautyLab.kt without recording) + GPUPixel beauty +
  MediaPipe person segmentation + background effects. Channel `flylab/camera`.
- `MainActivity.kt` - registers the channel; saves the last Java crash
  (shown in the read-out as "last crash: ...", long-press LAB to clear).
- `lib/main.dart` - the Lab screen (Off / Blur / Mask, flip, read-out).
- `android/app/libs/gpupixel-release.aar` - same AAR as Fly.
- `android/app/src/main/assets/selfie_segmenter.tflite` - MediaPipe model
  (Apache-2.0), 249,537 bytes, from
  storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/
- `android/app/proguard-rules.pro` - R8 keep rules (see below).

## Step 1 - person cut-out + Blur: WORKS (16 Nov 2026, FlyLab-bn263200046)
- Ko's phone: camera 23-25 fps, mask 35-42 ms on the **CPU** (16-18 masks/s),
  person sharp, background blurred (RenderEffect blur, API 31+).
- Mask is 144x256, stretched with smoothstep(0.35, 0.65); edges a little
  stair-stepped, a light halo around the hair (the blur also smears the
  person's own colours into the background). To improve later.
- **Lessons for the Fly merge (important):**
  1. Flutter release builds run R8. MediaPipe needs keep rules:
     `com.google.mediapipe.**`, `com.google.protobuf.**`,
     `com.google.common.flogger.**` (else "IllegalStateException: no caller
     found on the stack for: v0.c" - Flogger finds its caller by class
     name), `com.google.common.**`, AutoValue.
  2. The MediaPipe **GPU delegate crashes natively** on the V2250 (app closes
     on open; try/catch can't stop it). LabCamera saves a "gpuTrying" flag
     before trying the GPU; after a crash the next start uses the CPU
     ("CPU (GPU crashed)"). In Fly: just use the CPU delegate.
  3. MediaPipe Tasks send anonymous usage metrics to Google (their docs) -
     mention in Fly's privacy policy when merged.

## Step 3 - futuristic backgrounds + halo fix: WORKS (16 Nov 2026)
- `LabBackgrounds.kt`: 6 AGSL RuntimeShader backgrounds (Android 13+):
  city (synthwave sun + skyline + grid floor), warp (star streaks), matrix
  (green glyph rain), grid (hologram room), aurora (stars + light curtains),
  chrome (liquid metal). Neon rim glow (mask tinted + RenderEffect blur,
  Android 12+) in each background's colour.
- Blur halo fix: the seg thread builds a clean background from the 144x256
  copy with the person weighted out (weighted box blur, r = 7, 2 passes) -
  no person colours left in it. Mask edge smoothstep(0.45, 0.8).
- Ko's phone (screenshots 01:17-01:18): camera 24-25 fps with the shaders
  (no fps drop), mask 32-40 ms (17-21/s, CPU). City / Warp / Matrix look
  right; **Blur halo is gone**.
- Still to improve: on the bright-room shaders a thin **white fringe** of the
  old background around the hair / shoulders, the edge is a little
  **stair-stepped** (the model works at 256x256), and things touching the
  person (a ceiling lamp behind the head) count as "person". Idea: a thin
  neon outline drawn ON the edge band (4*m*(1-m)) + smoothing the mask over
  time.

## Edge polish (16 Nov 2026, testing)
- Confidence smoothed over time (65% step, jumps > 0.5 taken at once).
- Neon outline ON the edge band: band = 4e(1-e), e = smoothstep(0.15, 0.9,
  conf), squared; drawn ADD in the background's glow colour after the
  person (shader backgrounds only) - covers the white fringe / stair steps.

## White fringe removal (16 Nov 2026, testing)
- Ko: "no white at all - fix the base first". Android 13+: the person is
  drawn by the CUTOUT AGSL shader (LabCamera companion): a = smoothstep(0.35,
  0.95, mask alpha) (edge pulled in) and edge colour de-mixed with the clean
  room estimate B: F = (C - (1-a) B) / max(a, 0.3), premultiplied. The room
  estimate (buildCleanBlur) now runs for every background mode.

## Motion fix - frame + its own mask (16 Nov 2026, testing)
- Ko: "when I move, a white shadow of the room comes along". Cause: timing
  - the newest frame was drawn with a mask from an older frame. Now each
  shown frame is held (full copy) until ITS mask is ready and drawn with it;
  frames arriving while the segmenter is busy are skipped. Cost: preview at
  the mask rate (~18-22 fps) and a few tens of ms later. Time smoothing
  only for changes < 0.12; room estimate every 3rd mask.

## No colour ring + speed fix (16 Nov 2026, testing)
- Motion fix test: "better", but camera 7 fps and Ko doesn't want the colour
  ring around the body. Neon glow + outline OFF (`neon = false`). Pipeline:
  segmenter works on frame N while the camera thread parks the newest
  beautified frame (newest wins); the seg thread goes straight on with it
  (segLoop). Room estimate after sending the frame to draw; 3 mask / edge
  buffers.

## Motion-smear fix (16 Nov 2026, testing)
- Speed fix result: camera 20 fps, mask 23 ms (24/s), no ring. Left: white
  shadow on fast moves = the camera's own motion blur (hair smeared over the
  white wall). CUTOUT: colour key in the edge zone (mask < 0.98): pixels
  within ~0.08-0.22 colour distance of the room estimate are dropped. Camera
  asks for a fixed 30-30 fps range first (shorter shutter).

## Pipeline bug fix (16 Nov 2026, testing)
- 02:12 test: head-shaped hole showing the room = old frame drawn with a
  newer mask (camera thread behind, 3 shared mask buffers overwritten).
  Now: each frame carries its own mask / edge copy; only the newest
  finished frame is drawn (handOverForDraw / drawReady, older dropped);
  camera frames are skipped before the beauty step while one is parked.

## Quality pass (16 Nov 2026, testing)
- Pipeline fix result: no more white shadow. Ko wants: TikTok-pretty,
  bright in a dark room, smooth on moves (read-out: camera 11 fps).
- Draw thread (LabDraw) - camera / segmenter / drawing in parallel.
- Low light: Fly's ISO watcher (fixed 30 -> 24-30 fps, HQ noise reduction)
  + CUTOUT shadow lift f = 1-(1-f)^gain, gain from the person's average
  brightness (aim 0.5, 1.0-1.8, smoothed). Read-out: "LOW", "light x1.4".
- Beauty presets from Fly (Natural / Glow / Doll / Glam), default Glow.
- Colour key gentler: distance 0.05-0.14, only where mask < 0.92.

## Hair pass (16 Nov 2026, testing)
- Quality pass result (02:35): "all good" - camera 19 fps, LOW works, Glow.
  Left: hair = flat black lump, edge moving. Fix: de-colouring 55% and
  floor c*0.5; cut lo 0.25 / hi 0.9 (softer); 3x3 space average + time
  smoothing up to 0.25; brightness measured on the top half (face / hair).

## STATUS 16 Nov 2026 03:00 - live camera effects PAUSED, new direction
- Live camera state: edge / motion / speed / quality passes work on Ko's
  phone (no white shadow, ~19 fps, LOW mode, Glow beauty). The last "hair
  pass" build (fly-lab-hair.zip) was NOT tested yet. Honest limit: the free
  256x256 selfie model can't do hair strands / TikTok quality live on the
  V2250 (CPU only, GPU delegate crashes). Options discussed: Snap Camera
  Kit (free SDK, needs Snap approval), own path A (guided-filter refine of
  the mask) / B (MODNet / PP-MattingV2, Apache-2.0, heavier).
- **Ko's new direction (16 Nov 2026 ~03:00): a PHOTO / VIDEO EDITOR** -
  pick existing photos / videos -> templates with many effects -> an mp4
  -> post straight to Fly. Main wish: ONE PHOTO -> a VIDEO. Users can also
  edit by hand, and "just write text" -> effects. Not live, so quality can
  be much higher (frames can take their time; person cut-out offline).
- Fly's own versions (no copying CapCut designs / assets), e.g. Cinematic
  Zoom, 3D Pop (person / background cut apart = parallax), Pulse Zoom,
  Retro Film, Butterflies, Paint Pop, Future (the 6 shaders), Memories.
  Each effect = one GPU shader used for BOTH the preview and the mp4 export
  (what you see = what you get, effects baked into the file).
- "Write text" = an LLM (via the Cloudflare Worker, key not in the app)
  turns the user's text (Burmese OK) into a recipe of OUR effects; no
  generative video (too expensive).

## Editor step 1 - one photo -> video: WORKS (16 Nov 2026, FlyLab-bn263202254)
- `PhotoTemplates.kt`: 3 GLSL ES 1.0 templates (cinematic: slow push-in +
  teal/orange + light leak; pulse: 120 bpm zoom punch + RGB split + shake;
  retro: sepia, grain, flicker, weave, scratches, dust). Shared helpers:
  coverUv (cover fit, zoom, pan), fadeInOut.
- `PhotoVideoEngine.kt` (channel `flylab/editor`): photo decoded upright
  (EXIF) <= 1600 px; preview = own GL thread + EGL window surface on a
  Flutter texture (720x1280, ~30 fps, 8 s loop); export = same shader frame
  by frame into an H.264 MediaCodec input surface (720x1280, 30 fps,
  8 Mbit/s, I-frame 1 s) + MediaMuxer, then copied to the gallery
  (Movies/FlyLab via MediaStore). No audio yet (step 4).
- `lib/editor_lab.dart` + Lab home (`LabHome` in main.dart): Photo -> Video
  / Camera Lab. `image_picker` ^1.2.2 added.
- Ko's phone: preview smooth, export **8 s = 240 frames in 3.0 s** (much
  faster than real time), 9.5 MB, saved to Movies/FlyLab, the gallery
  video looks like the preview (Retro Film checked). Small fix for later:
  Retro Film's dust spots are hard black squares - make them round / soft.

## Editor step 5 - COMBINE + more: WORKS (17 Nov 2026, FlyLab-bn263210118)
- Ko's test video: template + Snow + Bokeh + Fireworks, 8 s, 240 frames,
  4.1 MB, all layers baked in, right way up. Note: several BRIGHT layers
  together look a little over-bright - for later: auto-balance brightness
  when stacking (for now Adjust -> Brightness).
- Ko: "many more" + "combine one with another".
- Engine: a CHAIN - setTemplate {ids: [template, effect, effect, effect]}.
  PhotoRenderer.draw() renders pass 1 (template on the photo) into an
  offscreen texture, each next pass reads the one before (ping-pong FBOs,
  uFlipIn = 1 because FBO rows are bottom-up), the last pass draws to the
  screen / encoder with uFinal = 1 (only it applies the user's look +
  fade). Export takes the same ids.
- 7 new templates (Spin - Beat, Dolly Zoom - Cinema, Aurora - Night,
  Kaleido - Fun, Pop Art / Sketch / Duotone - new category Art; Comic moved
  to Art) -> 42 templates.
- EFFECTS tab: 12 stackable effects with no zoom / no grade of their own -
  Sparkle, Hearts, Snow, Rain, Bokeh, Film Dust, Light Rays, Lens Flare,
  Bubbles, Film Strip, Fireworks, Balloons. Tick up to 3 (numbered badges =
  order), "Clear"; the chain is shown as "Dreamy + Snow + Sparkle".
- All 54 shaders checked with glslangValidator; chains rendered on the PC
  emulator (Dreamy + Hearts, Neon + Snow + Sparkle, Memory + Bokeh ...).

## Editor step 4 - template LIBRARY: WORKS (17 Nov 2026, FlyLab-bn263210050)
- Ko: "many more, CapCut-like variety, but neat - each in its place".
- 22 new shaders -> 35 templates, each in exactly ONE category:
  Love (Dreamy, Heartbeat, Pink Sparkle, Love Frame, Petals) /
  Beat (Pulse Zoom, Flash Beat, Glitch, Shake, Split, Zoom Blur) /
  Cinema (Cinematic, Focus In, Golden Hour, Movie Bars, Slow Glide,
  Light Leak) / Feel (Sad Rain, Memory, Color Return, Fog, Lonely) /
  Night (Neon Lines, Snow, Fireflies, Starry Night) / Fun (Confetti,
  Mirror, Comic, Rainbow, Disco) / Retro (Retro Film, VHS, Old TV, Pixel).
  "For You" = 10 hand-picked from the others.
- COMMON helpers: heartD, vnoise / fbm, hueShift. All 35 checked with
  glslangValidator + rendered on a PC GPU emulator before sending.
- Panel: CapCut-style text tabs + preview CARDS made from the user's own
  photo: channel "thumbs" renders every template once at 180x320 into an
  offscreen FBO on the preview thread -> JPEG bytes -> Image.memory.

## Editor step 3 - templates by feeling + the user's own look: WORKS (17 Nov 2026, FlyLab-bn263210025)
- Ko (17 Nov 00:03): NO background removal. The editor = put many kinds of
  effects on photos to make a video, users make it their own (adjust /
  filters), designed for how people FEEL; later the same for videos.
- Step 2 cut-out work DROPPED: PhotoMatting.kt deleted, the multiclass
  model removed from assets (APK back to ~40 MB). selfie_segmenter.tflite
  stays (Camera Lab uses it).
- 13 templates, grouped by mood: Love (Dreamy, Heartbeat), Hype (Pulse
  Zoom, Flash Beat, Glitch), Cinema (Cinematic, Focus In, Golden Hour),
  Feel (Sad Rain, Memory polaroid, Retro Film), Night (Neon Lines, Snow).
  All 13 shaders checked with glslangValidator and rendered on a PC GPU
  emulator before sending.
- finish(c) in every template = the user's look: Filters (None, Vivid,
  Warm, Cool, B&W, Fade, Pink, Moody + strength), Adjust (brightness,
  contrast, saturation, warmth, vignette, grain), fade in / out. Length
  5 / 8 / 10 / 15 s. Channel: setParams {adj[8], seconds}.
- UI: tabs Templates / Filters / Adjust / Length; mood chips; sliders use
  ValueNotifier (no full-screen rebuilds).

## Editor step 2 - cut-out templates (16 Nov 2026) - DROPPED 17 Nov 2026
- `PhotoMatting.kt`: person cut ONCE per photo - MediaPipe IMAGE mode (CPU)
  on <= 512 px -> guided filter (r = w/110, eps 1e-3) at <= 1024 px using
  the photo's brightness as guide (edges snap to hair / shoulders) ->
  matte; clean-plate background (person weighted out, filled from the
  surroundings at 1/8 size, blended in near the person).
- Textures uMask (unit 1) / uBg (unit 2) + uRes. Templates: 3D Pop
  (background pulls back + drifts, person pushes in + drifts the other way
  = parallax), Future City / Warp / Matrix (GLSL ports of the AGSL
  backgrounds, person with a light tint). Retro dust = round soft spots.
- Preview starts at once; cut-out templates show the photo until the cut
  is ready ("Person cut out in x s"); export of a cut-out template waits
  for it.

## Editor step 2c - held things (16 Nov 2026) - DROPPED
- 2b test (bn263202337): multiclass model, cut 0.7 s - background junk gone,
  but the phone in the hands and the shin between the knees were cut away.
- Fix: morphological close (r = 1.5 % of width) + fill enclosed holes
  smaller than 12 % of the person, before the guided filter.

## Editor step 2b - cleaner cut-out (16 Nov 2026) - DROPPED
- Ko's test (bn263202315): cut in 0.2 s, but selfie_segmenter grabbed the
  blue sheet / bed / wall around a sitting person (blocky pieces), and
  3D Pop showed a dark smoky shadow behind the person.
- Fixes: selfie_multiclass_256x256.tflite in assets (whole body + hair,
  person = 1 - background; old model is the fallback), small junk blobs
  dropped, firmer threshold (q - 0.28) / 0.44, clean plate by push-pull
  fill, 3D Pop background slightly out of focus + gentler parallax.
  Status shows which model was used ("multiclass" / "selfie").

## Editor plan (Ko approved 16 Nov 2026, revised 17 Nov 2026)
1. ~~Engine + 3 templates from ONE photo~~ (WORKS)
2. ~~Cut-out templates~~ (DROPPED - no background removal)
3. ~~Templates by feeling + filters + adjust + length~~ (WORKS 17 Nov 2026)
3b. ~~Template library: 35 templates + photo preview cards~~ (WORKS)
3c. ~~Many more templates + COMBINE (template + up to 3 effects)~~ (WORKS 17 Nov 2026)
   Ko: "OK - now MANY more, CapCut-like variety, but neat, each in its place"
4. Many photos -> slideshow + music
5. Effects on existing videos
6. Lite editor (trim / split / join / speed / text / music by hand)
7. "Write text" -> recipe (AI)
8. Merge into Fly -> post straight to Fly

## Plan (Ko, 15 Nov 2026) - live camera (paused)
1. ~~Lab + GPUPixel camera~~ 2. ~~Blur~~ 3. ~~Futuristic backgrounds~~ (edge polish open) (GPU
shaders: cyber city, warp tunnel, matrix rain, hologram grid, galaxy /
aurora, liquid chrome) + the user's own photo / video 4. Record (baked into
the mp4) 5. Futuristic "outfit" effects on the clothes (MediaPipe multiclass
model: neon circuit, chrome armor, hologram suit, galaxy fabric, energy aura -
texture only, not a new garment; the model is slow: ~71 ms GPU / 218 ms CPU on
a Pixel 6) 6. Face AR stickers 7. Merge into Fly Dev -> prod.
