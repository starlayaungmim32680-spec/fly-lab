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

## Editor step 1 - one photo -> video (16 Nov 2026 evening, testing)
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

## Editor plan (Ko approved, 16 Nov 2026)
1. Engine + 3 templates from ONE photo (Cinematic Zoom, Pulse Zoom, Retro
   Film): pick photo -> preview -> export mp4 (prove speed on the V2250)
2. Cut-out templates (3D Pop, Future, ...)
3. Template panel (tabs + animated preview cards, CapCut-style layout)
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
