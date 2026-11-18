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
  // ---- effect pack, 18 Nov 2026: 25 effects, each in up to 4 styles
  // ("fx_x~2" = style 2; native side: PhotoTemplates.varOf).
  FlyItem('fx_quake', 'Earthquake', 'Motion'),
  FlyItem('fx_quake~1', 'Earthquake Medium', 'Motion'),
  FlyItem('fx_quake~2', 'Earthquake Strong', 'Motion'),
  FlyItem('fx_quake~3', 'Earthquake Crazy', 'Motion'),
  FlyItem('fx_bounce', 'Beat Bounce', 'Motion'),
  FlyItem('fx_bounce~1', 'Beat Bounce Zoom + Flash', 'Motion'),
  FlyItem('fx_bounce~2', 'Beat Bounce Breathe', 'Motion'),
  FlyItem('fx_bounce~3', 'Beat Bounce Double', 'Motion'),
  FlyItem('fx_wobble', 'Wobble', 'Motion'),
  FlyItem('fx_wobble~1', 'Wobble Wave + Flash', 'Motion'),
  FlyItem('fx_wobble~2', 'Wobble Jelly', 'Motion'),
  FlyItem('fx_wobble~3', 'Wobble Underwater', 'Motion'),
  FlyItem('fx_flash_beat', 'Flash Beat', 'Motion'),
  FlyItem('fx_flash_beat~1', 'Flash Beat White', 'Motion'),
  FlyItem('fx_flash_beat~2', 'Flash Beat Pink', 'Motion'),
  FlyItem('fx_flash_beat~3', 'Flash Beat Color', 'Motion'),
  FlyItem('fx_soft_blur', 'Soft Blur', 'Motion'),
  FlyItem('fx_soft_blur~1', 'Soft Blur Strong', 'Motion'),
  FlyItem('fx_soft_blur~2', 'Soft Blur Dreamy', 'Motion'),
  FlyItem('fx_soft_blur~3', 'Soft Blur Blur In', 'Motion'),
  FlyItem('fx_fade', 'Fade In/Out', 'Motion'),
  FlyItem('fx_fade~1', 'Fade In/Out White', 'Motion'),
  FlyItem('fx_fade~2', 'Fade In/Out Pink', 'Motion'),
  FlyItem('fx_tape', 'Damaged Tape', 'Glitch'),
  FlyItem('fx_tape~1', 'Damaged Tape Heavy', 'Glitch'),
  FlyItem('fx_tape~2', 'Damaged Tape Blue VCR', 'Glitch'),
  FlyItem('fx_tape~3', 'Damaged Tape Pink', 'Glitch'),
  FlyItem('fx_vintage', 'Old Film', 'Glitch'),
  FlyItem('fx_vintage~1', 'Old Film B&W', 'Glitch'),
  FlyItem('fx_vintage~2', 'Old Film Faded', 'Glitch'),
  FlyItem('fx_vintage~3', 'Old Film 70s', 'Glitch'),
  FlyItem('fx_rgb', 'RGB Split', 'Glitch'),
  FlyItem('fx_rgb~1', 'RGB Split Zoom', 'Glitch'),
  FlyItem('fx_rgb~2', 'RGB Split Pulse', 'Glitch'),
  FlyItem('fx_rgb~3', 'RGB Split Wave', 'Glitch'),
  FlyItem('fx_rainbow_wave', 'Sound Wave', 'Glitch'),
  FlyItem('fx_rainbow_wave~1', 'Sound Wave Pink', 'Glitch'),
  FlyItem('fx_rainbow_wave~2', 'Sound Wave Cyan', 'Glitch'),
  FlyItem('fx_rainbow_wave~3', 'Sound Wave Gold', 'Glitch'),
  FlyItem('fx_flicker', 'Retro Flicker', 'Glitch'),
  FlyItem('fx_flicker~1', 'Retro Flicker Warm', 'Glitch'),
  FlyItem('fx_flicker~2', 'Retro Flicker Cool', 'Glitch'),
  FlyItem('fx_flicker~3', 'Retro Flicker Pink', 'Glitch'),
  FlyItem('fx_blink', 'Color Blink', 'Glitch'),
  FlyItem('fx_blink~1', 'Color Blink Blue', 'Glitch'),
  FlyItem('fx_blink~2', 'Color Blink Purple', 'Glitch'),
  FlyItem('fx_blink~3', 'Color Blink Red', 'Glitch'),
  FlyItem('fx_electric_frame', 'Electric Frame', 'Frame'),
  FlyItem('fx_electric_frame~1', 'Electric Frame Blue', 'Frame'),
  FlyItem('fx_electric_frame~2', 'Electric Frame Purple', 'Frame'),
  FlyItem('fx_electric_frame~3', 'Electric Frame Gold', 'Frame'),
  FlyItem('fx_flame_frame', 'Fire Frame', 'Frame'),
  FlyItem('fx_flame_frame~1', 'Fire Frame Blue', 'Frame'),
  FlyItem('fx_flame_frame~2', 'Fire Frame Purple', 'Frame'),
  FlyItem('fx_flame_frame~3', 'Fire Frame Pink', 'Frame'),
  FlyItem('fx_glow_frame', 'Glow Frame', 'Frame'),
  FlyItem('fx_glow_frame~1', 'Glow Frame Gold', 'Frame'),
  FlyItem('fx_glow_frame~2', 'Glow Frame White', 'Frame'),
  FlyItem('fx_glow_frame~3', 'Glow Frame Blue', 'Frame'),
  FlyItem('fx_likes', 'Like Bubbles', 'Love'),
  FlyItem('fx_likes~1', 'Like Bubbles Love', 'Love'),
  FlyItem('fx_likes~2', 'Like Bubbles Pink', 'Love'),
  FlyItem('fx_likes~3', 'Like Bubbles Gold Rain', 'Love'),
  FlyItem('fx_outline_hearts', 'Floating Hearts', 'Love'),
  FlyItem('fx_outline_hearts~1', 'Floating Hearts Pink', 'Love'),
  FlyItem('fx_outline_hearts~2', 'Floating Hearts Gold', 'Love'),
  FlyItem('fx_outline_hearts~3', 'Floating Hearts Red', 'Love'),
  FlyItem('fx_hex_bokeh', 'Hex Lights', 'Light'),
  FlyItem('fx_hex_bokeh~1', 'Hex Lights Pink', 'Light'),
  FlyItem('fx_hex_bokeh~2', 'Hex Lights Blue', 'Light'),
  FlyItem('fx_hex_bokeh~3', 'Hex Lights Rainbow', 'Light'),
  FlyItem('fx_sequins', 'Sequins', 'Light'),
  FlyItem('fx_sequins~1', 'Sequins Gold', 'Light'),
  FlyItem('fx_sequins~2', 'Sequins Pink', 'Light'),
  FlyItem('fx_sequins~3', 'Sequins Rainbow', 'Light'),
  FlyItem('fx_diamond', 'Diamond Lights', 'Light'),
  FlyItem('fx_diamond~1', 'Diamond Lights Gold', 'Light'),
  FlyItem('fx_diamond~2', 'Diamond Lights Blue', 'Light'),
  FlyItem('fx_diamond~3', 'Diamond Lights Rainbow', 'Light'),
  FlyItem('fx_shine', 'Light Sweep', 'Light'),
  FlyItem('fx_shine~1', 'Light Sweep Gold', 'Light'),
  FlyItem('fx_shine~2', 'Light Sweep Rainbow', 'Light'),
  FlyItem('fx_shine~3', 'Light Sweep Pink', 'Light'),
  FlyItem('fx_rainbow_dust', 'Magic Dust', 'Light'),
  FlyItem('fx_rainbow_dust~1', 'Magic Dust Pink', 'Light'),
  FlyItem('fx_rainbow_dust~2', 'Magic Dust Aqua', 'Light'),
  FlyItem('fx_rainbow_dust~3', 'Magic Dust White', 'Light'),
  FlyItem('fx_autumn_wind', 'Autumn Wind', 'Nature'),
  FlyItem('fx_autumn_wind~1', 'Autumn Wind Maple', 'Nature'),
  FlyItem('fx_autumn_wind~2', 'Autumn Wind Spring', 'Nature'),
  FlyItem('fx_autumn_wind~3', 'Autumn Wind Golden', 'Nature'),
  FlyItem('fx_warm_haze', 'Desert Haze', 'Nature'),
  FlyItem('fx_warm_haze~1', 'Desert Haze Sunset', 'Nature'),
  FlyItem('fx_warm_haze~2', 'Desert Haze Morning', 'Nature'),
  FlyItem('fx_warm_haze~3', 'Desert Haze Golden', 'Nature'),
  FlyItem('fx_fire_burst', 'Fire Burst', 'Party'),
  FlyItem('fx_fire_burst~1', 'Fire Burst Blue Fire', 'Party'),
  FlyItem('fx_fire_burst~2', 'Fire Burst Magic Green', 'Party'),
  FlyItem('fx_fire_burst~3', 'Fire Burst Purple', 'Party'),
];

const List<String> flyEffectCats = [
  'Trending',
  'Motion',
  'Glitch',
  'Frame',
  'Love',
  'Sky',
  'Nature',
  'Weather',
  'Light',
  'Party',
  'Retro',
];

const int flyMaxEffects = 3;

/// The "Trending" tab: a hand-picked mix from every category.
const List<String> flyTrending = [
  'fx_likes',
  'fx_flame_frame~2',
  'fx_outline_hearts',
  'fx_tape',
  'fx_bounce~1',
  'fx_sequins~1',
  'fx_electric_frame~1',
  'fx_autumn_wind',
  'fx_rainbow_wave',
  'fx_diamond',
  'fx_quake~1',
  'fx_vintage',
  'fx_blink',
  'fx_shine~1',
  'fx_fire_burst',
  'fx_hearts',
  'fx_fireworks',
  'fx_sakura',
  'fx_snow',
  'fx_soft_blur~2',
];

/// The effects shown under one category tab.
List<FlyItem> flyEffectsIn(String cat) {
  if (cat == 'Trending') {
    return [
      for (final String id in flyTrending)
        for (final FlyItem e in flyEffects)
          if (e.id == id) e,
    ];
  }
  return flyEffects.where((e) => e.cat == cat).toList();
}

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
