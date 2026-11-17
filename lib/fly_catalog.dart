// Fly Lab - the catalog of templates, effects and filters (shared by the
// Project editor, 17 Nov 2026). Every item lives in exactly ONE category.
// The ids are the shader ids in PhotoTemplates.kt.
import 'package:flutter/material.dart';

class FlyItem {
  const FlyItem(this.id, this.label, this.cat);
  final String id;
  final String label;
  final String cat;
}

const List<FlyItem> flyTemplates = [
  FlyItem('dreamy', 'Dreamy', 'Love'),
  FlyItem('heartbeat', 'Heartbeat', 'Love'),
  FlyItem('pink_sparkle', 'Pink Sparkle', 'Love'),
  FlyItem('love_frame', 'Love Frame', 'Love'),
  FlyItem('petals', 'Petals', 'Love'),
  FlyItem('pulse', 'Pulse Zoom', 'Beat'),
  FlyItem('flash', 'Flash Beat', 'Beat'),
  FlyItem('glitch', 'Glitch', 'Beat'),
  FlyItem('shake', 'Shake', 'Beat'),
  FlyItem('split', 'Split', 'Beat'),
  FlyItem('zoom_blur', 'Zoom Blur', 'Beat'),
  FlyItem('cinematic', 'Cinematic', 'Cinema'),
  FlyItem('focus', 'Focus In', 'Cinema'),
  FlyItem('golden', 'Golden Hour', 'Cinema'),
  FlyItem('letterbox', 'Movie Bars', 'Cinema'),
  FlyItem('ken_burns', 'Slow Glide', 'Cinema'),
  FlyItem('light_leak', 'Light Leak', 'Cinema'),
  FlyItem('sad_rain', 'Sad Rain', 'Feel'),
  FlyItem('memory', 'Memory', 'Feel'),
  FlyItem('color_return', 'Color Return', 'Feel'),
  FlyItem('fog', 'Fog', 'Feel'),
  FlyItem('lonely', 'Lonely', 'Feel'),
  FlyItem('neon', 'Neon Lines', 'Night'),
  FlyItem('snow', 'Snow', 'Night'),
  FlyItem('fireflies', 'Fireflies', 'Night'),
  FlyItem('starry', 'Starry Night', 'Night'),
  FlyItem('confetti', 'Confetti', 'Fun'),
  FlyItem('mirror', 'Mirror', 'Fun'),
  FlyItem('kaleido', 'Kaleido', 'Fun'),
  FlyItem('rainbow', 'Rainbow', 'Fun'),
  FlyItem('disco', 'Disco', 'Fun'),
  FlyItem('retro', 'Retro Film', 'Retro'),
  FlyItem('vhs', 'VHS', 'Retro'),
  FlyItem('old_tv', 'Old TV', 'Retro'),
  FlyItem('pixel', 'Pixel', 'Retro'),
  FlyItem('spin', 'Spin', 'Beat'),
  FlyItem('vertigo', 'Dolly Zoom', 'Cinema'),
  FlyItem('aurora', 'Aurora', 'Night'),
  FlyItem('comic', 'Comic', 'Art'),
  FlyItem('pop_art', 'Pop Art', 'Art'),
  FlyItem('sketch', 'Sketch', 'Art'),
  FlyItem('duotone', 'Duotone', 'Art'),
];

const List<String> flyTemplateCats = [
  'For You',
  'Love',
  'Beat',
  'Cinema',
  'Feel',
  'Night',
  'Fun',
  'Art',
  'Retro',
];

const List<String> flyForYou = [
  'love_frame',
  'dreamy',
  'pop_art',
  'golden',
  'memory',
  'split',
  'neon',
  'sketch',
  'confetti',
  'vhs',
];

/// Effects stack on top of a template (max [flyMaxEffects]).
const List<FlyItem> flyEffects = [
  FlyItem('fx_moon', 'Big Moon', 'Sky'),
  FlyItem('fx_sunrise', 'Sunrise', 'Sky'),
  FlyItem('fx_shooting', 'Shooting Stars', 'Sky'),
  FlyItem('fx_night_sky', 'Night Sky', 'Sky'),
  FlyItem('fx_birds', 'Birds', 'Sky'),
  FlyItem('fx_clouds', 'Clouds', 'Sky'),
  FlyItem('fx_rainbow', 'Rainbow', 'Sky'),
  FlyItem('fx_leaves', 'Falling Leaves', 'Nature'),
  FlyItem('fx_sakura', 'Sakura', 'Nature'),
  FlyItem('fx_butterflies', 'Butterflies', 'Nature'),
  FlyItem('fx_fireflies', 'Fireflies', 'Nature'),
  FlyItem('fx_dandelion', 'Dandelion', 'Nature'),
  FlyItem('fx_bubbles', 'Bubbles', 'Nature'),
  FlyItem('fx_snow', 'Snow', 'Weather'),
  FlyItem('fx_rain', 'Rain', 'Weather'),
  FlyItem('fx_lightning', 'Lightning', 'Weather'),
  FlyItem('fx_fog', 'Fog', 'Weather'),
  FlyItem('fx_sparkle', 'Sparkle', 'Light'),
  FlyItem('fx_gold', 'Gold Glitter', 'Light'),
  FlyItem('fx_bokeh', 'Bokeh', 'Light'),
  FlyItem('fx_rays', 'Light Rays', 'Light'),
  FlyItem('fx_flare', 'Lens Flare', 'Light'),
  FlyItem('fx_prism', 'Prism', 'Light'),
  FlyItem('fx_spotlight', 'Spotlight', 'Light'),
  FlyItem('fx_neon_frame', 'Neon Frame', 'Light'),
  FlyItem('fx_lanterns', 'Sky Lanterns', 'Party'),
  FlyItem('fx_fireworks', 'Fireworks', 'Party'),
  FlyItem('fx_balloons', 'Balloons', 'Party'),
  FlyItem('fx_confetti', 'Confetti', 'Party'),
  FlyItem('fx_hearts', 'Hearts', 'Party'),
  FlyItem('fx_heart_burst', 'Heart Burst', 'Party'),
  FlyItem('fx_dust', 'Film Dust', 'Retro'),
  FlyItem('fx_film', 'Film Strip', 'Retro'),
];

const List<String> flyEffectCats = [
  'Sky',
  'Nature',
  'Weather',
  'Light',
  'Party',
  'Retro',
];

const int flyMaxEffects = 3;

const List<(String, Color)> flyFilters = [
  ('None', Color(0xFF555555)),
  ('Vivid', Color(0xFFFF5A5A)),
  ('Warm', Color(0xFFFFA14A)),
  ('Cool', Color(0xFF4AA8FF)),
  ('B&W', Color(0xFF9E9E9E)),
  ('Fade', Color(0xFFC9B9A6)),
  ('Pink', Color(0xFFFF7EB6)),
  ('Moody', Color(0xFF4E5D6C)),
];

/// "No template" for a clip in a project: the clip as it is.
const FlyItem flyNone = FlyItem('plain', 'None', 'For You');

String flyLabel(String id) {
  if (id == flyNone.id) return flyNone.label;
  for (final FlyItem t in flyTemplates) {
    if (t.id == id) return t.label;
  }
  for (final FlyItem t in flyEffects) {
    if (t.id == id) return t.label;
  }
  return id;
}
