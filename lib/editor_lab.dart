// Fly Lab EDITOR - ONE PHOTO -> a VIDEO.
// Ko's direction: pick an existing photo, choose a template, make it your
// own (filter + adjust + length), get an mp4 with everything BAKED IN, post
// it to Fly later. Engine: PhotoVideoEngine.kt / PhotoTemplates.kt
// (channel "flylab/editor").
//
// Step 1 (16 Nov 2026): 3 templates + export - WORKS (240 frames in 3 s).
// Step 2 (cut-out templates) - DROPPED 17 Nov 2026 (Ko: no background
// removal).
// Step 3 (17 Nov 2026): 13 templates grouped by FEELING (Love, Hype,
// Cinema, Feel, Night) + Filters (8, with strength) + Adjust sliders
// (brightness, contrast, saturation, warmth, vignette, grain) + Length
// (5 / 8 / 10 / 15 s). Everything shows live in the preview and is baked
// into the saved video.
//
// Step 4 (17 Nov 2026): the template LIBRARY - 35 templates, each in ONE
// category (CapCut-style text tabs: For You, Love, Beat, Cinema, Feel,
// Night, Fun, Retro). Cards show a real preview made from the user's own
// photo ("thumbs", rendered once on the GPU after the photo loads).
//
// Step 5 (17 Nov 2026): COMBINE + more. 7 new templates (Spin, Dolly Zoom,
// Aurora, Kaleido, Pop Art, Sketch, Duotone; new category Art) and an
// EFFECTS tab: 12 effects (Sparkle, Hearts, Snow, Rain, Bokeh, Dust, Light
// Rays, Lens Flare, Bubbles, Film Strip, Fireworks, Balloons) - tick up to
// 3, they stack on top of the template in the order they were picked
// ("setTemplate" {ids: [template, effect...]}).
//
// Step 6 (17 Nov 2026): 21 more effects from Ko's ideas (moon, sunrise,
// shooting stars, birds, falling leaves ...) -> 33 effects, sorted into
// their own tabs: Sky, Nature, Weather, Light, Party, Retro.
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

class _Tpl {
  const _Tpl(this.id, this.label, this.cat);
  final String id;
  final String label;
  final String cat;
}

/// The user's look: filter + sliders (sent to the GPU as 8 numbers).
class _Look {
  const _Look({
    this.bright = 0,
    this.contrast = 1,
    this.sat = 1,
    this.warm = 0,
    this.vignette = 0,
    this.grain = 0,
    this.filter = 0,
    this.strength = 1,
  });
  final double bright, contrast, sat, warm, vignette, grain;
  final int filter;
  final double strength;

  _Look copyWith({
    double? bright,
    double? contrast,
    double? sat,
    double? warm,
    double? vignette,
    double? grain,
    int? filter,
    double? strength,
  }) =>
      _Look(
        bright: bright ?? this.bright,
        contrast: contrast ?? this.contrast,
        sat: sat ?? this.sat,
        warm: warm ?? this.warm,
        vignette: vignette ?? this.vignette,
        grain: grain ?? this.grain,
        filter: filter ?? this.filter,
        strength: strength ?? this.strength,
      );

  List<double> toList() => [
        bright,
        contrast,
        sat,
        warm,
        vignette,
        grain,
        filter.toDouble(),
        strength,
      ];
}

class EditorLabScreen extends StatefulWidget {
  const EditorLabScreen({super.key});

  @override
  State<EditorLabScreen> createState() => _EditorLabScreenState();
}

class _EditorLabScreenState extends State<EditorLabScreen> {
  static const MethodChannel _ch = MethodChannel('flylab/editor');

  // Every template lives in exactly ONE category.
  static const List<_Tpl> _templates = [
    _Tpl('dreamy', 'Dreamy', 'Love'),
    _Tpl('heartbeat', 'Heartbeat', 'Love'),
    _Tpl('pink_sparkle', 'Pink Sparkle', 'Love'),
    _Tpl('love_frame', 'Love Frame', 'Love'),
    _Tpl('petals', 'Petals', 'Love'),
    _Tpl('pulse', 'Pulse Zoom', 'Beat'),
    _Tpl('flash', 'Flash Beat', 'Beat'),
    _Tpl('glitch', 'Glitch', 'Beat'),
    _Tpl('shake', 'Shake', 'Beat'),
    _Tpl('split', 'Split', 'Beat'),
    _Tpl('zoom_blur', 'Zoom Blur', 'Beat'),
    _Tpl('cinematic', 'Cinematic', 'Cinema'),
    _Tpl('focus', 'Focus In', 'Cinema'),
    _Tpl('golden', 'Golden Hour', 'Cinema'),
    _Tpl('letterbox', 'Movie Bars', 'Cinema'),
    _Tpl('ken_burns', 'Slow Glide', 'Cinema'),
    _Tpl('light_leak', 'Light Leak', 'Cinema'),
    _Tpl('sad_rain', 'Sad Rain', 'Feel'),
    _Tpl('memory', 'Memory', 'Feel'),
    _Tpl('color_return', 'Color Return', 'Feel'),
    _Tpl('fog', 'Fog', 'Feel'),
    _Tpl('lonely', 'Lonely', 'Feel'),
    _Tpl('neon', 'Neon Lines', 'Night'),
    _Tpl('snow', 'Snow', 'Night'),
    _Tpl('fireflies', 'Fireflies', 'Night'),
    _Tpl('starry', 'Starry Night', 'Night'),
    _Tpl('confetti', 'Confetti', 'Fun'),
    _Tpl('mirror', 'Mirror', 'Fun'),
    _Tpl('kaleido', 'Kaleido', 'Fun'),
    _Tpl('rainbow', 'Rainbow', 'Fun'),
    _Tpl('disco', 'Disco', 'Fun'),
    _Tpl('retro', 'Retro Film', 'Retro'),
    _Tpl('vhs', 'VHS', 'Retro'),
    _Tpl('old_tv', 'Old TV', 'Retro'),
    _Tpl('pixel', 'Pixel', 'Retro'),
    _Tpl('spin', 'Spin', 'Beat'),
    _Tpl('vertigo', 'Dolly Zoom', 'Cinema'),
    _Tpl('aurora', 'Aurora', 'Night'),
    _Tpl('comic', 'Comic', 'Art'),
    _Tpl('pop_art', 'Pop Art', 'Art'),
    _Tpl('sketch', 'Sketch', 'Art'),
    _Tpl('duotone', 'Duotone', 'Art'),
  ];
  // Effects: made to stack on top of any template (max 3). Each lives in
  // ONE effect category.
  static const List<_Tpl> _effects = [
    _Tpl('fx_moon', 'Big Moon', 'Sky'),
    _Tpl('fx_sunrise', 'Sunrise', 'Sky'),
    _Tpl('fx_shooting', 'Shooting Stars', 'Sky'),
    _Tpl('fx_night_sky', 'Night Sky', 'Sky'),
    _Tpl('fx_birds', 'Birds', 'Sky'),
    _Tpl('fx_clouds', 'Clouds', 'Sky'),
    _Tpl('fx_rainbow', 'Rainbow', 'Sky'),
    _Tpl('fx_leaves', 'Falling Leaves', 'Nature'),
    _Tpl('fx_sakura', 'Sakura', 'Nature'),
    _Tpl('fx_butterflies', 'Butterflies', 'Nature'),
    _Tpl('fx_fireflies', 'Fireflies', 'Nature'),
    _Tpl('fx_dandelion', 'Dandelion', 'Nature'),
    _Tpl('fx_bubbles', 'Bubbles', 'Nature'),
    _Tpl('fx_snow', 'Snow', 'Weather'),
    _Tpl('fx_rain', 'Rain', 'Weather'),
    _Tpl('fx_lightning', 'Lightning', 'Weather'),
    _Tpl('fx_fog', 'Fog', 'Weather'),
    _Tpl('fx_sparkle', 'Sparkle', 'Light'),
    _Tpl('fx_gold', 'Gold Glitter', 'Light'),
    _Tpl('fx_bokeh', 'Bokeh', 'Light'),
    _Tpl('fx_rays', 'Light Rays', 'Light'),
    _Tpl('fx_flare', 'Lens Flare', 'Light'),
    _Tpl('fx_prism', 'Prism', 'Light'),
    _Tpl('fx_spotlight', 'Spotlight', 'Light'),
    _Tpl('fx_neon_frame', 'Neon Frame', 'Light'),
    _Tpl('fx_lanterns', 'Sky Lanterns', 'Party'),
    _Tpl('fx_fireworks', 'Fireworks', 'Party'),
    _Tpl('fx_balloons', 'Balloons', 'Party'),
    _Tpl('fx_confetti', 'Confetti', 'Party'),
    _Tpl('fx_hearts', 'Hearts', 'Party'),
    _Tpl('fx_heart_burst', 'Heart Burst', 'Party'),
    _Tpl('fx_dust', 'Film Dust', 'Retro'),
    _Tpl('fx_film', 'Film Strip', 'Retro'),
  ];
  static const List<String> _fxCats = [
    'Sky',
    'Nature',
    'Weather',
    'Light',
    'Party',
    'Retro',
  ];
  static const int _maxFx = 3;
  static const List<String> _cats = [
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
  // "For You": a hand-picked mix from the other categories.
  static const List<String> _forYou = [
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
  static const List<(String, Color)> _filters = [
    ('None', Color(0xFF555555)),
    ('Vivid', Color(0xFFFF5A5A)),
    ('Warm', Color(0xFFFFA14A)),
    ('Cool', Color(0xFF4AA8FF)),
    ('B&W', Color(0xFF9E9E9E)),
    ('Fade', Color(0xFFC9B9A6)),
    ('Pink', Color(0xFFFF7EB6)),
    ('Moody', Color(0xFF4E5D6C)),
  ];
  static const List<int> _lengths = [5, 8, 10, 15];

  int? _textureId;
  String _template = 'dreamy';
  String _cat = 'For You';
  Map<String, Uint8List> _thumbs = const {};
  int _tab = 0; // 0 templates, 1 effects, 2 filters, 3 adjust, 4 length
  List<String> _fx = const [];
  String _fxCat = 'Sky';
  int _seconds = 8;
  bool _loading = false;
  bool _exporting = false;
  // Sliders / progress / status redraw alone (ValueNotifier), not the
  // preview or the whole screen.
  final ValueNotifier<_Look> _look = ValueNotifier<_Look>(const _Look());
  final ValueNotifier<double> _progress = ValueNotifier<double>(0);
  final ValueNotifier<String> _status = ValueNotifier<String>('');
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _look.addListener(_sendParams);
  }

  Future<void> _sendParams() async {
    try {
      await _ch.invokeMethod('setParams', {
        'adj': _look.value.toList(),
        'seconds': _seconds.toDouble(),
      });
    } catch (_) {}
  }

  Future<void> _pick() async {
    final XFile? f =
        await ImagePicker().pickImage(source: ImageSource.gallery);
    if (f == null || !mounted) return;
    setState(() => _loading = true);
    _status.value = '';
    try {
      final Map<dynamic, dynamic>? r = await _ch
          .invokeMapMethod<dynamic, dynamic>('load', {'path': f.path});
      await _sendChain();
      await _sendParams();
      if (!mounted || r == null) return;
      setState(() {
        _textureId = (r['textureId'] as num).toInt();
        _thumbs = const {};
      });
      unawaited(_loadThumbs());
    } catch (e) {
      _status.value = 'Could not open the photo: $e';
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  /// Preview cards of every template, made from this photo.
  Future<void> _loadThumbs() async {
    try {
      final Map<dynamic, dynamic>? r =
          await _ch.invokeMapMethod<dynamic, dynamic>('thumbs');
      if (!mounted || r == null) return;
      setState(() => _thumbs = {
            for (final MapEntry<dynamic, dynamic> e in r.entries)
              e.key as String: e.value as Uint8List,
          });
    } catch (_) {}
  }

  /// The template + the picked effects, in order.
  Future<void> _sendChain() async {
    try {
      await _ch.invokeMethod('setTemplate', {
        'ids': [_template, ..._fx],
      });
    } catch (_) {}
  }

  Future<void> _setTemplate(String id) async {
    setState(() => _template = id);
    await _sendChain();
  }

  Future<void> _toggleFx(String id) async {
    if (_fx.contains(id)) {
      setState(() => _fx = [..._fx]..remove(id));
    } else if (_fx.length >= _maxFx) {
      _status.value = 'Up to $_maxFx effects - remove one first';
      return;
    } else {
      setState(() => _fx = [..._fx, id]);
    }
    _status.value = '';
    await _sendChain();
  }

  String _labelOf(String id) => [..._templates, ..._effects]
      .firstWhere((t) => t.id == id, orElse: () => _Tpl(id, id, ''))
      .label;

  void _setLength(int s) {
    setState(() => _seconds = s);
    _sendParams();
  }

  Future<void> _export() async {
    if (_textureId == null || _exporting) return;
    setState(() => _exporting = true);
    _progress.value = 0;
    _status.value = 'Making the video...';
    _poll = Timer.periodic(const Duration(milliseconds: 200), (_) async {
      try {
        final double p = (await _ch.invokeMethod<double>('progress')) ?? 0;
        _progress.value = p;
      } catch (_) {}
    });
    try {
      await _sendParams();
      final Map<dynamic, dynamic>? r = await _ch.invokeMapMethod<dynamic,
          dynamic>('export', {
        'ids': [_template, ..._fx],
        'seconds': _seconds.toDouble(),
      });
      if (r != null) {
        final double secs = ((r['ms'] as num?) ?? 0) / 1000;
        final double mb = ((r['bytes'] as num?) ?? 0) / (1024 * 1024);
        _status.value = 'Saved to the gallery: ${r['uri']}\n'
            '${r['frames']} frames · ${mb.toStringAsFixed(1)} MB · '
            'made in ${secs.toStringAsFixed(1)} s';
      }
    } catch (e) {
      _status.value = 'Export failed: $e';
    } finally {
      _poll?.cancel();
      _progress.value = 1;
      if (mounted) setState(() => _exporting = false);
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    _look.removeListener(_sendParams);
    _look.dispose();
    _progress.dispose();
    _status.dispose();
    _ch.invokeMethod('dispose');
    super.dispose();
  }

  // ------------------------------------------------------------------ UI

  static const LinearGradient _brand = LinearGradient(colors: [
    Color(0xFFFF4B6E),
    Color(0xFF9C4DFF),
  ]);

  Widget _tabBar() {
    const List<(String, IconData)> tabs = [
      ('Templates', Icons.auto_awesome_motion),
      ('Effects', Icons.layers_outlined),
      ('Filters', Icons.filter_vintage_outlined),
      ('Adjust', Icons.tune),
      ('Length', Icons.timer_outlined),
    ];
    return Row(
      children: [
        for (int i = 0; i < tabs.length; i++)
          Expanded(
            child: InkWell(
              onTap: () => setState(() => _tab = i),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(tabs[i].$2,
                        size: 20,
                        color: _tab == i ? Colors.white : Colors.white54),
                    const SizedBox(height: 2),
                    Text(tabs[i].$1,
                        style: TextStyle(
                            fontSize: 11.5,
                            fontWeight:
                                _tab == i ? FontWeight.w800 : FontWeight.w500,
                            color:
                                _tab == i ? Colors.white : Colors.white54)),
                    const SizedBox(height: 4),
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 180),
                      height: 3,
                      width: _tab == i ? 22 : 0,
                      decoration: BoxDecoration(
                        gradient: _brand,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget _templatesPanel() {
    final List<_Tpl> list = _cat == 'For You'
        ? [
            for (final String id in _forYou)
              _templates.firstWhere((t) => t.id == id),
          ]
        : _templates.where((t) => t.cat == _cat).toList();
    return Column(
      children: [
        SizedBox(
          height: 30,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 10),
            children: [
              for (final String c in _cats)
                GestureDetector(
                  onTap: () => setState(() => _cat = c),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 9),
                    child: Center(
                      child: Text(c,
                          style: TextStyle(
                              fontSize: 13.5,
                              fontWeight: _cat == c
                                  ? FontWeight.w800
                                  : FontWeight.w500,
                              color: _cat == c
                                  ? Colors.white
                                  : Colors.white54)),
                    ),
                  ),
                ),
            ],
          ),
        ),
        SizedBox(
          height: 128,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.fromLTRB(10, 6, 10, 4),
            children: [
              for (final _Tpl t in list) _card(t),
            ],
          ),
        ),
      ],
    );
  }

  Widget _catRow(List<String> cats, String cur, ValueChanged<String> onPick) {
    return SizedBox(
      height: 30,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        children: [
          for (final String c in cats)
            GestureDetector(
              onTap: () => onPick(c),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 9),
                child: Center(
                  child: Text(c,
                      style: TextStyle(
                          fontSize: 13.5,
                          fontWeight:
                              cur == c ? FontWeight.w800 : FontWeight.w500,
                          color: cur == c ? Colors.white : Colors.white54)),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _effectsPanel() {
    return SizedBox(
      height: 188,
      child: Column(
        children: [
          SizedBox(
            height: 30,
            child: Row(
              children: [
                const SizedBox(width: 16),
                Expanded(
                  child: Text(
                    _fx.isEmpty
                        ? 'Tap to add on top of the template (up to $_maxFx)'
                        : '${_fx.length} / $_maxFx effects added',
                    style: const TextStyle(fontSize: 12, color: Colors.white70),
                  ),
                ),
                if (_fx.isNotEmpty)
                  TextButton(
                    onPressed: () {
                      setState(() => _fx = const []);
                      _sendChain();
                    },
                    child: const Text('Clear'),
                  ),
                const SizedBox(width: 6),
              ],
            ),
          ),
          _catRow(_fxCats, _fxCat, (c) => setState(() => _fxCat = c)),
          SizedBox(
            height: 128,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.fromLTRB(10, 6, 10, 4),
              children: [
                for (final _Tpl t in _effects.where((e) => e.cat == _fxCat))
                  _card(t,
                      selected: _fx.contains(t.id),
                      badge: _fx.contains(t.id) ? _fx.indexOf(t.id) + 1 : 0,
                      onTap: () => _toggleFx(t.id)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _card(_Tpl t, {bool? selected, int badge = 0, VoidCallback? onTap}) {
    final bool sel = selected ?? _template == t.id;
    final Uint8List? img = _thumbs[t.id];
    return GestureDetector(
      onTap: _exporting ? null : (onTap ?? () => _setTemplate(t.id)),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4),
        child: SizedBox(
          width: 60,
          child: Column(
            children: [
              AnimatedContainer(
                duration: const Duration(milliseconds: 160),
                width: 60,
                height: 96,
                padding: const EdgeInsets.all(2),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(10),
                  gradient: sel ? _brand : null,
                  color: sel ? null : Colors.transparent,
                ),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: img == null
                      ? Container(
                          color: Colors.white10,
                          alignment: Alignment.center,
                          child: const SizedBox(
                            width: 14,
                            height: 14,
                            child:
                                CircularProgressIndicator(strokeWidth: 1.5),
                          ),
                        )
                      : Image.memory(img,
                          fit: BoxFit.cover, gaplessPlayback: true),
                ),
                if (badge > 0)
                  Positioned(
                    right: 4,
                    top: 4,
                    child: Container(
                      width: 18,
                      height: 18,
                      alignment: Alignment.center,
                      decoration: const BoxDecoration(
                          shape: BoxShape.circle, gradient: _brand),
                      child: Text('$badge',
                          style: const TextStyle(
                              fontSize: 11, fontWeight: FontWeight.w800)),
                    ),
                  ),
                  ],
                ),
              ),
              const SizedBox(height: 4),
              Text(t.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 10.5,
                      fontWeight: sel ? FontWeight.w800 : FontWeight.w500,
                      color: sel ? Colors.white : Colors.white70)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _filtersPanel() {
    return ValueListenableBuilder<_Look>(
      valueListenable: _look,
      builder: (context, look, _) => Column(
        children: [
          SizedBox(
            height: 78,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              children: [
                for (int i = 0; i < _filters.length; i++)
                  GestureDetector(
                    onTap: () => _look.value = look.copyWith(filter: i),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      child: Column(
                        children: [
                          Container(
                            width: 46,
                            height: 46,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: _filters[i].$2,
                              border: Border.all(
                                  color: look.filter == i
                                      ? Colors.white
                                      : Colors.transparent,
                                  width: 2.5),
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(_filters[i].$1,
                              style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: look.filter == i
                                      ? FontWeight.w800
                                      : FontWeight.w500)),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
          if (look.filter != 0)
            _slider('Strength', look.strength, 0, 1,
                (v) => _look.value = look.copyWith(strength: v)),
        ],
      ),
    );
  }

  Widget _slider(String label, double v, double min, double max,
      ValueChanged<double> onChanged) {
    return SizedBox(
      height: 36,
      child: Row(
        children: [
          const SizedBox(width: 16),
          SizedBox(
              width: 78,
              child: Text(label, style: const TextStyle(fontSize: 12))),
          Expanded(
            child: Slider(value: v, min: min, max: max, onChanged: onChanged),
          ),
        ],
      ),
    );
  }

  Widget _adjustPanel() {
    return ValueListenableBuilder<_Look>(
      valueListenable: _look,
      builder: (context, look, _) => SizedBox(
        height: 124,
        child: ListView(
          padding: EdgeInsets.zero,
          children: [
            _slider('Brightness', look.bright, -0.25, 0.25,
                (v) => _look.value = look.copyWith(bright: v)),
            _slider('Contrast', look.contrast, 0.6, 1.5,
                (v) => _look.value = look.copyWith(contrast: v)),
            _slider('Saturation', look.sat, 0, 2,
                (v) => _look.value = look.copyWith(sat: v)),
            _slider('Warmth', look.warm, -1, 1,
                (v) => _look.value = look.copyWith(warm: v)),
            _slider('Vignette', look.vignette, 0, 1,
                (v) => _look.value = look.copyWith(vignette: v)),
            _slider('Grain', look.grain, 0, 1,
                (v) => _look.value = look.copyWith(grain: v)),
            Center(
              child: TextButton.icon(
                onPressed: () => _look.value =
                    _Look(filter: look.filter, strength: look.strength),
                icon: const Icon(Icons.restart_alt, size: 18),
                label: const Text('Reset'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _lengthPanel() {
    return SizedBox(
      height: 70,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          for (final int s in _lengths)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6),
              child: ChoiceChip(
                label: Text('$s s'),
                selected: _seconds == s,
                onSelected: _exporting ? null : (_) => _setLength(s),
              ),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final int? id = _textureId;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        title: const Text('Photo → Video'),
        actions: [
          IconButton(
            onPressed: _exporting ? null : _pick,
            icon: const Icon(Icons.add_photo_alternate_outlined),
            tooltip: 'Pick a photo',
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: Center(
              child: id == null
                  ? _loading
                      ? const CircularProgressIndicator()
                      : FilledButton.icon(
                          onPressed: _pick,
                          icon: const Icon(Icons.photo_library_outlined),
                          label: const Text('Pick a photo'),
                        )
                  : AspectRatio(
                      aspectRatio: 9 / 16,
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(16),
                        child: Texture(textureId: id),
                      ),
                    ),
            ),
          ),
          ValueListenableBuilder<String>(
            valueListenable: _status,
            builder: (context, s, _) => s.isEmpty
                ? const SizedBox.shrink()
                : Padding(
                    padding: const EdgeInsets.fromLTRB(16, 6, 16, 0),
                    child: Text(s,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 12)),
                  ),
          ),
          if (_exporting)
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 6, 24, 0),
              child: ValueListenableBuilder<double>(
                valueListenable: _progress,
                builder: (context, p, _) => LinearProgressIndicator(value: p),
              ),
            ),
          if (_fx.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 6, 16, 0),
              child: Text(
                [_template, ..._fx].map(_labelOf).join('  +  '),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 12, fontWeight: FontWeight.w700),
              ),
            ),
          _tabBar(),
          AnimatedSize(
            duration: const Duration(milliseconds: 180),
            child: switch (_tab) {
              0 => _templatesPanel(),
              1 => _effectsPanel(),
              2 => _filtersPanel(),
              3 => _adjustPanel(),
              _ => _lengthPanel(),
            },
          ),
          Padding(
            padding: EdgeInsets.fromLTRB(
                16, 4, 16, MediaQuery.paddingOf(context).bottom + 10),
            child: SizedBox(
              width: double.infinity,
              height: 48,
              child: FilledButton.icon(
                onPressed: id == null || _exporting ? null : _export,
                icon: const Icon(Icons.download_rounded),
                label: Text(_exporting
                    ? 'Making the video...'
                    : 'Save video ($_seconds s)'),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
