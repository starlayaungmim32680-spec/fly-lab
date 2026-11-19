// Fly Creative Studio – dark theme built from the tokens (P0.1).
//
// Only COLOURS and TEXT are set here. Component sizes, paddings and shapes
// stay Flutter's defaults, so wrapping a screen in this theme does not move
// anything on it.

import 'package:flutter/material.dart';

import 'studio_tokens.dart';

class StudioTheme {
  StudioTheme._();

  static final ThemeData dark = _build();

  static ThemeData _build() {
    final ThemeData base = ThemeData.dark(useMaterial3: true);
    const ColorScheme scheme = ColorScheme.dark(
      primary: StudioColors.violet,
      onPrimary: StudioColors.text1,
      secondary: StudioColors.coral,
      onSecondary: StudioColors.text1,
      secondaryContainer: StudioColors.ink3,
      onSecondaryContainer: StudioColors.text1,
      surface: StudioColors.ink1,
      onSurface: StudioColors.text1,
      onSurfaceVariant: StudioColors.text2,
      surfaceContainerHighest: StudioColors.ink2,
      outline: StudioColors.lineStrong,
      outlineVariant: StudioColors.line,
      error: StudioColors.danger,
    );
    return base.copyWith(
      colorScheme: scheme,
      scaffoldBackgroundColor: StudioColors.ink0,
      canvasColor: StudioColors.ink0,
      dividerColor: StudioColors.line,
      textTheme: base.textTheme.apply(
        bodyColor: StudioColors.text1,
        displayColor: StudioColors.text1,
      ),
      iconTheme: const IconThemeData(color: StudioColors.text1),
      appBarTheme: const AppBarTheme(
        backgroundColor: StudioColors.ink0,
        foregroundColor: StudioColors.text1,
        surfaceTintColor: Colors.transparent,
        titleTextStyle: StudioText.title,
      ),
      snackBarTheme: const SnackBarThemeData(
        backgroundColor: StudioColors.ink3,
        contentTextStyle: StudioText.label,
      ),
      sliderTheme: base.sliderTheme.copyWith(
        activeTrackColor: StudioColors.violet,
        inactiveTrackColor: StudioColors.ink3,
        thumbColor: StudioColors.text1,
        overlayColor: StudioColors.violet.withAlpha(40),
        valueIndicatorColor: StudioColors.ink3,
        activeTickMarkColor: Colors.transparent,
        inactiveTickMarkColor: Colors.transparent,
      ),
      chipTheme: base.chipTheme.copyWith(
        backgroundColor: StudioColors.ink2,
        selectedColor: StudioColors.ink3,
        checkmarkColor: StudioColors.focus,
        side: const BorderSide(color: StudioColors.line),
        labelStyle: StudioText.label,
        secondaryLabelStyle: StudioText.label,
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: StudioColors.violet,
        linearTrackColor: StudioColors.ink3,
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(foregroundColor: StudioColors.focus),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: StudioColors.violet,
          foregroundColor: StudioColors.text1,
        ),
      ),
      textSelectionTheme: const TextSelectionThemeData(
        cursorColor: StudioColors.coral,
        selectionColor: Color(0x559C4DFF),
        selectionHandleColor: StudioColors.coral,
      ),
      inputDecorationTheme: const InputDecorationTheme(
        hintStyle: StudioText.hint,
        focusedBorder: UnderlineInputBorder(
            borderSide: BorderSide(color: StudioColors.focus)),
      ),
    );
  }
}
