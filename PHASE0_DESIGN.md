# Phase 0 – Fly Creative Studio original design system (APPROVED with refinements, 19 Nov 2026)

_19 Nov 2026. Design only – no code until Ko approves. Visual mockups:
the "Fly Creative Studio – Phase 0 design" canvas (Ko's claude.ai
artifacts). Follows STUDIO_ROADMAP.md §1 (own identity, no copying) and
Fly's rules in the main repo AI_CONTEXT.md (app UI text in English only,
Fly gradient #FF4B6E → #9C4DFF → #3A8DFF, full-file rewrites, one task at a
time, Impeller OFF, --build-number on every build)._

Ko's direction: **Premium Professional · Dark · Original Fly identity ·
beginner-friendly but powerful.** No AI features, no engine rewrite, no
feature removed.

## 0. Ko's approval and refinements (19 Nov 2026, 16:18)

Ko approved the Premium Dark direction with these refinements (all
folded into the sections below and into the canvas):

| # | Refinement | Where it is answered |
|---|---|---|
| R1 | Keep the original Fly identity and dark premium look | §2 tokens unchanged in spirit |
| R2 | Red→blue gradient sparingly: important actions and selected states only | §2 "Aurora rule" |
| R3 | Clear text labels on all 6 dock tools (beginners) | §4 Dock, §3 DockTool |
| R4 | Selected effects visually obvious | §5.3, §3 "Selected states" |
| R5 | Dots under effect cards: pagination or variations? | §5.3 – they are STYLE VARIATIONS, now drawn as colour swatches + "4 styles", never dots |
| R6 | Each track: distinct colour AND a readable label | §4 River – labelled gutter |
| R7 | Usability on small Android screens | §6 + test matrix |
| R8 | Clean, professional, no clutter | §2 "Calm rule" |
| R9 | P0.1 = ONLY colours + typography on the existing screen; no layout or engine change; nothing lost | §10 P0.1 and its file list |

Correction to STUDIO_ROADMAP.md §4.8: "Burmese UI" there was wrong – Fly's
rule is English-only UI text. Myanmar stays in the CONTENT (5 Myanmar
fonts, Myanmar typing in Text), not in labels.

---

## 1. Audit of the current editor (lib/project_editor.dart)

| # | Area | What we have today | Problem |
|---|---|---|---|
| A1 | Layout | Preview takes "whatever is left" | Preview size jumps when a panel opens (panels 70–260 px) or lanes are added |
| A2 | Toolbar | One horizontal row of 12 tools (Edit, Audio, Text, Stickers, Overlay, Effects, Templates, Filters, Adjust, Ratio, Background) | Half off screen; looks like a well-known competitor's bar; Ratio / Background are settings, not tools |
| A3 | Panels | The panel REPLACES the toolbar; 3 header styles (`_panelHeader`, custom rows, effects header) | Tools and properties mixed; no consistent "what am I editing" header |
| A4 | Selection | 4 separate selections (`_sel`, `_fxSel`, `_auSel`, `_txSel`), 4 copy-pasted bar widgets | Easy to get out of sync; 3× duplicated code |
| A5 | Timeline | Fixed 56 px/s, white centre line, white "+" tile at the end, lanes 24 px, bars 20 px, trim handles 14 px | Too small to touch (needs 44 dp), text 10 px, lanes push the preview up, recognisable competitor details |
| A6 | Colours | Pure black #000, hard-coded colours per widget (0xFF9C4DFF, 0xFF17703A, 0xFF9A5A1C …), Material default lavender sliders/chips | No system; purple / orange / green track code mirrors a competitor |
| A7 | Type | 9.5–22 px sizes chosen per widget | No scale; several labels below 11 px |
| A8 | Effects browser | Horizontal strip of 112 px cards, 136 cards (styles as separate cards), "Trending" tab | Only 3½ cards visible; long scroll; copied naming |
| A9 | Status | Status text and export progress print under the timeline | Moves the layout, mixes messages with the timeline |
| A10 | Sizes | Built for one ~390 dp phone | Small phones lose the preview; tablets / landscape waste space |

Keep (good): one-screen workspace, live preview through the engine, the
effect / text / sound bars with drag + trim, Sample | My clip, on-video
handles, the gradient Export.

## 2. Design tokens – "Fly Studio Night"

File: `lib/studio/studio_tokens.dart` (one place, no hard-coded colours).

### Colours
| Token | Value | Use |
|---|---|---|
| ink0 | #0A0A12 | page, stage background |
| ink1 | #12121C | timeline, dock |
| ink2 | #1A1A27 | cards, chips, icon buttons |
| ink3 | #242436 | pressed / selected tile |
| sheet | #161622 | properties sheet |
| line | #FFFFFF @ 8% | hairlines |
| lineStrong | #FFFFFF @ 18% | outlines of secondary buttons |
| text1 / text2 / text3 | #F5F5FA / #B4B4C6 / #8A8AA0 | primary / secondary / hint (all ≥ 4.5:1 on ink1) |
| aurora | #FF4B6E → #9C4DFF → #3A8DFF | see the Aurora rule below |
| focus | #B98BFF | selected chip outline, keyboard focus |
| danger | #FF8FA3 | Delete text / icon |

**Aurora rule (R2).** The red→blue gradient appears only on: (1) the
Export button (the one primary action), (2) the playhead needle, (3) the
SELECTED thing – effect / mood card ring, the active dock tool's dash, the
selected bar's glow – and (4) slider fill. Never on backgrounds, headers,
text, chips or icons.

**Calm rule (R8).** At most one gradient element per zone, no shadows,
no borders where a surface change is enough, icons outline-only, one
hint line per sheet, no badges except "selected" and counts.

### Track colours (Fly's own, from the brand)
| Track | Colour | Bar (normal) | Bar (selected) |
|---|---|---|---|
| Story (clips) | #E8E8F0 | thumbnails + 1.5 px light frame | 2.5 px white frame + white handles |
| Layers (text, stickers, photos) | Coral #FF5C7A | 22% fill + 1 px border, white text | solid, ink text |
| Magic (effects) | Sky #4C8DFF | same | same |
| Sound (music) | Amber #FFB23E | same + waveform stripes | same |

### Type – Roboto (on every Android, 0 KB added)
display 22/900 · title 17/700 · body 14/500 · label 12/600 (+0.2 tracking)
· caption 11/500 (minimum anywhere) · timecode Roboto 13/600 with tabular
figures (no extra font file). Respect the system font scale up to 1.3×.

### Space, shape, motion
space 4 · 8 · 12 · 16 · 24 · 32 · radius 6 (bars) · 10 (chips) · 14
(cards, tiles, stage) · 20 (sheets) · pill · touch target ≥ 44 dp ·
motion 160 ms ease-out (state), 240 ms emphasized (sheets), selection
spring 0.96 → 1 · light haptic on select / snap. No drop shadows: depth
comes from ink layers + hairlines.

## 3. Icon & button system

**Icons** – `lib/studio/studio_icons.dart`: our own path icons (24 grid,
1.8 px round stroke, filled only when active), drawn with CustomPainter –
no icon font, no package. Family icons carry the track colour: Moods,
Magic (sky), Text (coral), Layers (coral), Sound (amber), Look, Canvas,
plus Split, Trim, Speed, Volume, Duplicate, Earlier, Later, Delete, Play,
Pause, Add, Done, Close, Replace, Place, Whole video.

**Buttons** (`studio_buttons.dart`)
| Component | Look | Rule |
|---|---|---|
| PrimaryButton | aurora pill, 40 h, white 14/700 | one per screen (Export) |
| SecondaryButton | ink2 pill, lineStrong border | Media, Cancel-type actions |
| GhostButton | text only, text2 | low-emphasis |
| IconButton | 40 circle ink2 | Close, Done |
| PlayButton | 48 circle text1, ink icon | transport |
| DockTool | 66×60 tile, icon 22 + ALWAYS-visible label 12/600 (R3); active = ink3 tile, label 700, short aurora dash | dock / rail – never icon-only |
| ActionTile | 68 h ink2 tile in a grid | sheet actions (Split, Duplicate …) |
| Chip / TabPill | 32 h; selected = text1 fill + ink text | tabs, categories, styles |
| Segmented | pill with 2 parts | Sample \| My clip |
| StudioSlider | 4 px track, aurora fill, 20 px white thumb, value in mono | all sliders |
| ColorDot | 30 circle, white ring when chosen | text / sticker colours |
| StyleSwatches | 12×8 rounded rectangles in the style colours + "4 styles" caption; chosen one ringed | effect / mood cards (R5) |

**Selected states (R4).** Every selectable thing shows selection in 3 ways
at once, never colour alone: (1) aurora ring 2.5 px, (2) a white check
badge (cards) or white handles (bars, clips), (3) bolder label / solid bar
fill. Effects already on the video also show an "On video" tag on their
card.

## 4. The workspace – four zones, always in the same place

```
┌ Top bar ─ Close · Project name/“Saved · 0:09” · Canvas chip (9:16) · Export ┐
│ STAGE   (preview; on-video handles; never covered on phones)                │
│ Transport ─ timecode · Play · + Media                                       │
│ RIVER   (timeline: Story + Layers + Magic + Sound, family icons at left)    │
└ DOCK (nothing selected)  ⇄  PROPERTIES SHEET (something selected) ─────────┘
```

- **Stage** gets a fixed height per state, so it never jumps while you
  work: Create state (dock) = big; Editing state (sheet) = one smaller
  step, animated once.
- **River**: centre playhead = aurora needle with a dot cap. Tracks in a
  fixed order: Story, Layers, Magic, Sound, each with its family icon in a
  76 dp sticky gutter that shows the family colour chip AND its name
  (Story, Layers, Magic, Sound) at 11/700 (R6); bar text is white on the
  22% tint, ink on the solid selected bar. Bars 28 px (lanes 34), text 11 px, trim handles
  18 px visual / 44 px touch. In the Editing state other tracks fold to
  14 px colour lines so the selected item stays big. More than 2 lanes in
  a family fold into "+N" until tapped.
- **Dock** (create): Moods · Magic · Text · Layers · Sound · Look (6
  tools, all visible on a 360 dp phone, each with its text label – R3). Canvas (ratio + background) moves
  to the top-bar chip; Media (add clips) to the transport row.
- **Properties sheet** (edit): handle · header (track dot, name, time
  range in mono, Done) · tab pills · content (action grid, sliders, chips,
  library) · one hint line. Heights: compact 250 dp, expanded 60% (Magic,
  Moods, Look libraries).

## 5. Screen by screen (all on the canvas)

1. **Workspace – nothing selected**: top bar, big stage, transport, river
   with all tracks, dock.
2. **Clip selected**: sheet "Clip 1 · Video", tabs Edit · Trim · Speed ·
   Volume · Mood; Edit = Split, Duplicate, Earlier, Later, Delete; clip
   gets white handles in the river; tip "long-press a clip and drag".
3. **Magic library** (Dock → Magic): expanded sheet, Sample | My clip,
   feeling categories, 3-column grid, ONE card per effect (136 cards → 59
   cards). Under each card: STYLE SWATCHES (small colour rectangles + "4
   styles"), never round dots, so they cannot be read as pagination (R5);
   the style is chosen in the effect sheet with named chips. Tap = add a
   3 s bar at the playhead. The selected / added card has the aurora ring,
   a white check badge and an "On video" tag (R4); the stage shows the
   effect live above.
4. **Editing a layer (text)**: coral box on the stage with a Remove knob
   and one "turn + resize" knob (one finger; pinch / twist still work);
   sheet tabs Edit · Font · Style · Color · Motion; fonts show real letters
   ("မြန်မာ", "Aa").
5. **Effect bar selected**: sheet tabs Edit (Replace, Place on video /
   Full screen, Whole video, Delete) · Size; Focus Card shows its card box
   on the stage (same as today, restyled).
6. **Sound bar selected**: sheet tabs Volume · Fades (in / out) · Edit
   (Whole video, Delete); amber waveform bar.
7. **Look** (Dock → Look): tabs Filters (8 swatches + strength) · Adjust
   (6 sliders) – today's Filters + Adjust panels merged.
8. **Canvas** (top-bar chip): sheet with Ratio (4 cards) and Background
   (Blur / colours) – today's Ratio + Background panels merged.
9. **Export**: sheet over the dock with progress ring, then the result
   card (file, size, time, sound report) – replaces the status line.
10. **Empty project**: stage shows "Add your first clip" with a big
    Media button; dock disabled until there is a clip.

## 6. Every Android screen size

| Class | Rule (width × height in dp) | Layout |
|---|---|---|
| Compact | width < 360 or height < 700 | Top bar 52, stage smaller, river shows Story + one folded "N tracks" line, dock 4 tools + More (Layers, Look in More), sheet compact 230 |
| Regular | 360–599 wide | As on the canvas (390 × 844) |
| Tall | height ≥ 860 | Stage grows; river shows 2 lanes per family before folding |
| Wide (tablet, foldable open, phone landscape) | width ≥ 600 | Dock becomes a left rail (with Canvas), Properties panel stays open on the right (340 dp), river across the bottom with a time ruler; nothing covers the stage |

**Small-screen check (R7)** – every step is checked on these sizes
before Ko's phone test (Flutter device preview / emulator profiles):

| Profile | dp | Must hold |
|---|---|---|
| Small (e.g. Galaxy A0x, 5") | 360 × 640 | stage ≥ 230 dp tall, all 5 dock items + labels, sheet compact fits, no overflow stripes |
| Common | 360 × 800, 393 × 873 | regular layout |
| Big phone | 412 × 915 | tall layout |
| Font scale 1.3× | any | labels ellipsize, no clipped buttons |
| Landscape / tablet | ≥ 600 wide | wide layout |

Also: safe-area insets (cut-outs, gesture bar), text scale up to 1.3×
(labels ellipsize, tiles grow), all touch targets ≥ 44 dp, one-hand
reach: every frequent action is in the bottom half.

## 7. Original names

Ids in the engine (`fx_tape`, `fx_window~2` …) stay the same – only the
labels and groups change, so saved projects and the engine are untouched.

**Tool families**: Templates → **Moods** · Effects → **Magic** ·
Stickers + Overlay → **Layers** (Sticker, Emoji, Photo) · Text → Text ·
Audio → **Sound** · Filters + Adjust → **Look** (matches Fly's existing
"Fly Looks") · Ratio + Background → **Canvas** · Edit → clip sheet.

**Tracks**: Story · Layers · Magic · Sound.

**Magic categories** (by feeling): Fly Picks (was Trending) · Dreamy ·
Love · Party · Nature · Sky · Weather · Glitch · Motion · Frames · Retro.

**Effect renames** (the ones that followed a competitor's list):
| Today | Fly name | | Today | Fly name |
|---|---|---|---|---|
| Autumn Wind | Maple Drift | | Earthquake | Tremor |
| Flash Beat | Pulse Flash | | Beat Bounce | Bounce |
| Fire Frame | Ember Edge | | Wobble | Jelly |
| Electric Frame | Volt Edge | | Soft Blur | Dream Blur |
| Glow Frame | Halo Edge | | Fade In/Out | Curtain |
| Hex Lights | Honeycomb Glow | | Damaged Tape | VHS Tear |
| Sequins | Glitter Rain | | Old Film | Cine Reel |
| Diamond Lights | Prism Tiles | | RGB Split | Prism Split |
| Desert Haze | Golden Haze | | Retro Flicker | Projector |
| Sound Wave | Equalizer Glow | | Color Blink | Neon Blink |
| Like Bubbles | Fly Reactions | | Light Sweep | Shine |
| Floating Hearts | Heart Balloons | | Magic Dust | Stardust |
| Fire Burst | Ember Rise | | Window Card | Focus Card |

Styles (`~1..~3`) become **style dots** under one card, each with a name
in the sheet ("Ember Edge · Blue").

## 8. Nothing is lost – where every feature goes

| Feature today | New place |
|---|---|
| Add clips (pickMultipleMedia), end "+" tile | Transport "+ Media"; empty-state button |
| Play / pause / scrub / timecode | Transport + river (same) |
| Split, Duplicate, Move left/right, Delete, long-press reorder | Clip sheet → Edit; long-press drag stays |
| Trim (video) / Duration (photo), Speed, Clip volume | Clip sheet tabs Trim, Speed, Volume |
| Template per clip, Apply to all | Clip sheet → Mood; Dock → Moods (library + Apply to all) |
| Effects library, Sample \| My clip, Clear | Dock → Magic (style dots, Clear in header menu) |
| Effect bar: move, trim, Replace, Place on video, Size, Whole video, Delete, Focus Card box | Effect sheet + stage handles (same gestures) |
| Filters, Adjust (8 values) | Dock → Look |
| Ratio, Fit, Background | Top-bar Canvas chip |
| Audio: Add music, Original On/Off, bar move/trim, Volume, Fades, Whole video, Delete | Dock → Sound; Sound sheet |
| Text: presets, add, edit words, font, style, colour, animation in/loop/out, duplicate, centre, delete, on-video drag/pinch/twist/double-tap | Dock → Text; Layer sheet + stage |
| Stickers (emoji, shapes), Photo overlay (frame, blend, colour) | Dock → Layers; Layer sheet |
| Export, progress, saved path, sound report | Export button → Export sheet |

## 9. Component structure (Flutter)

```
lib/studio/
  studio_tokens.dart      colours, type, space, radius, motion, breakpoints
  studio_theme.dart       dark ThemeData (Slider, Chip, Dialog, SnackBar)
  studio_icons.dart       Fly path icons (CustomPainter)
  studio_buttons.dart     Primary, Secondary, Ghost, Icon, Play, DockTool,
                          ActionTile, TabPills, Segmented, StudioSlider, ColorDot
  studio_layout.dart      StudioScaffold: picks Compact / Regular / Wide and
                          places TopBar, Stage, Transport, River, Dock / Sheet
  top_bar.dart            close, title, Canvas chip, Export
  stage.dart              engine Texture + overlays (Placer, LayerHandles)
  transport.dart          timecode (ValueListenableBuilder), Play, Media
  river/river.dart        scroll + needle + ruler (wide)
  river/story_strip.dart  clip thumbnails, long-press reorder, handles
  river/track_bar.dart    ONE bar widget for Layers / Magic / Sound
  river/track_family.dart lanes + "+N" folding
  dock.dart               6 tools (rail on wide screens)
  sheets/property_sheet.dart  header + tabs + body + hint (shared)
  sheets/clip_sheet.dart, magic_sheet.dart, layer_sheet.dart,
  sheets/sound_sheet.dart, look_sheet.dart, canvas_sheet.dart,
  sheets/moods_sheet.dart, export_sheet.dart
  library/library_grid.dart   3-col cards + style dots (Magic, Moods)
```

`project_editor.dart` keeps ALL state and engine calls (`_ch.invokeMethod`
…) – it becomes the controller and passes values + callbacks into these
widgets. The engine (Kotlin, shaders, channels) is not touched. Time,
status and progress stay ValueNotifiers (no flicker). Display names /
categories live in `fly_catalog.dart`; ids unchanged.

## 10. Implementation plan (after approval – one step at a time, each built, tested on Ko's phone, committed)

| Step | What | Risk |
|---|---|---|
| P0.1 | ONLY colours + typography on today's screen (R9). Tokens + dark theme; no layout, no new widgets, no engine change | low |
| P0.2 | River: one generic TrackBar, family gutter, aurora needle, 28 px bars, 44 dp handles, folding | medium (gestures) |
| P0.3 | Dock + top-bar Canvas chip + transport Media; old tool row removed, every tool reachable (table §8) | medium |
| P0.4 | Property sheet for clip, effect, layer, sound; stable stage heights | medium |
| P0.5 | Magic / Moods libraries: grid + style dots + Fly names + Fly Picks; Look and Canvas sheets | low |
| P0.6 | Screen classes: Compact, Tall, Wide (rail + side panel) | medium |
| P0.7 | Export sheet, empty state, haptics, motion polish | low |

Each step: full files, `flutter analyze`, build with `--build-number`,
Ko tests, LAB_NOTES + AI_CONTEXT updated when it works. Regression check
after every step: the §8 table, one export with sound + text + effect.

## 11. P0.1 – exact files (approved scope: colours + typography only)

| File | Change |
|---|---|
| `lib/studio/studio_tokens.dart` | NEW. `StudioColors` (ink0-3, sheet, line, text1-3, aurora, focus, danger, track colours), `StudioText` (display, title, body, label, caption, timecode with tabular figures), all `const`. |
| `lib/studio/studio_theme.dart` | NEW. `StudioTheme.dark` = ThemeData (dark) using the tokens: scaffold / app bar / bottom sheet / dialog / snackbar colours, Slider, Chip / ChoiceChip, TextButton / FilledButton, IconButton, ProgressIndicator colours and the text theme. Default component SIZES kept, so nothing moves. |
| `lib/project_editor.dart` | CHANGED (full file). Wraps the screen in `Theme(data: StudioTheme.dark)`; every hard-coded UI colour (≈ 70: Colors.white / white54 / black, 0xFF9C4DFF, 0xFF5B3A8C, 0xFF9A5A1C, 0xFF17703A, 0xFF1DB954 …) and every `TextStyle(fontSize: …)` (35, today 9.5–30 px) is replaced by a token. Track bars take the new colours (Magic sky, Layers coral, Sound amber, selected = solid + ink text). Labels below 11 px go to 11. NO widget added, removed or re-ordered, NO size / padding / position change, NO logic or channel change. |
| `LAB_NOTES.md` | Notes for P0.1 (after Ko's test). |

NOT changed in P0.1: all Kotlin (engine, shaders, audio, text), `fly_catalog.dart`
(names come in P0.5), `main.dart`, `editor_lab.dart` (Photo → Video lab
screen), `pubspec.yaml` (no new package or font), assets. User CONTENT
colours (text colour palette, background colours, filter swatches) stay
exactly as they are – they are the user's choices, not UI.

Check after P0.1: `flutter analyze lib` clean; build with
`--build-number`; on the phone every feature in §8 still works, one export
with sound + text + effect; screenshots on a 360 × 640 profile and Ko's
phone.
