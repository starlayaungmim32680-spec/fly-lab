// Fly Lab EDITOR step 1 (16 Nov 2026) - ONE PHOTO -> a VIDEO.
// Ko's direction: pick an existing photo / video, choose a template, get an
// mp4 with the effects baked in, post it to Fly. Step 1 proves the engine
// (PhotoVideoEngine.kt / PhotoTemplates.kt, channel "flylab/editor"):
// pick a photo -> live preview of 3 templates (Cinematic Zoom, Pulse Zoom,
// Retro Film) -> Save video (8 s, 720x1280, 30 fps) into the gallery
// (Movies/FlyLab), with how long it took.
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

class EditorLabScreen extends StatefulWidget {
  const EditorLabScreen({super.key});

  @override
  State<EditorLabScreen> createState() => _EditorLabScreenState();
}

class _EditorLabScreenState extends State<EditorLabScreen> {
  static const MethodChannel _ch = MethodChannel('flylab/editor');

  static const List<(String, String, IconData)> _templates = [
    ('cinematic', 'Cinematic', Icons.movie_creation_outlined),
    ('pulse', 'Pulse Zoom', Icons.graphic_eq),
    ('retro', 'Retro Film', Icons.camera_roll_outlined),
  ];

  int? _textureId;
  String _template = 'cinematic';
  bool _loading = false;
  bool _exporting = false;
  // Progress / result redraw alone (ValueNotifier), not the preview.
  final ValueNotifier<double> _progress = ValueNotifier<double>(0);
  final ValueNotifier<String> _status = ValueNotifier<String>('');
  Timer? _poll;

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
      final Map<dynamic, dynamic>? r = await _ch.invokeMapMethod<dynamic,
          dynamic>('export', {'id': _template, 'seconds': 8.0});
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
    _progress.dispose();
    _status.dispose();
    _ch.invokeMethod('dispose');
    super.dispose();
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
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                    child: Text(s,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 12.5)),
                  ),
          ),
          if (_exporting)
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 8, 24, 0),
              child: ValueListenableBuilder<double>(
                valueListenable: _progress,
                builder: (context, p, _) => LinearProgressIndicator(value: p),
              ),
            ),
          SizedBox(
            height: 92,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              children: [
                for (final (String key, String label, IconData icon)
                    in _templates)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 5),
                    child: GestureDetector(
                      onTap: _exporting ? null : () => _setTemplate(key),
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 180),
                        width: 96,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(16),
                          gradient: _template == key
                              ? const LinearGradient(colors: [
                                  Color(0xFFFF4B6E),
                                  Color(0xFF9C4DFF),
                                ])
                              : null,
                          color: _template == key ? null : Colors.white10,
                        ),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(icon),
                            const SizedBox(height: 4),
                            Text(label,
                                style: const TextStyle(
                                    fontSize: 12,
                                    fontWeight: FontWeight.w700)),
                          ],
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          Padding(
            padding: EdgeInsets.fromLTRB(
                16, 0, 16, MediaQuery.paddingOf(context).bottom + 12),
            child: SizedBox(
              width: double.infinity,
              height: 50,
              child: FilledButton.icon(
                onPressed: id == null || _exporting ? null : _export,
                icon: const Icon(Icons.download_rounded),
                label: Text(_exporting ? 'Making the video...' : 'Save video (8 s)'),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
