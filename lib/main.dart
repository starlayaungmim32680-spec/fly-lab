// Fly Lab (15 Nov 2026) - Ko's test bench for Fly camera effects.
// Effects are built and tested here first; only what works on Ko's phone is
// copied into Fly. Not published, no Firebase, no login.
//
// Step 1: GPUPixel beauty camera + person segmentation (MediaPipe) + the
// first background: Blur. "Mask" shows only the cut-out (cyan = person) so
// Ko can see how clean the edges are. The top read-out is the Lab's own
// measuring tool: camera fps, segmentation time / runs per second, and
// whether the model runs on the GPU or the CPU (16 Nov 2026: plus the last
// Java crash, if the app closed itself - long-press LAB to clear it).
//
// Step 3 (16 Nov 2026): 6 futuristic animated backgrounds (City, Warp,
// Matrix, Grid, Aurora, Chrome - GPU shaders in LabBackgrounds.kt) with a
// neon rim around the person, and a cleaner Blur (no grey halo). The choice
// row scrolls sideways.
//
// Quality pass (16 Nov 2026): beauty presets from Fly's Record camera (tap
// the face button, top right: Natural / Glow / Doll / Glam, default Glow);
// the read-out also shows LOW (low-light camera mode) and the brightness
// lift (x1.0 - x1.8).
import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

import 'editor_lab.dart';
import 'project_editor.dart';

void main() {
  runApp(const FlyLabApp());
}

class FlyLabApp extends StatelessWidget {
  const FlyLabApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Fly Lab',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true),
      home: const LabHome(),
    );
  }
}

/// Lab home (16 Nov 2026): the live camera tests (paused) and the new
/// photo / video EDITOR (editor_lab.dart).
class LabHome extends StatelessWidget {
  const LabHome({super.key});

  @override
  Widget build(BuildContext context) {
    Widget tile(String title, String sub, IconData icon, List<Color> colors,
        Widget page) {
      return GestureDetector(
        onTap: () => Navigator.push(
            context, MaterialPageRoute(builder: (_) => page)),
        child: Container(
          margin: const EdgeInsets.only(bottom: 16),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(22),
            gradient: LinearGradient(colors: colors),
          ),
          child: Row(
            children: [
              Icon(icon, size: 40),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: const TextStyle(
                            fontSize: 20, fontWeight: FontWeight.w800)),
                    const SizedBox(height: 4),
                    Text(sub, style: const TextStyle(fontSize: 13)),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
    }

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        title: const Row(children: [_LabBadge(), SizedBox(width: 10),
          Text('Fly Lab')]),
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          tile('Photo → Video', 'Editor step 1 - templates from one photo',
              Icons.movie_filter,
              const [Color(0xFFFF4B6E), Color(0xFF9C4DFF)],
              const EditorLabScreen()),
          tile('New Project', 'CapCut-style editor - photos + videos, timeline',
              Icons.video_library_outlined,
              const [Color(0xFFFFB300), Color(0xFFFF4B6E)],
              const ProjectEditorScreen()),
          tile('Camera Lab', 'Live cut-out + backgrounds (paused)',
              Icons.videocam_outlined,
              const [Color(0xFF00B8D4), Color(0xFF3A4DFF)],
              const LabCameraScreen()),
        ],
      ),
    );
  }
}

class LabCameraScreen extends StatefulWidget {
  const LabCameraScreen({super.key});

  @override
  State<LabCameraScreen> createState() => _LabCameraScreenState();
}

class _LabCameraScreenState extends State<LabCameraScreen> {
  static const MethodChannel _ch = MethodChannel('flylab/camera');

  int? _textureId;
  double _aspect = 9 / 16;
  bool _front = true;
  String _mode = 'city';
  // Beauty presets - the same values as Fly's gpu_camera_screen.dart.
  static const List<(String, Map<String, double>)> _looks = [
    ('Natural', {'smooth': 0.55, 'white': 0.25, 'thin': 0.26, 'eyes': 0.19,
        'lips': 0.15, 'blush': 0.1}),
    ('Glow', {'smooth': 0.75, 'white': 0.4, 'thin': 0.38, 'eyes': 0.3,
        'lips': 0.25, 'blush': 0.25}),
    ('Doll', {'smooth': 0.9, 'white': 0.5, 'thin': 0.56, 'eyes': 0.49,
        'lips': 0.35, 'blush': 0.35}),
    ('Glam', {'smooth': 1.0, 'white': 0.6, 'thin': 1.0, 'eyes': 0.85,
        'lips': 0.45, 'blush': 0.4}),
  ];
  int _look = 1;
  String? _error;
  // The read-out redraws alone (ValueNotifier), not the whole camera.
  final ValueNotifier<String> _stats = ValueNotifier<String>('');
  Timer? _statsTimer;

  static const List<(String, String, IconData)> _modes = [
    ('none', 'Off', Icons.block),
    ('blur', 'Blur', Icons.blur_on),
    ('city', 'City', Icons.location_city),
    ('warp', 'Warp', Icons.rocket_launch),
    ('matrix', 'Matrix', Icons.code),
    ('grid', 'Grid', Icons.grid_on),
    ('aurora', 'Aurora', Icons.auto_awesome),
    ('chrome', 'Chrome', Icons.water_drop),
    ('mask', 'Mask', Icons.person_outline),
  ];

  @override
  void initState() {
    super.initState();
    _start();
    _statsTimer =
        Timer.periodic(const Duration(seconds: 1), (_) => _pollStats());
  }

  Future<void> _start() async {
    final PermissionStatus st = await Permission.camera.request();
    if (!st.isGranted) {
      if (mounted) setState(() => _error = 'Camera permission is needed.');
      return;
    }
    try {
      final Map<dynamic, dynamic>? r = await _ch
          .invokeMapMethod<dynamic, dynamic>('start', {'front': _front});
      if (r == null || !mounted) return;
      await _ch.invokeMethod('setBackground', {'mode': _mode});
      await _ch.invokeMethod('set', _looks[_look].$2);
      setState(() {
        _textureId = (r['textureId'] as num).toInt();
        _aspect = (r['width'] as num) / (r['height'] as num);
        _front = r['front'] == true;
      });
    } catch (e) {
      if (mounted) setState(() => _error = 'Camera could not start: $e');
    }
  }

  Future<void> _pollStats() async {
    if (_textureId == null) return;
    try {
      final Map<dynamic, dynamic>? r =
          await _ch.invokeMapMethod<dynamic, dynamic>('stats');
      if (r == null || !mounted) return;
      final double fps = (r['fps'] as num?)?.toDouble() ?? 0;
      final double segMs = (r['segMs'] as num?)?.toDouble() ?? 0;
      final double segFps = (r['segFps'] as num?)?.toDouble() ?? 0;
      final String crash = (r['lastCrash'] as String?) ?? '';
      final bool low = r['low'] == true;
      final double gain = (r['gain'] as num?)?.toDouble() ?? 1;
      _stats.value = 'camera ${fps.toStringAsFixed(0)} fps · '
          'mask ${segMs.toStringAsFixed(0)} ms '
          '(${segFps.toStringAsFixed(0)}/s) · ${r['delegate']}'
          '${low ? ' · LOW' : ''} · light x${gain.toStringAsFixed(1)}'
          '${crash.isEmpty ? '' : '\nlast crash: $crash'}';
    } catch (_) {}
  }

  Future<void> _setMode(String m) async {
    setState(() => _mode = m);
    try {
      await _ch.invokeMethod('setBackground', {'mode': m});
    } catch (_) {}
  }

  Future<void> _nextLook() async {
    setState(() => _look = (_look + 1) % _looks.length);
    try {
      await _ch.invokeMethod('set', _looks[_look].$2);
    } catch (_) {}
  }

  Future<void> _flip() async {
    try {
      final Map<dynamic, dynamic>? r =
          await _ch.invokeMapMethod<dynamic, dynamic>('switchCamera');
      if (r == null || !mounted) return;
      setState(() {
        _aspect = (r['width'] as num) / (r['height'] as num);
        _front = r['front'] == true;
      });
    } catch (_) {}
  }

  @override
  void dispose() {
    _statsTimer?.cancel();
    _stats.dispose();
    _ch.invokeMethod('stop');
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final int? id = _textureId;
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (id != null)
            ClipRect(
              child: FittedBox(
                fit: BoxFit.cover,
                child: SizedBox(
                  width: 720,
                  height: 720 / _aspect,
                  child: _front
                      ? Transform(
                          alignment: Alignment.center,
                          transform: Matrix4.rotationY(pi),
                          child: Texture(textureId: id),
                        )
                      : Texture(textureId: id),
                ),
              ),
            )
          else
            Center(
              child: _error == null
                  ? const CircularProgressIndicator()
                  : Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(_error!, textAlign: TextAlign.center),
                    ),
            ),
          // Lab read-out (top).
          Positioned(
            left: 12,
            right: 12,
            top: MediaQuery.paddingOf(context).top + 10,
            child: Row(
              children: [
                GestureDetector(
                  onLongPress: () => _ch.invokeMethod('clearCrash'),
                  child: const _LabBadge(),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ValueListenableBuilder<String>(
                    valueListenable: _stats,
                    builder: (context, t, _) => Text(
                      t,
                      style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        shadows: [Shadow(blurRadius: 4)],
                      ),
                    ),
                  ),
                ),
                Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      onPressed: _flip,
                      icon: const Icon(Icons.flip_camera_ios_outlined),
                    ),
                    GestureDetector(
                      onTap: _nextLook,
                      child: Column(
                        children: [
                          const Icon(Icons.face_retouching_natural),
                          Text(_looks[_look].$1,
                              style: const TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w700,
                                  shadows: [Shadow(blurRadius: 4)])),
                        ],
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          // Background choices (bottom).
          Positioned(
            left: 0,
            right: 0,
            bottom: MediaQuery.paddingOf(context).bottom + 24,
            child: SizedBox(
              height: 76,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 12),
                children: [
                  for (final (String key, String label, IconData icon)
                      in _modes)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 5),
                      child: _ModeChip(
                        label: label,
                        icon: icon,
                        selected: _mode == key,
                        onTap: () => _setMode(key),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _LabBadge extends StatelessWidget {
  const _LabBadge();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        gradient: const LinearGradient(
          colors: [Color(0xFFFF4B6E), Color(0xFF9C4DFF)],
        ),
      ),
      child: const Text('LAB',
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w900)),
    );
  }
}

class _ModeChip extends StatelessWidget {
  const _ModeChip({
    required this.label,
    required this.icon,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final IconData icon;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        width: 72,
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: BoxDecoration(
          color: selected ? null : Colors.black54,
          gradient: selected
              ? const LinearGradient(
                  colors: [Color(0xFF00E5FF), Color(0xFF9C4DFF)],
                )
              : null,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: Colors.white24),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 24),
            const SizedBox(height: 4),
            Text(label,
                style: const TextStyle(
                    fontSize: 12, fontWeight: FontWeight.w700)),
          ],
        ),
      ),
    );
  }
}
