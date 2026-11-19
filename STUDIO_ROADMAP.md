# Fly Creative Studio - product direction and roadmap

_Written 19 Nov 2026 at Ko's request. Direction only: nothing in this file
is built yet unless the "Where we are" list says so._

## 1. Product principles (Ko, 19 Nov 2026 - every task must follow these)

Fly Video Editor must have its **own original identity**.

- **Do not copy** CapCut (or any other editor): not its UI, branding,
  icons, layouts, templates, animations or proprietary designs.
- Other editors are **functional references only** - to learn which
  editing capabilities people expect, never how they look or are named.
- Think independently; prefer ideas that make Fly different.

Fly Creative Studio must be:

1. **Easier for beginners** - a first video in under a minute, no editing
   words needed, Myanmar content first-class.
2. **Powerful for advanced creators** - full timeline, precise control,
   nothing hidden for good.
3. **Visually unique** - Fly's own look, colours, icons and motion.
4. **Fast and reliable** - smooth on cheap phones, never loses work.
5. **Integrated directly with Fly Social** - made to be posted, remixed and
   discovered on Fly.

Rule for every assistant: before giving code for the editor, check the
change against these five points and the originality checklist (section 3).

## 2. Where we are (Fly Lab, works on Ko's phone)

- Project editor: timeline, split, trim, duplicate, speed, drag to reorder.
- 42 templates + 136 effect cards (styles), effects track with time and place.
- Audio: music from the phone, clip sound, volume, fades, AAC in export.
- Text: 11 OFL fonts (5 Myanmar), styles, colours, animations, presets.
- Stickers (emoji + 16 shapes drawn in code), photo overlay with frames
  and blend modes, Window Card.
- All on the phone, no paid cloud.

## 3. Originality checklist - what in the current lab looks too familiar

Built fast while learning which features people expect. Before the merge
into Fly, these get a Fly version (Phase 0):

| Now | Why it is a problem | Fly direction |
|---|---|---|
| Bottom tool bar in a long row (Edit, Audio, Text, Stickers, Overlay, Effects, Filters, Adjust, Ratio, Background) | Same order and pattern as the best-known editor | **Fly Dock** (section 4.4) |
| White square "+" at the end of the clip strip, centred white playhead | Very recognisable layout detail | Fly's own add button and playhead (amber "needle") |
| Track colours purple effects / orange text / green audio | Close to a competitor's colour code | Fly track palette from the brand gradient (amber, coral, sky, mint) |
| "Trending" tab name | Copied naming | **Fly Picks** - ranked by what Fly users really use |
| Effect names that mirror a competitor's list one-to-one (e.g. "Autumn Wind" is identical; Flash Beat, Fire Frame, Electric Frame, Hex Lights, Sequins, Diamond Lights, Desert Haze, Sound Wave, Like Bubbles were planned from its screenshots) | Shaders are our own code, but the set and names follow their catalogue | Rename to Fly names, re-group by **feeling**, add Myanmar-first effects |
| Material Icons everywhere | Generic, no identity | A small Fly icon set (rounded, two-tone) |

Already original: shader code, the sticker shapes, the Sample picture,
fonts are OFL, Window Card is a Fly effect (not an overlay+mask recipe).

## 4. Original UX ideas for Fly

### 4.1 Feeling Start (beginners)
Start from a feeling, not from tools: **Love, Hype, Calm, Funny, Memory,
Festival**. Fly builds a first draft from the user's clips - a matching
template, 1-2 effects, a text style and cuts on the music's beats. The user
can accept, shuffle ("another one") or open it in the full editor. Matches
how Ko designs ("ခံစားချက်" first).

### 4.2 Two depths, one project - Quick and Studio
- **Quick**: big cards, one decision per screen (clips -> feeling -> words
  -> music -> post), Burmese labels, no timeline.
- **Studio**: the full timeline.
- Same project file; switch any time without losing anything.

### 4.3 "On screen now" stack (phone-friendly timeline)
Many lanes are hard on a phone. Next to the preview, a small vertical
stack shows **only what is on screen at the playhead** (clip, effects,
texts, stickers, music). Tap one to edit it. The long multi-lane timeline
becomes optional (Studio).

### 4.4 Fly Dock (contextual, one-hand)
A dock near the thumb that shows **only the actions for what is selected**
(nothing selected = create actions). Same gestures everywhere: drag =
move in time / on screen, pinch = size, twist = turn, double-tap = edit,
long-press = more. Learn once, use on every layer.

### 4.5 Beat Magnet
On-device beat detection for the chosen song (free, no server). Cuts,
effect bars and text bars snap to beats; one tap "Cut to the beat";
flash / bounce effects time themselves.

### 4.6 Looks you can share - Remix on Fly
Every export carries a tiny **Look recipe** (JSON: effects, text styles,
timing, ratio - no media, no music). On a Fly post viewers tap **"Use this
look"** and the editor opens with **their own** clips in that look. The
creator is credited. Fly Picks ranks looks by real use. Fly gets its own
template ecosystem made by its users - no copied or stock templates.

### 4.7 Fly safe zones
A toggle that shows where Fly's feed UI (caption, buttons, profile)
covers the video; texts and stickers are nudged out of those areas.

### 4.8 Myanmar first
Unicode Myanmar fonts (done), Zawgyi detection with one-tap convert while
typing, Burmese captions on the phone later (app UI text itself stays
English - Fly rule), and seasonal
packs drawn by us: Thingyan water, Thadingyut lights, Tazaungdaing
lanterns.

### 4.9 Never lose work
Autosave after every change, a visual undo history ("go back to 2 minutes
ago"), drafts that survive the app being killed, export that can continue
in the background with a notification.

### 4.10 Light-phone mode and slow data
Lower-resolution preview (proxy) on weak phones, export queue, upload
presets for slow mobile data (smaller files that still look good on Fly).

### 4.11 Fly look and feel
Brand gradient (amber -> coral), soft glass panels, Fly icon set, short
living preview loops on effect cards, gentle haptics on snaps.

## 5. Roadmap (one task at a time, each tested on Ko's phone)

- **Phase 0 - Originality pass** (small, before new features): Fly names
  and feeling groups for effects, Fly Picks, track palette, playhead and
  add button, first Fly icons. No new engine work.
- **Phase 1 - Core gaps**: transitions (our own set), video overlay
  (picture in picture), captions on the phone (open-source Whisper),
  simple keyframes (move / size over time).
- **Phase 2 - Fly UX**: Feeling Start, Quick / Studio, Fly Dock, "On
  screen now" stack, Beat Magnet.
- **Phase 3 - Fly Social**: post straight from the editor, Look recipes +
  "Use this look", Fly safe zones, drafts.
- **Phase 4 - Speed and reliability**: autosave + history, proxies,
  background export, tests on cheap phones, APK size budget.
- **Phase 5 - Merge into Fly + Play Store**: move the editor into Fly
  (Impeller OFF rule, build numbers), licences page (fonts OFL), gpupixel
  issue #253 check, privacy text for on-device processing.

Order inside a phase is decided with Ko. Nothing is built until Ko says go.

Phase 0 proposal (19 Nov 2026): PHASE0_DESIGN.md + the "Fly Creative
Studio - Phase 0 design" canvas. Waiting for Ko's approval.
