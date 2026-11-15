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

## Plan (Ko, 15 Nov 2026)
1. ~~Lab + GPUPixel camera~~ 2. ~~Blur~~ 3. ~~Futuristic backgrounds~~ (edge polish open) (GPU
shaders: cyber city, warp tunnel, matrix rain, hologram grid, galaxy /
aurora, liquid chrome) + the user's own photo / video 4. Record (baked into
the mp4) 5. Futuristic "outfit" effects on the clothes (MediaPipe multiclass
model: neon circuit, chrome armor, hologram suit, galaxy fabric, energy aura -
texture only, not a new garment; the model is slow: ~71 ms GPU / 218 ms CPU on
a Pixel 6) 6. Face AR stickers 7. Merge into Fly Dev -> prod.
