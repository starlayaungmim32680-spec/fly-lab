// Fly Creative Studio – design tokens ("Fly Studio Night").
//
// Phase 0 / P0.1 (19 Nov 2026): the ONE place for the editor's UI colours
// and text styles. See PHASE0_DESIGN.md §2. Widgets must use these instead
// of hard-coded colours. User CONTENT colours (text colours, background
// colours, filter swatches the user picks) are not UI and do not live here.
//
// Aurora rule: the red -> blue Fly gradient is only for the primary action,
// the playhead, the SELECTED thing and slider fill.

import 'package:flutter/material.dart';

/// UI colours of the studio.
class StudioColors {
  StudioColors._();

  // Ink surfaces (depth by layers, no shadows).
  static const Color ink0 = Color(0xFF0A0A12); // page, stage
  static const Color ink1 = Color(0xFF12121C); // timeline, toolbar
  static const Color ink2 = Color(0xFF1A1A27); // cards, chips, tiles
  static const Color ink3 = Color(0xFF242436); // pressed / selected tile
  static const Color sheet = Color(0xFF161622); // panels, dialogs

  // Lines.
  static const Color line = Color(0x14FFFFFF); // 8 %
  static const Color lineStrong = Color(0x2EFFFFFF); // 18 %

  // Text (all >= 4.5:1 on ink1).
  static const Color text1 = Color(0xFFF5F5FA);
  static const Color text2 = Color(0xFFB4B4C6);
  static const Color text3 = Color(0xFF8A8AA0);

  // Brand.
  static const Color coral = Color(0xFFFF4B6E);
  static const Color violet = Color(0xFF9C4DFF);
  static const Color sky = Color(0xFF3A8DFF);
  static const Color focus = Color(0xFFB98BFF); // selected chip, active tool
  static const Color danger = Color(0xFFFF8FA3);

  /// The Fly aurora (red -> violet -> blue), left to right.
  static const LinearGradient aurora = LinearGradient(
    colors: [coral, violet, sky],
    stops: [0.0, 0.55, 1.0],
  );

  /// The aurora top to bottom (playhead needle).
  static const LinearGradient auroraVertical = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [coral, violet, sky],
    stops: [0.0, 0.6, 1.0],
  );

  // Track colours (Fly's own). Solid = selected bar, tint = normal bar
  // (30 % of the colour over ink1, opaque so it is cheap to draw).
  static const Color story = Color(0xFFE8E8F0);
  static const Color layers = Color(0xFFFF5C7A); // text, stickers, photos
  static const Color layersTint = Color(0xFF592838);
  static const Color magic = Color(0xFF4C8DFF); // effects
  static const Color magicTint = Color(0xFF233760);
  static const Color sound = Color(0xFFFFB23E); // music
  static const Color soundTint = Color(0xFF594226);

  /// The little grip lines inside white trim handles.
  static const Color grip = Color(0x8A0A0A12);
}

/// UI text styles of the studio (Roboto, the Android system font).
class StudioText {
  StudioText._();

  static const TextStyle display = TextStyle(
      fontSize: 22, fontWeight: FontWeight.w900, color: StudioColors.text1);
  static const TextStyle title = TextStyle(
      fontSize: 17, fontWeight: FontWeight.w700, color: StudioColors.text1);

  /// Panel headers inside today's fixed-height rows (title comes in P0.4).
  static const TextStyle heading = TextStyle(
      fontSize: 14, fontWeight: FontWeight.w700, color: StudioColors.text1);
  static const TextStyle body = TextStyle(
      fontSize: 14, fontWeight: FontWeight.w500, color: StudioColors.text2);

  /// Status messages under the timeline.
  static const TextStyle status = TextStyle(
      fontSize: 12, fontWeight: FontWeight.w500, color: StudioColors.text2);
  static const TextStyle label = TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w600,
      letterSpacing: 0.2,
      color: StudioColors.text1);
  static const TextStyle labelStrong = TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w800,
      letterSpacing: 0.2,
      color: StudioColors.text1);
  static const TextStyle caption = TextStyle(
      fontSize: 11, fontWeight: FontWeight.w500, color: StudioColors.text2);
  static const TextStyle captionStrong = TextStyle(
      fontSize: 11, fontWeight: FontWeight.w700, color: StudioColors.text1);

  /// Hints and small section titles (quiet).
  static const TextStyle hint = TextStyle(
      fontSize: 11, fontWeight: FontWeight.w500, color: StudioColors.text3);

  /// Category tabs.
  static const TextStyle tabOn = TextStyle(
      fontSize: 13, fontWeight: FontWeight.w800, color: StudioColors.text1);
  static const TextStyle tabOff = TextStyle(
      fontSize: 13, fontWeight: FontWeight.w500, color: StudioColors.text3);

  /// Library card names.
  static const TextStyle cardOn = TextStyle(
      fontSize: 11, fontWeight: FontWeight.w800, color: StudioColors.text1);
  static const TextStyle cardOff = TextStyle(
      fontSize: 11, fontWeight: FontWeight.w500, color: StudioColors.text2);

  /// Timecode: tabular figures so it does not jiggle while playing.
  static const TextStyle timecode = TextStyle(
    fontSize: 13,
    fontWeight: FontWeight.w600,
    color: StudioColors.text1,
    fontFeatures: [FontFeature.tabularFigures()],
  );
}

/// Sizes of the studio's timeline (P0.2). Touch areas are never under 44.
class StudioSizes {
  StudioSizes._();

  static const double lane = 34; // one lane of a track family
  static const double bar = 28; // a bar inside a lane
  static const double handle = 18; // visible trim handle
  static const double touch = 44; // finger area of a trim handle
  static const double gutter = 76; // the track names on the left
  static const double laneGap = 4; // space above each track family
  static const int foldAfter = 2; // lanes shown before "+N"
}
