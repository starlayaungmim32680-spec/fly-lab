// Fly Lab PROJECT EDITOR (CapCut-style) - E1a, 17 Nov 2026.
// Ko: "like CapCut, the free way" - everything runs on the phone.
//
// A project = a TIMELINE of clips (photos and videos) with a template per
// clip, plus the project's look: effects (max 3, stacked on every clip),
// filter + adjust, aspect ratio, fill / fit and background. The engine is
// ProjectEngine.kt (channel "flylab/project"); this screen owns the
// timeline and sends it whole with "setTimeline" after every change.
//
// Layout like CapCut: preview, time + play, a timeline strip that scrolls
// under a fixed white playhead, and a bottom toolbar (Edit, Templates,
// Effects, Filters, Adjust, Ratio, Background, Add). A tool opens its
// panel in place of the toolbar; the tick closes it.
// E1b (done): split at playhead, duplicate, trim (video), duration (photo),
// move left / right, delete. Coming next: speed + drag reorder, E2 Audio,
// E3 Text, E4 Stickers + Overlay, E5 Transitions.
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

import 'fly_catalog.dart';

class _Clip {
  _Clip({
    required this.id,
    required this.media,
    required this.video,
    required this.dur,
    required this.srcDur,
    required this.thumb,
  });
  final int id;
  final int media;
  final bool video;
  double dur;
  final double srcDur;
  final Uint8List thumb;
  String template = 'plain';
  /// Where in the source this clip starts (0 unless split or trimmed).
  double from = 0;

  /// How long the source can still give us from [from] on.
  double get maxDur => video ? (srcDur - from).clamp(0.5, 600.0) : 10.0;

  Map<String, Object> toMap() => {
        'id': id,
        'media': media,
        'dur': dur,
        'template': template,
        'from': from,
      };
}

enum _Panel {
  none,
  edit,
  duration,
  templates,
  effects,
  filters,
  adjust,
  ratio,
  background,
}

class ProjectEditorScreen extends StatefulWidget {
  const ProjectEditorScreen({super.key});

  @override
  State<ProjectEditorScreen> createState() => _ProjectEditorScreenState();
}

class _ProjectEditorScreenState extends State<ProjectEditorScreen>
    with SingleTickerProviderStateMixin {
  static const MethodChannel _ch = MethodChannel('flylab/project');
  static const double _pps = 56; // timeline pixels per second
  static const LinearGradient _brand = LinearGradient(colors: [
    Color(0xFFFF4B6E),
    Color(0xFF9C4DFF),
  ]);
  static const List<String> _ratios = ['9:16', '1:1', '4:5', '16:9'];
  static const List<(String, Color?)> _backgrounds = [
    ('Blur', null),
    ('Black', Color(0xFF000000)),
    ('White', Color(0xFFFFFFFF)),
    ('Pink', Color(0xFFFF7EB6)),
    ('Purple', Color(0xFF7C4DFF)),
    ('Blue', Color(0xFF2F80ED)),
    ('Green', Color(0xFF27AE60)),
    ('Gold', Color(0xFFF2C94C)),
  ];

  final List<_Clip> _clips = [];
  int _nextClip = 1;
  int? _sel;
  _Panel _panel = _Panel.none;

  int? _textureId;
  double _texW = 720;
  double _texH = 1280;
  String _ratio = '9:16';
  bool _fit = false;
  int _bg = 0; // index in _backgrounds
  List<String> _effects = const [];
  String _tplCat = 'For You';
  String _fxCat = 'Sky';
  final Map<int, Map<String, Uint8List>> _thumbs = {};

  // Look sliders: brightness, contrast, saturation, warmth, vignette,
  // grain, filter id, filter strength.
  final ValueNotifier<List<double>> _adj =
      ValueNotifier<List<double>>(const [0, 1, 1, 0, 0, 0, 0, 1]);
  final ValueNotifier<double> _time = ValueNotifier<double>(0);
  final ValueNotifier<String> _status = ValueNotifier<String>('');
  final ValueNotifier<double> _progress = ValueNotifier<double>(0);
  final ScrollController _sc = ScrollController();
  late final Ticker _ticker;
  bool _playing = false;
  bool _polling = false;
  bool _userDrag = false;
  bool _seekBusy = false;
  double? _seekNext;
  bool _adding = false;
  bool _exporting = false;

  double get _total => _clips.fold(0.0, (s, c) => s + c.dur);

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_onTick);
    _adj.addListener(_sendLook);
    _startPreview();
  }

  @override
  void dispose() {
    _ticker.dispose();
    _adj.removeListener(_sendLook);
    _adj.dispose();
    _time.dispose();
    _status.dispose();
    _progress.dispose();
    _sc.dispose();
    _ch.invokeMethod('dispose');
    super.dispose();
  }

  // ------------------------------------------------------------ engine
  Future<void> _startPreview() async {
    try {
      final Map<dynamic, dynamic>? r =
          await _ch.invokeMapMethod<dynamic, dynamic>('preview');
      if (!mounted || r == null) return;
      setState(() {
        _textureId = (r['textureId'] as num).toInt();
        _texW = (r['width'] as num).toDouble();
        _texH = (r['height'] as num).toDouble();
      });
      await _sendLook();
    } catch (e) {
      _status.value = 'Preview failed: $e';
    }
  }

  Future<void> _sendTimeline() async {
    try {
      await _ch.invokeMethod('setTimeline', {
        'clips': [for (final _Clip c in _clips) c.toMap()],
      });
    } catch (_) {}
  }

  Future<void> _sendLook() async {
    final Color? bg = _backgrounds[_bg].$2;
    try {
      await _ch.invokeMethod('setLook', {
        'adj': _adj.value,
        'effects': _effects,
        'fit': _fit,
        'bgMode': bg == null ? 0 : 1,
        'bgColor': bg == null
            ? [0.0, 0.0, 0.0]
            : [bg.r, bg.g, bg.b],
      });
    } catch (_) {}
  }

  Future<void> _addMedia() async {
    if (_adding) return;
    final List<XFile> files = await ImagePicker().pickMultipleMedia();
    if (files.isEmpty || !mounted) return;
    setState(() => _adding = true);
    _status.value = 'Adding ${files.length}...';
    const List<String> videoExt = [
      '.mp4', '.mov', '.3gp', '.mkv', '.webm', '.m4v', '.avi',
    ];
    try {
      final List<dynamic>? r = await _ch.invokeListMethod<dynamic>('addMedia', {
        'items': [
          for (final XFile f in files)
            {
              'path': f.path,
              'video': videoExt.any((e) => f.path.toLowerCase().endsWith(e)),
            },
        ],
      });
      if (!mounted || r == null) return;
      setState(() {
        for (final dynamic m in r) {
          final Map<dynamic, dynamic> mm = m as Map<dynamic, dynamic>;
          final bool video = mm['video'] as bool;
          final double d = (mm['dur'] as num).toDouble();
          _clips.add(_Clip(
            id: _nextClip++,
            media: (mm['media'] as num).toInt(),
            video: video,
            dur: video ? d.clamp(0.5, 60.0) : 3.0,
            srcDur: d,
            thumb: mm['thumb'] as Uint8List,
          ));
        }
      });
      _status.value = r.length < files.length
          ? '${files.length - r.length} could not be read'
          : '';
      await _sendTimeline();
    } catch (e) {
      _status.value = 'Could not add: $e';
    } finally {
      if (mounted) setState(() => _adding = false);
    }
  }

  // ------------------------------------------------------ play / seek
  Future<void> _togglePlay() async {
    if (_clips.isEmpty) return;
    if (_playing) {
      await _ch.invokeMethod('pause');
      _ticker.stop();
      setState(() => _playing = false);
    } else {
      await _ch.invokeMethod('play');
      setState(() => _playing = true);
      if (!_ticker.isActive) _ticker.start();
    }
  }

  Future<void> _onTick(Duration _) async {
    if (_polling) return;
    _polling = true;
    try {
      final Map<dynamic, dynamic>? r =
          await _ch.invokeMapMethod<dynamic, dynamic>('position');
      if (!mounted || r == null) return;
      final double t = (r['t'] as num).toDouble();
      final bool playing = r['playing'] as bool;
      _time.value = t;
      if (!_userDrag && _sc.hasClients) {
        _sc.jumpTo((t * _pps).clamp(0.0, _sc.position.maxScrollExtent));
      }
      if (!playing) {
        _ticker.stop();
        if (_playing) setState(() => _playing = false);
      }
    } catch (_) {
    } finally {
      _polling = false;
    }
  }

  Future<void> _seek(double t) async {
    _time.value = t;
    if (_seekBusy) {
      _seekNext = t;
      return;
    }
    _seekBusy = true;
    try {
      await _ch.invokeMethod('seek', {'t': t});
      while (_seekNext != null) {
        final double n = _seekNext!;
        _seekNext = null;
        await _ch.invokeMethod('seek', {'t': n});
      }
    } catch (_) {
    } finally {
      _seekBusy = false;
    }
  }

  bool _onScroll(ScrollNotification n) {
    if (n is ScrollStartNotification && n.dragDetails != null) {
      _userDrag = true;
      if (_playing) {
        _ch.invokeMethod('pause');
        _ticker.stop();
        setState(() => _playing = false);
      }
    } else if (n is ScrollUpdateNotification && _userDrag) {
      _seek((n.metrics.pixels / _pps).clamp(0.0, _total));
    } else if (n is ScrollEndNotification && _userDrag) {
      _userDrag = false;
      _seek((n.metrics.pixels / _pps).clamp(0.0, _total));
    }
    return false;
  }

  /// The clip under the playhead (or the selected one).
  _Clip? get _current {
    if (_sel != null) {
      for (final _Clip c in _clips) {
        if (c.id == _sel) return c;
      }
    }
    double start = 0;
    for (final _Clip c in _clips) {
      if (_time.value < start + c.dur) return c;
      start += c.dur;
    }
    return _clips.isEmpty ? null : _clips.last;
  }

  // ------------------------------------------------------------- edits
  void _open(_Panel p) {
    if (p != _Panel.ratio &&
        p != _Panel.effects &&
        p != _Panel.filters &&
        p != _Panel.adjust &&
        p != _Panel.background &&
        _clips.isEmpty) {
      _status.value = 'Add photos or videos first';
      return;
    }
    if ((p == _Panel.edit || p == _Panel.templates || p == _Panel.duration) &&
        _sel == null) {
      _sel = _current?.id;
    }
    if (p == _Panel.templates || p == _Panel.effects) _loadThumbs();
    setState(() => _panel = p);
  }

  Future<void> _loadThumbs() async {
    final _Clip? c = _current ?? (_clips.isEmpty ? null : _clips.first);
    if (c == null || _thumbs.containsKey(c.media)) return;
    _thumbs[c.media] = const {};
    try {
      final Map<dynamic, dynamic>? r = await _ch
          .invokeMapMethod<dynamic, dynamic>('thumbs', {'media': c.media});
      if (!mounted || r == null) return;
      setState(() => _thumbs[c.media] = {
            for (final MapEntry<dynamic, dynamic> e in r.entries)
              e.key as String: e.value as Uint8List,
          });
    } catch (_) {
      _thumbs.remove(c.media);
    }
  }

  Uint8List? _thumbFor(String id) {
    final _Clip? c = _current ?? (_clips.isEmpty ? null : _clips.first);
    if (c == null) return null;
    return _thumbs[c.media]?[id];
  }

  void _setTemplate(String id, {bool all = false}) {
    setState(() {
      if (all) {
        for (final _Clip c in _clips) {
          c.template = id;
        }
      } else {
        _current?.template = id;
      }
    });
    _sendTimeline();
  }

  void _toggleEffect(String id) {
    if (_effects.contains(id)) {
      setState(() => _effects = [..._effects]..remove(id));
    } else if (_effects.length >= flyMaxEffects) {
      _status.value = 'Up to $flyMaxEffects effects - remove one first';
      return;
    } else {
      setState(() => _effects = [..._effects, id]);
    }
    _status.value = '';
    _sendLook();
  }

  void _deleteClip() {
    final _Clip? c = _current;
    if (c == null) return;
    setState(() {
      _clips.remove(c);
      _sel = null;
      _panel = _Panel.none;
    });
    _sendTimeline();
  }

  void _moveClip(int dir) {
    final _Clip? c = _current;
    if (c == null) return;
    final int i = _clips.indexOf(c);
    final int j = i + dir;
    if (j < 0 || j >= _clips.length) return;
    setState(() {
      _clips.removeAt(i);
      _clips.insert(j, c);
    });
    _sendTimeline();
  }

  /// Cut the clip under the playhead into two clips at that point.
  void _splitClip() {
    double start = 0;
    for (int i = 0; i < _clips.length; i++) {
      final _Clip c = _clips[i];
      final double local = _time.value - start;
      if (local >= 0 && local < c.dur) {
        if (local < 0.3 || c.dur - local < 0.3) {
          _status.value = 'Move the playhead into the middle of a clip';
          return;
        }
        final _Clip right = _Clip(
          id: _nextClip++,
          media: c.media,
          video: c.video,
          dur: c.dur - local,
          srcDur: c.srcDur,
          thumb: c.thumb,
        )
          ..template = c.template
          ..from = c.from + local;
        setState(() {
          c.dur = local;
          _clips.insert(i + 1, right);
          _sel = right.id;
        });
        _status.value = '';
        _sendTimeline();
        return;
      }
      start += c.dur;
    }
    _status.value = 'Move the playhead onto a clip first';
  }

  /// Copy the current clip and put the copy right after it.
  void _duplicateClip() {
    final _Clip? c = _current;
    if (c == null) return;
    final int i = _clips.indexOf(c);
    final _Clip copy = _Clip(
      id: _nextClip++,
      media: c.media,
      video: c.video,
      dur: c.dur,
      srcDur: c.srcDur,
      thumb: c.thumb,
    )
      ..template = c.template
      ..from = c.from;
    setState(() {
      _clips.insert(i + 1, copy);
      _sel = copy.id;
    });
    _sendTimeline();
  }

  Future<void> _setRatio(String r) async {
    try {
      final Map<dynamic, dynamic>? m = await _ch
          .invokeMapMethod<dynamic, dynamic>('setRatio', {'ratio': r});
      if (!mounted || m == null) return;
      setState(() {
        _ratio = r;
        _texW = (m['width'] as num).toDouble();
        _texH = (m['height'] as num).toDouble();
        _thumbs.clear();
      });
    } catch (_) {}
  }

  Future<void> _export() async {
    if (_clips.isEmpty || _exporting) return;
    if (_playing) await _togglePlay();
    setState(() => _exporting = true);
    _progress.value = 0;
    _status.value = 'Making the video...';
    final Timer poll =
        Timer.periodic(const Duration(milliseconds: 200), (_) async {
      try {
        _progress.value = (await _ch.invokeMethod<double>('progress')) ?? 0;
      } catch (_) {}
    });
    try {
      final Map<dynamic, dynamic>? r =
          await _ch.invokeMapMethod<dynamic, dynamic>('export');
      if (r != null) {
        final double secs = ((r['ms'] as num?) ?? 0) / 1000;
        final double mb = ((r['bytes'] as num?) ?? 0) / (1024 * 1024);
        _status.value = 'Saved: ${r['uri']}\n'
            '${r['frames']} frames · ${mb.toStringAsFixed(1)} MB · '
            'made in ${secs.toStringAsFixed(1)} s';
      }
    } catch (e) {
      _status.value = 'Export failed: $e';
    } finally {
      poll.cancel();
      if (mounted) setState(() => _exporting = false);
    }
  }

  // ---------------------------------------------------------------- UI
  String _fmt(double t) {
    final int s = t.floor();
    return '${(s ~/ 60).toString().padLeft(2, '0')}:'
        '${(s % 60).toString().padLeft(2, '0')}';
  }

  Widget _timeline() {
    return LayoutBuilder(builder: (context, box) {
      final double half = box.maxWidth / 2;
      return SizedBox(
        height: 66,
        child: Stack(
          children: [
            NotificationListener<ScrollNotification>(
              onNotification: _onScroll,
              child: ListView(
                controller: _sc,
                scrollDirection: Axis.horizontal,
                padding: EdgeInsets.fromLTRB(half, 5, half - 60, 5),
                children: [
                  for (final _Clip c in _clips) _clipBox(c),
                  GestureDetector(
                    onTap: _addMedia,
                    child: Container(
                      width: 52,
                      margin: const EdgeInsets.only(left: 6),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: const Icon(Icons.add, color: Colors.black),
                    ),
                  ),
                ],
              ),
            ),
            Positioned(
              left: half - 1,
              top: 0,
              bottom: 0,
              child: IgnorePointer(
                child: Container(width: 2, color: Colors.white),
              ),
            ),
          ],
        ),
      );
    });
  }

  Widget _clipBox(_Clip c) {
    final bool sel = _sel == c.id;
    return GestureDetector(
      onTap: () {
        setState(() {
          _sel = sel ? null : c.id;
          _panel = sel ? _Panel.none : _Panel.edit;
        });
        if (!sel) _loadThumbs();
      },
      child: Container(
        width: (c.dur * _pps).clamp(24.0, 4000.0),
        margin: const EdgeInsets.only(right: 2),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
              color: sel ? Colors.white : Colors.transparent, width: 2),
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(4),
          child: Stack(
            fit: StackFit.expand,
            children: [
              Image.memory(c.thumb,
                  fit: BoxFit.fitHeight,
                  repeat: ImageRepeat.repeatX,
                  alignment: Alignment.centerLeft,
                  gaplessPlayback: true),
              Positioned(
                left: 4,
                bottom: 2,
                right: 4,
                child: Text(
                  '${c.video ? '▶ ' : ''}${c.dur.toStringAsFixed(1)}s'
                  '${c.template == 'plain' ? '' : ' · ${flyLabel(c.template)}'}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      shadows: [Shadow(blurRadius: 3)]),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _tool(IconData icon, String label, VoidCallback onTap,
      {bool active = false}) {
    return InkWell(
      onTap: _exporting ? null : onTap,
      child: SizedBox(
        width: 72,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 24, color: active ? const Color(0xFFFF7EB6) : null),
            const SizedBox(height: 4),
            Text(label,
                style: const TextStyle(fontSize: 11.5),
                maxLines: 1,
                overflow: TextOverflow.ellipsis),
          ],
        ),
      ),
    );
  }

  Widget _mainToolbar() {
    return SizedBox(
      height: 70,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 6),
        children: [
          _tool(Icons.content_cut, 'Edit', () => _open(_Panel.edit)),
          _tool(Icons.auto_awesome_motion, 'Templates',
              () => _open(_Panel.templates)),
          _tool(Icons.auto_awesome, 'Effects', () => _open(_Panel.effects),
              active: _effects.isNotEmpty),
          _tool(Icons.filter_vintage_outlined, 'Filters',
              () => _open(_Panel.filters)),
          _tool(Icons.tune, 'Adjust', () => _open(_Panel.adjust)),
          _tool(Icons.crop_square, 'Ratio', () => _open(_Panel.ratio)),
          _tool(Icons.texture, 'Background', () => _open(_Panel.background)),
          _tool(Icons.add_photo_alternate_outlined, 'Add', _addMedia),
        ],
      ),
    );
  }

  Widget _panelHeader(String title) {
    return SizedBox(
      height: 34,
      child: Row(
        children: [
          const SizedBox(width: 16),
          Expanded(
            child: Text(title,
                style: const TextStyle(
                    fontSize: 13.5, fontWeight: FontWeight.w800)),
          ),
          IconButton(
            visualDensity: VisualDensity.compact,
            onPressed: () => setState(() => _panel = _Panel.none),
            icon: const Icon(Icons.check),
          ),
        ],
      ),
    );
  }

  Widget _catRow(List<String> cats, String cur, ValueChanged<String> pick) {
    return SizedBox(
      height: 28,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        children: [
          for (final String c in cats)
            GestureDetector(
              onTap: () => pick(c),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 9),
                child: Center(
                  child: Text(c,
                      style: TextStyle(
                          fontSize: 13,
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

  Widget _card(FlyItem t, bool sel, VoidCallback onTap, {int badge = 0}) {
    final Uint8List? img = _thumbFor(t.id);
    return GestureDetector(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4),
        child: SizedBox(
          width: 58,
          child: Column(
            children: [
              Container(
                width: 58,
                height: 84,
                padding: const EdgeInsets.all(2),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(10),
                  gradient: sel ? _brand : null,
                ),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: img == null || img.isEmpty
                          ? Container(
                              color: Colors.white10,
                              alignment: Alignment.center,
                              child: const SizedBox(
                                width: 14,
                                height: 14,
                                child: CircularProgressIndicator(
                                    strokeWidth: 1.5),
                              ),
                            )
                          : Image.memory(img,
                              fit: BoxFit.cover, gaplessPlayback: true),
                    ),
                    if (badge > 0)
                      Positioned(
                        right: 3,
                        top: 3,
                        child: Container(
                          width: 17,
                          height: 17,
                          alignment: Alignment.center,
                          decoration: const BoxDecoration(
                              shape: BoxShape.circle, gradient: _brand),
                          child: Text('$badge',
                              style: const TextStyle(
                                  fontSize: 10.5,
                                  fontWeight: FontWeight.w800)),
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 3),
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

  Widget _editPanel() {
    final _Clip? c = _current;
    return Column(
      children: [
        _panelHeader(c == null
            ? 'Edit'
            : 'Clip ${_clips.indexOf(c) + 1} of ${_clips.length}'
                '${c.video ? ' · video' : ' · photo'}'),
        SizedBox(
          height: 70,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 6),
            children: [
              if (c != null)
                _tool(Icons.timer_outlined, c.video ? 'Trim' : 'Duration',
                    () => setState(() => _panel = _Panel.duration)),
              _tool(Icons.content_cut, 'Split at playhead', _splitClip),
              _tool(Icons.copy_outlined, 'Duplicate', _duplicateClip),
              _tool(Icons.auto_awesome_motion, 'Template',
                  () => _open(_Panel.templates)),
              _tool(Icons.chevron_left, 'Move left', () => _moveClip(-1)),
              _tool(Icons.chevron_right, 'Move right', () => _moveClip(1)),
              _tool(Icons.delete_outline, 'Delete', _deleteClip),
            ],
          ),
        ),
      ],
    );
  }

  Widget _durationPanel() {
    final _Clip? c = _current;
    return Column(
      children: [
        _panelHeader(c != null && c.video ? 'Trim' : 'Duration'),
        if (c != null)
          SizedBox(
            height: 70,
            child: Row(
              children: [
                const SizedBox(width: 16),
                SizedBox(
                    width: 44,
                    child: Text('${c.dur.toStringAsFixed(1)}s',
                        style: const TextStyle(fontWeight: FontWeight.w800))),
                Expanded(
                  child: Slider(
                    value: c.dur.clamp(0.5, c.maxDur),
                    min: 0.5,
                    max: c.maxDur,
                    divisions: ((c.maxDur - 0.5) * 10).round().clamp(1, 5000),
                    onChanged: (v) => setState(() => c.dur = v),
                    onChangeEnd: (_) => _sendTimeline(),
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }

  Widget _templatesPanel() {
    final _Clip? c = _current;
    final List<FlyItem> list = _tplCat == 'For You'
        ? [
            flyNone,
            for (final String id in flyForYou)
              flyTemplates.firstWhere((t) => t.id == id),
          ]
        : flyTemplates.where((t) => t.cat == _tplCat).toList();
    return Column(
      children: [
        SizedBox(
          height: 34,
          child: Row(
            children: [
              const SizedBox(width: 16),
              Expanded(
                child: Text(
                    c == null ? 'Templates' : 'Template · clip ${_clips.indexOf(c) + 1}',
                    style: const TextStyle(
                        fontSize: 13.5, fontWeight: FontWeight.w800)),
              ),
              TextButton(
                onPressed: c == null
                    ? null
                    : () => _setTemplate(c.template, all: true),
                child: const Text('Apply to all'),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                onPressed: () => setState(() => _panel = _Panel.none),
                icon: const Icon(Icons.check),
              ),
            ],
          ),
        ),
        _catRow(flyTemplateCats, _tplCat, (v) => setState(() => _tplCat = v)),
        SizedBox(
          height: 112,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.fromLTRB(10, 6, 10, 2),
            children: [
              for (final FlyItem t in list)
                _card(t, c?.template == t.id, () => _setTemplate(t.id)),
            ],
          ),
        ),
      ],
    );
  }

  Widget _effectsPanel() {
    return Column(
      children: [
        SizedBox(
          height: 34,
          child: Row(
            children: [
              const SizedBox(width: 16),
              Expanded(
                child: Text(
                    _effects.isEmpty
                        ? 'Effects · on every clip (up to $flyMaxEffects)'
                        : 'Effects · ${_effects.map(flyLabel).join(' + ')}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 13.5, fontWeight: FontWeight.w800)),
              ),
              if (_effects.isNotEmpty)
                TextButton(
                  onPressed: () {
                    setState(() => _effects = const []);
                    _sendLook();
                  },
                  child: const Text('Clear'),
                ),
              IconButton(
                visualDensity: VisualDensity.compact,
                onPressed: () => setState(() => _panel = _Panel.none),
                icon: const Icon(Icons.check),
              ),
            ],
          ),
        ),
        _catRow(flyEffectCats, _fxCat, (v) => setState(() => _fxCat = v)),
        SizedBox(
          height: 112,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.fromLTRB(10, 6, 10, 2),
            children: [
              for (final FlyItem t in flyEffects.where((e) => e.cat == _fxCat))
                _card(t, _effects.contains(t.id), () => _toggleEffect(t.id),
                    badge: _effects.contains(t.id)
                        ? _effects.indexOf(t.id) + 1
                        : 0),
            ],
          ),
        ),
      ],
    );
  }

  void _setAdj(int i, double v) {
    final List<double> a = [..._adj.value];
    a[i] = v;
    _adj.value = a;
  }

  Widget _slider(String label, int i, double min, double max) {
    return ValueListenableBuilder<List<double>>(
      valueListenable: _adj,
      builder: (context, a, _) => SizedBox(
        height: 36,
        child: Row(
          children: [
            const SizedBox(width: 16),
            SizedBox(
                width: 80,
                child: Text(label, style: const TextStyle(fontSize: 12))),
            Expanded(
              child: Slider(
                  value: a[i].clamp(min, max),
                  min: min,
                  max: max,
                  onChanged: (v) => _setAdj(i, v)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _filtersPanel() {
    return Column(
      children: [
        _panelHeader('Filters'),
        ValueListenableBuilder<List<double>>(
          valueListenable: _adj,
          builder: (context, a, _) => SizedBox(
            height: 76,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              children: [
                for (int i = 0; i < flyFilters.length; i++)
                  GestureDetector(
                    onTap: () => _setAdj(6, i.toDouble()),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      child: Column(
                        children: [
                          Container(
                            width: 46,
                            height: 46,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: flyFilters[i].$2,
                              border: Border.all(
                                  color: a[6].round() == i
                                      ? Colors.white
                                      : Colors.transparent,
                                  width: 2.5),
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(flyFilters[i].$1,
                              style: const TextStyle(fontSize: 11)),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
        _slider('Strength', 7, 0, 1),
      ],
    );
  }

  Widget _adjustPanel() {
    return Column(
      children: [
        SizedBox(
          height: 34,
          child: Row(
            children: [
              const SizedBox(width: 16),
              const Expanded(
                child: Text('Adjust',
                    style: TextStyle(
                        fontSize: 13.5, fontWeight: FontWeight.w800)),
              ),
              TextButton(
                onPressed: () {
                  final List<double> a = _adj.value;
                  _adj.value = [0, 1, 1, 0, 0, 0, a[6], a[7]];
                },
                child: const Text('Reset'),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                onPressed: () => setState(() => _panel = _Panel.none),
                icon: const Icon(Icons.check),
              ),
            ],
          ),
        ),
        SizedBox(
          height: 128,
          child: ListView(
            padding: EdgeInsets.zero,
            children: [
              _slider('Brightness', 0, -0.25, 0.25),
              _slider('Contrast', 1, 0.6, 1.5),
              _slider('Saturation', 2, 0, 2),
              _slider('Warmth', 3, -1, 1),
              _slider('Vignette', 4, 0, 1),
              _slider('Grain', 5, 0, 1),
            ],
          ),
        ),
      ],
    );
  }

  Widget _ratioPanel() {
    return Column(
      children: [
        _panelHeader('Aspect ratio'),
        SizedBox(
          height: 44,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              for (final String r in _ratios)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 5),
                  child: ChoiceChip(
                    label: Text(r),
                    selected: _ratio == r,
                    onSelected: (_) => _setRatio(r),
                  ),
                ),
            ],
          ),
        ),
        SizedBox(
          height: 44,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              ChoiceChip(
                label: const Text('Fill'),
                selected: !_fit,
                onSelected: (_) {
                  setState(() {
                    _fit = false;
                    _thumbs.clear();
                  });
                  _sendLook();
                },
              ),
              const SizedBox(width: 10),
              ChoiceChip(
                label: const Text('Fit (whole picture)'),
                selected: _fit,
                onSelected: (_) {
                  setState(() {
                    _fit = true;
                    _thumbs.clear();
                  });
                  _sendLook();
                },
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _backgroundPanel() {
    return Column(
      children: [
        _panelHeader('Background (shows with Fit)'),
        SizedBox(
          height: 78,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            children: [
              for (int i = 0; i < _backgrounds.length; i++)
                GestureDetector(
                  onTap: () {
                    setState(() {
                      _bg = i;
                      _fit = true;
                      _thumbs.clear();
                    });
                    _sendLook();
                  },
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 6),
                    child: Column(
                      children: [
                        Container(
                          width: 46,
                          height: 46,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: _backgrounds[i].$2 ?? Colors.white24,
                            border: Border.all(
                                color: _fit && _bg == i
                                    ? const Color(0xFFFF7EB6)
                                    : Colors.white24,
                                width: 2.5),
                          ),
                          child: _backgrounds[i].$2 == null
                              ? const Icon(Icons.blur_on, size: 22)
                              : null,
                        ),
                        const SizedBox(height: 4),
                        Text(_backgrounds[i].$1,
                            style: const TextStyle(fontSize: 11)),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _bottom() {
    return switch (_panel) {
      _Panel.none => _mainToolbar(),
      _Panel.edit => _editPanel(),
      _Panel.duration => _durationPanel(),
      _Panel.templates => _templatesPanel(),
      _Panel.effects => _effectsPanel(),
      _Panel.filters => _filtersPanel(),
      _Panel.adjust => _adjustPanel(),
      _Panel.ratio => _ratioPanel(),
      _Panel.background => _backgroundPanel(),
    };
  }

  @override
  Widget build(BuildContext context) {
    final int? tex = _textureId;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        title: const Text('New Project'),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 10),
            child: FilledButton(
              onPressed: _clips.isEmpty || _exporting ? null : _export,
              style: FilledButton.styleFrom(
                visualDensity: VisualDensity.compact,
                backgroundColor: const Color(0xFFFF4B6E),
                foregroundColor: Colors.white,
              ),
              child: Text(_exporting ? 'Saving...' : 'Export'),
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: Center(
              child: tex == null
                  ? const CircularProgressIndicator()
                  : _clips.isEmpty
                      ? FilledButton.icon(
                          onPressed: _adding ? null : _addMedia,
                          icon: const Icon(Icons.add_photo_alternate_outlined),
                          label: Text(_adding
                              ? 'Adding...'
                              : 'Add photos / videos'),
                        )
                      : AspectRatio(
                          aspectRatio: _texW / _texH,
                          child: Texture(textureId: tex),
                        ),
            ),
          ),
          SizedBox(
            height: 40,
            child: Row(
              children: [
                const SizedBox(width: 16),
                Expanded(
                  child: ValueListenableBuilder<double>(
                    valueListenable: _time,
                    builder: (context, t, _) => Text(
                        '${_fmt(t)} / ${_fmt(_total)}',
                        style: const TextStyle(fontSize: 12.5)),
                  ),
                ),
                IconButton(
                  onPressed: _clips.isEmpty || _exporting ? null : _togglePlay,
                  icon: Icon(_playing
                      ? Icons.pause_rounded
                      : Icons.play_arrow_rounded),
                  iconSize: 30,
                ),
                const Expanded(child: SizedBox()),
              ],
            ),
          ),
          _timeline(),
          ValueListenableBuilder<String>(
            valueListenable: _status,
            builder: (context, s, _) => s.isEmpty
                ? const SizedBox(height: 6)
                : Padding(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
                    child: Text(s,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 11.5)),
                  ),
          ),
          if (_exporting)
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 4, 24, 0),
              child: ValueListenableBuilder<double>(
                valueListenable: _progress,
                builder: (context, p, _) => LinearProgressIndicator(value: p),
              ),
            ),
          const Divider(height: 10, color: Colors.white12),
          AnimatedSize(
            duration: const Duration(milliseconds: 160),
            child: _bottom(),
          ),
          SizedBox(height: MediaQuery.paddingOf(context).bottom + 6),
        ],
      ),
    );
  }
}
