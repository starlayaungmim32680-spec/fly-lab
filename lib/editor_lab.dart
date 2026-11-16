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
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

class _Tpl {
  const _Tpl(this.id, this.label, this.icon, this.mood);
  final String id;
  final String label;
  final IconData icon;
  final String mood;
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

  static const List<_Tpl> _templates = [
    _Tpl('dreamy', 'Dreamy', Icons.auto_awesome, 'Love'),
    _Tpl('heartbeat', 'Heartbeat', Icons.favorite, 'Love'),
    _Tpl('pulse', 'Pulse Zoom', Icons.graphic_eq, 'Hype'),
    _Tpl('flash', 'Flash Beat', Icons.flash_on, 'Hype'),
    _Tpl('glitch', 'Glitch', Icons.blur_linear, 'Hype'),
    _Tpl('cinematic', 'Cinematic', Icons.movie_creation_outlined, 'Cinema'),
    _Tpl('focus', 'Focus In', Icons.center_focus_strong, 'Cinema'),
    _Tpl('golden', 'Golden Hour', Icons.wb_sunny_outlined, 'Cinema'),
    _Tpl('sad_rain', 'Sad Rain', Icons.water_drop_outlined, 'Feel'),
    _Tpl('memory', 'Memory', Icons.photo_outlined, 'Feel'),
    _Tpl('retro', 'Retro Film', Icons.camera_roll_outlined, 'Feel'),
    _Tpl('neon', 'Neon Lines', Icons.gesture, 'Night'),
    _Tpl('snow', 'Snow', Icons.ac_unit, 'Night'),
  ];
  static const List<(String, String)> _moods = [
    ('All', '✨'),
    ('Love', '💕'),
    ('Hype', '🔥'),
    ('Cinema', '🎬'),
    ('Feel', '🌧️'),
    ('Night', '🌙'),
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
  String _mood = 'All';
  int _tab = 0; // 0 templates, 1 filters, 2 adjust, 3 length
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
      await _ch.invokeMethod('setTemplate', {'id': _template});
      await _sendParams();
      if (!mounted || r == null) return;
      setState(() => _textureId = (r['textureId'] as num).toInt());
    } catch (e) {
      _status.value = 'Could not open the photo: $e';
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _setTemplate(String id) async {
    setState(() => _template = id);
    try {
      await _ch.invokeMethod('setTemplate', {'id': id});
    } catch (_) {}
  }

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
          dynamic>('export', {'id': _template, 'seconds': _seconds.toDouble()});
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
    final List<_Tpl> list = _mood == 'All'
        ? _templates
        : _templates.where((t) => t.mood == _mood).toList();
    return Column(
      children: [
        SizedBox(
          height: 34,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            children: [
              for (final (String m, String emoji) in _moods)
                Padding(
                  padding: const EdgeInsets.only(right: 6),
                  child: ChoiceChip(
                    label: Text('$emoji $m'),
                    selected: _mood == m,
                    visualDensity: VisualDensity.compact,
                    onSelected: (_) => setState(() => _mood = m),
                  ),
                ),
            ],
          ),
        ),
        SizedBox(
          height: 86,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            children: [
              for (final _Tpl t in list)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: GestureDetector(
                    onTap: _exporting ? null : () => _setTemplate(t.id),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 180),
                      width: 84,
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(14),
                        gradient: _template == t.id ? _brand : null,
                        color: _template == t.id ? null : Colors.white10,
                      ),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(t.icon, size: 22),
                          const SizedBox(height: 4),
                          Text(t.label,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                  fontSize: 11.5,
                                  fontWeight: FontWeight.w700)),
                        ],
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
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
          _tabBar(),
          AnimatedSize(
            duration: const Duration(milliseconds: 180),
            child: switch (_tab) {
              0 => _templatesPanel(),
              1 => _filtersPanel(),
              2 => _adjustPanel(),
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
