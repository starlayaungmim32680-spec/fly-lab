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
// E3 Text (19 Nov 2026): Text tool - words in Myanmar or English (11 fonts
// in assets/fonts, OFL), 6 styles, colours, animations in / loop / out;
// orange bars on a text track; on the video: drag, pinch, twist.
// E2 Audio (19 Nov 2026): Audio tool - Add music from the phone (green
// bars on an audio track: drag, trim, volume, fade in / out), the clips'
// own sound on / off, and a Volume per video clip (Edit -> Volume).
// Effects (18 Nov 2026): an EFFECTS TRACK like CapCut - each effect is a
// bar under the clips (tap a card = a 3 s bar at the playhead; drag the bar
// / its ends), and can be placed on the video (a circle: drag + pinch).
// Effect cards show the built-in Sample picture or My clip.
// E1b (done): split at playhead, duplicate, trim (video), duration (photo),
// move left / right, delete. E1c: speed (video 0.5x - 3x), long-press a
// clip and drag to reorder. Next: E2 Audio, E3 Text, E4 Stickers +
// Overlay, E5 Transitions.
import 'dart:async';
import 'dart:math' as math;

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

  /// Playback speed (video only): 2.0 = twice as fast, so the same source
  /// footage takes half the timeline time.
  double speed = 1;

  /// The clip's own sound (video): 0 = mute, 1 = as recorded, 2 = louder.
  double vol = 1;

  /// The longest this clip can be on the timeline: the source left from
  /// [from] on, played at [speed].
  double get maxDur => video
      ? ((srcDur - from) / speed).clamp(0.5, 600.0).toDouble()
      : 10.0;

  Map<String, Object> toMap() => {
        'id': id,
        'media': media,
        'dur': dur,
        'template': template,
        'from': from,
        'speed': speed,
        'vol': vol,
      };
}

/// One effect on the EFFECTS TRACK (like CapCut): on screen from [start] to
/// [end] (project seconds). [placed] = only inside a circle the user drags
/// on the video ([x], [y] 0..1 from the top-left, radius [r] in frame
/// heights); otherwise the whole frame.
class _Fx {
  _Fx({required this.uid, required this.id, required this.start, required this.end});
  final int uid;
  String id;
  double start;
  double end;
  double x = 0.5;
  double y = 0.5;
  double r = 0.3;
  bool placed = false;

  Map<String, Object> toMap() => {
        'id': id,
        'start': start,
        'end': end,
        'x': x,
        'y': y,
        'r': r,
        'placed': placed,
      };
}

/// One music bar on the AUDIO TRACK (E2): plays the song from [from] on,
/// on the project from [start] to [end], at [vol] with fades.
class _Audio {
  _Audio({
    required this.uid,
    required this.path,
    required this.name,
    required this.srcDur,
    required this.start,
    required this.end,
  });
  final int uid;
  final String path;
  final String name;
  final double srcDur;
  double start;
  double end;
  double from = 0;
  double vol = 1;
  double fadeIn = 0;
  double fadeOut = 0;

  Map<String, Object> toMap() => {
        'uid': uid,
        'path': path,
        'start': start,
        'end': end,
        'from': from,
        'vol': vol,
        'fadeIn': fadeIn,
        'fadeOut': fadeOut,
      };
}

/// One text on the TEXT TRACK (E3): drawn by the engine with Android's text
/// layout (Myanmar shaped right). [x], [y] = centre (0..1 from the
/// top-left), [scale] 1 = 96 px letters on a 1280-high frame, [rot] degrees.
class _Txt {
  _Txt({required this.uid, required this.text, required this.start, required this.end});
  final int uid;
  String text;
  double start;
  double end;
  double x = 0.5;
  double y = 0.5;
  double scale = 1;
  double rot = 0;
  int font = 4;
  int style = 0;
  int color = 0xFFFFFFFF;
  int color2 = 0xFF000000;
  int animIn = 1;
  int animLoop = 0;
  int animOut = 1;
  double bw = 0; // the engine's bitmap size (for the box on the video)
  double bh = 0;

  _Txt copy(int newUid) => _Txt(uid: newUid, text: text, start: start, end: end)
    ..x = x
    ..y = (y + 0.08).clamp(0.0, 1.0)
    ..scale = scale
    ..rot = rot
    ..font = font
    ..style = style
    ..color = color
    ..color2 = color2
    ..animIn = animIn
    ..animLoop = animLoop
    ..animOut = animOut
    ..bw = bw
    ..bh = bh;

  Map<String, Object> toMap() => {
        'uid': uid,
        'text': text,
        'start': start,
        'end': end,
        'x': x,
        'y': y,
        'scale': scale,
        'rot': rot,
        'font': font,
        'style': style,
        'color': color,
        'color2': color2,
        'animIn': animIn,
        'animLoop': animLoop,
        'animOut': animOut,
      };
}

const List<String> _fontNames = [
  'Default', 'Padauk', 'Padauk Bold', 'Noto Sans', 'Noto Black',
  'Noto Serif', 'Pacifico', 'Bebas', 'Lobster', 'Marker', 'Dancing', 'Bangers',
];
const List<String> _txStyles = ['Plain', 'Outline', 'Box', 'Neon', '3D', 'Gradient'];
const List<String> _txIn = ['None', 'Fade', 'Pop', 'Slide up', 'Typewriter', 'Bounce', 'Zoom'];
const List<String> _txLoop = ['None', 'Pulse', 'Wiggle', 'Float', 'Blink'];
const List<String> _txOut = ['None', 'Fade', 'Pop', 'Slide down'];
const List<int> _txColors = [
  0xFFFFFFFF, 0xFF000000, 0xFFFFE14D, 0xFFFF7EB6, 0xFFFF3B5C, 0xFFFF9F43,
  0xFF2ED573, 0xFF39D5FF, 0xFF3D7BFF, 0xFFA55EEA, 0xFFC8A165, 0xCC000000,
];

enum _Panel {
  none,
  edit,
  duration,
  speed,
  volume,
  audio,
  audioEdit,
  text,
  textEdit,
  fxEdit,
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
  final List<_Fx> _fx = [];
  int _nextFx = 1;
  int? _fxSel; // the effect bar being edited
  bool _sampleCards = true; // effect cards on the sample picture or my clip
  final List<_Txt> _texts = [];
  int _nextTx = 1;
  int? _txSel; // the text being edited
  int _txTab = 0; // Edit, Font, Style, Color, Animation
  List<Uint8List>? _fontPrev;
  bool _txBusy = false;
  bool _txAgain = false;
  double _s0 = 1;
  double _rot0 = 0;
  final List<_Audio> _music = [];
  int _nextAu = 1;
  int? _auSel; // the music bar being edited
  bool _origOn = true; // the clips' own sound
  bool _audioBusy = false;
  bool _audioAgain = false;
  bool _picking = false;
  bool _lookBusy = false;
  bool _lookAgain = false;
  String _tplCat = 'For You';
  String _fxCat = 'Trending';
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
    if (_fx.isNotEmpty) {
      _fitFx();
      _sendLook();
    }
    if (_music.isNotEmpty) {
      _fitAudio();
      _sendAudio();
    }
    if (_texts.isNotEmpty) {
      _fitTexts();
      _sendTexts();
    }
    try {
      await _ch.invokeMethod('setTimeline', {
        'clips': [for (final _Clip c in _clips) c.toMap()],
      });
    } catch (_) {}
  }

  /// Sends the look; calls that come while one is on the way are merged,
  /// so dragging an effect on the video stays smooth.
  Future<void> _sendLook() async {
    if (_lookBusy) {
      _lookAgain = true;
      return;
    }
    _lookBusy = true;
    try {
      do {
        _lookAgain = false;
        final Color? bg = _backgrounds[_bg].$2;
        await _ch.invokeMethod('setLook', {
          'adj': _adj.value,
          'fx': [for (final _Fx f in _fx) f.toMap()],
          'fit': _fit,
          'bgMode': bg == null ? 0 : 1,
          'bgColor': bg == null ? [0.0, 0.0, 0.0] : [bg.r, bg.g, bg.b],
        });
      } while (_lookAgain && mounted);
    } catch (_) {
    } finally {
      _lookBusy = false;
    }
  }

  /// Keep every effect bar inside the project after the clips change.
  void _fitFx() {
    final double tot = _total;
    _fx.removeWhere((f) => f.start >= tot - 0.1);
    for (final _Fx f in _fx) {
      if (f.end > tot) f.end = tot;
      if (f.end - f.start < 0.3) f.start = math.max(0.0, f.end - 0.3);
    }
  }

  // ------------------------------------------------------------- text
  Future<void> _sendTexts() async {
    if (_txBusy) {
      _txAgain = true;
      return;
    }
    _txBusy = true;
    try {
      do {
        _txAgain = false;
        await _ch.invokeMethod('setTexts', {
          'texts': [for (final _Txt t in _texts) t.toMap()],
        });
      } while (_txAgain && mounted);
    } catch (_) {
    } finally {
      _txBusy = false;
    }
  }

  void _fitTexts() {
    final double tot = _total;
    _texts.removeWhere((t) => t.start >= tot - 0.1);
    for (final _Txt t in _texts) {
      if (t.end > tot) t.end = tot;
      if (t.end - t.start < 0.3) t.start = math.max(0.0, t.end - 0.3);
    }
  }

  _Txt? get _selTx {
    for (final _Txt t in _texts) {
      if (t.uid == _txSel) return t;
    }
    return null;
  }

  /// Ask the engine how big the words are (for the box on the video).
  Future<void> _measure(_Txt t) async {
    try {
      final List<dynamic>? r =
          await _ch.invokeListMethod<dynamic>('textSize', {'text': t.toMap()});
      if (!mounted || r == null || r.length < 2) return;
      setState(() {
        t.bw = (r[0] as num).toDouble();
        t.bh = (r[1] as num).toDouble();
      });
    } catch (_) {}
  }

  /// Changing how the text looks: send it, then measure it again.
  void _restyle(_Txt t, VoidCallback change) {
    setState(change);
    _sendTexts();
    _measure(t);
  }

  Future<String?> _askText(String initial) {
    final TextEditingController tc = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF1C1C1E),
        title: const Text('Text'),
        content: TextField(
          controller: tc,
          autofocus: true,
          minLines: 1,
          maxLines: 5,
          decoration: const InputDecoration(hintText: 'Type here · မြန်မာလို ရေးလို့ရပါတယ်'),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, tc.text.trim()),
              child: const Text('OK')),
        ],
      ),
    );
  }

  Future<void> _addText() async {
    if (_clips.isEmpty) return;
    final String? words = await _askText('');
    if (!mounted || words == null || words.isEmpty) return;
    final double tot = _total;
    double start = _time.value.clamp(0.0, tot);
    double end = math.min(start + 3, tot);
    if (end - start < 0.5) {
      start = math.max(0.0, tot - 3);
      end = tot;
    }
    final _Txt t = _Txt(uid: _nextTx++, text: words, start: start, end: end);
    setState(() {
      _texts.add(t);
      _txSel = t.uid;
      _sel = null;
      _fxSel = null;
      _auSel = null;
      _txTab = 0;
      _panel = _Panel.textEdit;
    });
    _sendTexts();
    _measure(t);
  }

  Future<void> _editText(_Txt t) async {
    final String? words = await _askText(t.text);
    if (!mounted || words == null || words.isEmpty) return;
    _restyle(t, () => t.text = words);
  }

  Future<void> _loadFonts() async {
    if (_fontPrev != null) return;
    _fontPrev = const [];
    try {
      final List<dynamic>? r = await _ch.invokeListMethod<dynamic>('fontPreviews');
      if (!mounted || r == null) return;
      setState(() => _fontPrev = [for (final dynamic b in r) b as Uint8List]);
    } catch (_) {
      _fontPrev = null;
    }
  }

  void _moveTx(_Txt t, double dt) {
    final double len = t.end - t.start;
    final double ns = (t.start + dt).clamp(0.0, math.max(0.0, _total - len));
    setState(() {
      t.start = ns;
      t.end = ns + len;
    });
    _sendTexts();
  }

  void _trimTx(_Txt t, {double dStart = 0, double dEnd = 0}) {
    setState(() {
      if (dStart != 0) t.start = (t.start + dStart).clamp(0.0, t.end - 0.3);
      if (dEnd != 0) {
        t.end = (t.end + dEnd)
            .clamp(t.start + 0.3, math.max(t.start + 0.3, _total));
      }
    });
    _sendTexts();
  }

  void _deleteTx() {
    final _Txt? t = _selTx;
    if (t == null) return;
    setState(() {
      _texts.remove(t);
      _txSel = null;
      _panel = _Panel.text;
    });
    _sendTexts();
  }

  void _duplicateTx() {
    final _Txt? t = _selTx;
    if (t == null) return;
    final _Txt c = t.copy(_nextTx++);
    setState(() {
      _texts.add(c);
      _txSel = c.uid;
    });
    _sendTexts();
  }

  // ------------------------------------------------------------ audio
  Future<void> _sendAudio() async {
    if (_audioBusy) {
      _audioAgain = true;
      return;
    }
    _audioBusy = true;
    try {
      do {
        _audioAgain = false;
        await _ch.invokeMethod('setAudio', {
          'music': [for (final _Audio a in _music) a.toMap()],
          'original': _origOn,
        });
      } while (_audioAgain && mounted);
    } catch (_) {
    } finally {
      _audioBusy = false;
    }
  }

  void _fitAudio() {
    final double tot = _total;
    _music.removeWhere((a) => a.start >= tot - 0.1);
    for (final _Audio a in _music) {
      if (a.end > tot) a.end = tot;
      if (a.end - a.start < 0.5) a.start = math.max(0.0, a.end - 0.5);
    }
  }

  _Audio? get _selAu {
    for (final _Audio a in _music) {
      if (a.uid == _auSel) return a;
    }
    return null;
  }

  /// Pick a song from the phone; it goes on the audio track at the playhead.
  Future<void> _addMusic() async {
    if (_picking || _clips.isEmpty) return;
    _picking = true;
    try {
      final Map<dynamic, dynamic>? r =
          await _ch.invokeMapMethod<dynamic, dynamic>('pickAudio');
      if (!mounted || r == null) return;
      final double dur = (r['dur'] as num).toDouble();
      final double tot = _total;
      double start = _time.value;
      if (start > tot - 0.5) start = 0;
      final _Audio a = _Audio(
        uid: _nextAu++,
        path: r['path'] as String,
        name: r['name'] as String,
        srcDur: dur,
        start: start,
        end: math.min(start + dur, tot),
      );
      setState(() {
        _music.add(a);
        _auSel = a.uid;
        _fxSel = null;
        _sel = null;
        _panel = _Panel.audioEdit;
      });
      _status.value = '';
      _sendAudio();
    } catch (e) {
      _status.value = 'Could not add the song: $e';
    } finally {
      _picking = false;
    }
  }

  void _moveAu(_Audio a, double dt) {
    final double len = a.end - a.start;
    final double ns = (a.start + dt).clamp(0.0, math.max(0.0, _total - len));
    setState(() {
      a.start = ns;
      a.end = ns + len;
    });
    _sendAudio();
  }

  /// Left end: the song keeps its place in time (like CapCut), so the part
  /// that is cut off is skipped. Right end: where it stops.
  void _trimAu(_Audio a, {double dStart = 0, double dEnd = 0}) {
    setState(() {
      if (dStart != 0) {
        final double d = dStart
            .clamp(-math.min(a.from, a.start), a.end - a.start - 0.5)
            .toDouble();
        a.start += d;
        a.from += d;
      }
      if (dEnd != 0) {
        final double lo = a.start + 0.5;
        final double hi =
            math.max(lo, math.min(_total, a.start + a.srcDur - a.from));
        a.end = (a.end + dEnd).clamp(lo, hi);
      }
    });
    _sendAudio();
  }

  void _deleteAu() {
    final _Audio? a = _selAu;
    if (a == null) return;
    setState(() {
      _music.remove(a);
      _auSel = null;
      _panel = _Panel.audio;
    });
    _sendAudio();
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
    if (p == _Panel.effects) _fxSel = null;
    if (p == _Panel.templates) _loadThumbs();
    if (p == _Panel.effects) _loadThumbs(sample: _sampleCards);
    setState(() => _panel = p);
  }

  static const int _sampleKey = -1; // the engine's built-in sample picture

  Future<void> _loadThumbs({bool sample = false}) async {
    final _Clip? c = _current ?? (_clips.isEmpty ? null : _clips.first);
    final int key = sample || c == null ? _sampleKey : c.media;
    if (_thumbs.containsKey(key)) return;
    _thumbs[key] = const {};
    try {
      final Map<dynamic, dynamic>? r = await _ch
          .invokeMapMethod<dynamic, dynamic>('thumbs', {'media': key});
      if (!mounted || r == null) return;
      setState(() => _thumbs[key] = {
            for (final MapEntry<dynamic, dynamic> e in r.entries)
              e.key as String: e.value as Uint8List,
          });
    } catch (_) {
      _thumbs.remove(key);
    }
  }

  Uint8List? _thumbFor(String id, {bool sample = false}) {
    final _Clip? c = _current ?? (_clips.isEmpty ? null : _clips.first);
    final int key = sample || c == null ? _sampleKey : c.media;
    return _thumbs[key]?[id];
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

  _Fx? get _selFx {
    for (final _Fx f in _fx) {
      if (f.uid == _fxSel) return f;
    }
    return null;
  }

  /// Card tap: replace the selected effect, or add a new bar at the playhead
  /// (3 s, like CapCut). The user then drags the bar to where it should be.
  void _pickEffect(String id) {
    if (_clips.isEmpty) return;
    final _Fx? sel = _selFx;
    if (sel != null) {
      setState(() => sel.id = id);
      _sendLook();
      return;
    }
    final double tot = _total;
    double start = _time.value.clamp(0.0, tot);
    double end = math.min(start + 3, tot);
    if (end - start < 0.5) {
      start = math.max(0.0, tot - 3);
      end = tot;
    }
    final _Fx f = _Fx(uid: _nextFx++, id: id, start: start, end: end);
    final int busy = _fx.where((o) => o.start < end && o.end > start).length;
    setState(() {
      _fx.add(f);
      _fxSel = f.uid;
      _panel = _Panel.fxEdit;
    });
    _status.value = busy >= flyMaxEffects
        ? 'Only $flyMaxEffects effects show at the same time - move one bar'
        : '';
    _sendLook();
  }

  void _moveFx(_Fx f, double dt) {
    final double len = f.end - f.start;
    final double ns = (f.start + dt).clamp(0.0, math.max(0.0, _total - len));
    setState(() {
      f.start = ns;
      f.end = ns + len;
    });
    _sendLook();
  }

  void _trimFx(_Fx f, {double dStart = 0, double dEnd = 0}) {
    setState(() {
      if (dStart != 0) f.start = (f.start + dStart).clamp(0.0, f.end - 0.3);
      if (dEnd != 0) {
        f.end = (f.end + dEnd)
            .clamp(f.start + 0.3, math.max(f.start + 0.3, _total));
      }
    });
    _sendLook();
  }

  void _deleteFx() {
    final _Fx? f = _selFx;
    if (f == null) return;
    setState(() {
      _fx.remove(f);
      _fxSel = null;
      _panel = _Panel.none;
    });
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
          ..speed = c.speed
          ..vol = c.vol
          ..from = c.from + local * c.speed;
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
      ..speed = c.speed
      ..vol = c.vol
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
            'made in ${secs.toStringAsFixed(1)} s'
            '${r['audio'] == null ? '' : '\n${r['audio']}'}';
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

  static const double _laneH = 24;

  /// Effect bars in lanes: a bar goes in the first lane where it fits.
  List<List<_Fx>> _fxLanes() {
    final List<List<_Fx>> lanes = [];
    final List<_Fx> sorted = [..._fx]..sort((a, b) => a.start.compareTo(b.start));
    for (final _Fx f in sorted) {
      List<_Fx>? home;
      for (final List<_Fx> l in lanes) {
        if (l.last.end <= f.start + 0.01) {
          home = l;
          break;
        }
      }
      if (home == null) {
        lanes.add([f]);
      } else {
        home.add(f);
      }
    }
    return lanes;
  }

  List<List<_Txt>> _txLanes() {
    final List<List<_Txt>> lanes = [];
    final List<_Txt> sorted = [..._texts]
      ..sort((a, b) => a.start.compareTo(b.start));
    for (final _Txt t in sorted) {
      List<_Txt>? home;
      for (final List<_Txt> l in lanes) {
        if (l.last.end <= t.start + 0.01) {
          home = l;
          break;
        }
      }
      if (home == null) {
        lanes.add([t]);
      } else {
        home.add(t);
      }
    }
    return lanes;
  }

  /// An orange text bar. Tap = select; selected: drag to move, ends to trim.
  Widget _txBar(_Txt t) {
    final bool sel = t.uid == _txSel;
    final double w = math.max(20.0, (t.end - t.start) * _pps);
    Widget handle(bool left) => GestureDetector(
          behavior: HitTestBehavior.opaque,
          onHorizontalDragUpdate: (d) => left
              ? _trimTx(t, dStart: d.delta.dx / _pps)
              : _trimTx(t, dEnd: d.delta.dx / _pps),
          child: Container(
            width: 14,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.horizontal(
                left: Radius.circular(left ? 5 : 0),
                right: Radius.circular(left ? 0 : 5),
              ),
            ),
            child: Container(width: 2, height: 10, color: Colors.black54),
          ),
        );
    return Positioned(
      left: t.start * _pps,
      top: 2,
      height: _laneH - 4,
      width: w,
      child: GestureDetector(
        onTap: () => _selectTx(sel ? null : t),
        onHorizontalDragUpdate:
            sel ? (d) => _moveTx(t, d.delta.dx / _pps) : null,
        child: Container(
          decoration: BoxDecoration(
            color: sel ? const Color(0xFFFF9F43) : const Color(0xFF9A5A1C),
            borderRadius: BorderRadius.circular(5),
            border: Border.all(
                color: sel ? Colors.white : Colors.transparent, width: 1.2),
          ),
          child: Row(
            children: [
              if (sel) handle(true),
              const SizedBox(width: 4),
              const Icon(Icons.title, size: 11, color: Colors.white),
              const SizedBox(width: 3),
              Expanded(
                child: Text(t.text.replaceAll('\n', ' '),
                    maxLines: 1,
                    overflow: TextOverflow.clip,
                    style: const TextStyle(
                        fontSize: 10, fontWeight: FontWeight.w700)),
              ),
              if (sel) handle(false),
            ],
          ),
        ),
      ),
    );
  }

  void _selectTx(_Txt? t) {
    setState(() {
      _txSel = t?.uid;
      _sel = null;
      _fxSel = null;
      _auSel = null;
      _panel = t == null ? _Panel.text : _Panel.textEdit;
    });
    if (t != null && t.bw == 0) _measure(t);
  }

  /// Text tool: add a text; the list of texts is the orange track.
  Widget _textPanel() {
    return Column(
      children: [
        _panelHeader(_texts.isEmpty ? 'Text' : 'Text · ${_texts.length}'),
        SizedBox(
          height: 70,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 6),
            children: [
              _tool(Icons.add_comment_outlined, 'Add text', _addText),
            ],
          ),
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(16, 0, 16, 4),
          child: Text(
              'Texts go on the orange track at the playhead · tap a text on the video or its bar to change it',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 10.5, color: Colors.white54)),
        ),
      ],
    );
  }

  Widget _chips(List<String> names, int cur, ValueChanged<int> pick) {
    return SizedBox(
      height: 40,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        children: [
          for (int i = 0; i < names.length; i++)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 3),
              child: ChoiceChip(
                label: Text(names[i], style: const TextStyle(fontSize: 12)),
                selected: cur == i,
                onSelected: (_) => pick(i),
                visualDensity: VisualDensity.compact,
              ),
            ),
        ],
      ),
    );
  }

  Widget _colorRow(String label, int cur, ValueChanged<int> pick) {
    return SizedBox(
      height: 44,
      child: Row(
        children: [
          const SizedBox(width: 16),
          SizedBox(width: 74, child: Text(label, style: const TextStyle(fontSize: 12))),
          Expanded(
            child: ListView(
              scrollDirection: Axis.horizontal,
              children: [
                for (final int c in _txColors)
                  GestureDetector(
                    onTap: () => pick(c),
                    child: Container(
                      width: 30,
                      height: 30,
                      margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 7),
                      decoration: BoxDecoration(
                        color: Color(c),
                        shape: BoxShape.circle,
                        border: Border.all(
                            color: cur == c ? Colors.white : Colors.white24,
                            width: cur == c ? 2.5 : 1),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// The selected text: words, font, style, colours, animation.
  Widget _textEditPanel() {
    final _Txt? t = _selTx;
    if (t == null) return _textPanel();
    const List<String> tabs = ['Edit', 'Font', 'Style', 'Color', 'Animation'];
    Widget body;
    switch (_txTab) {
      case 1:
        _loadFonts();
        final List<Uint8List> prev = _fontPrev ?? const [];
        body = SizedBox(
          height: 70,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 8),
            children: [
              for (int i = 0; i < _fontNames.length; i++)
                GestureDetector(
                  onTap: () => _restyle(t, () => t.font = i),
                  child: Container(
                    width: 92,
                    margin: const EdgeInsets.symmetric(horizontal: 3, vertical: 4),
                    padding: const EdgeInsets.all(4),
                    decoration: BoxDecoration(
                      color: Colors.white10,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                          color: t.font == i ? Colors.white : Colors.transparent,
                          width: 1.5),
                    ),
                    child: Column(
                      children: [
                        Expanded(
                          child: i < prev.length
                              ? Image.memory(prev[i], fit: BoxFit.contain)
                              : const SizedBox(),
                        ),
                        Text(_fontNames[i],
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 9.5)),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        );
      case 2:
        body = _chips(_txStyles, t.style, (i) => _restyle(t, () => t.style = i));
      case 3:
        body = Column(children: [
          _colorRow('Text', t.color, (c) => _restyle(t, () => t.color = c)),
          _colorRow(t.style == 2 ? 'Box' : 'Outline / 2nd', t.color2,
              (c) => _restyle(t, () => t.color2 = c)),
        ]);
      case 4:
        body = Column(children: [
          const Padding(
              padding: EdgeInsets.only(left: 16, top: 2),
              child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text('In', style: TextStyle(fontSize: 11, color: Colors.white54)))),
          _chips(_txIn, t.animIn, (i) {
            setState(() => t.animIn = i);
            _sendTexts();
          }),
          const Padding(
              padding: EdgeInsets.only(left: 16),
              child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text('While on screen',
                      style: TextStyle(fontSize: 11, color: Colors.white54)))),
          _chips(_txLoop, t.animLoop, (i) {
            setState(() => t.animLoop = i);
            _sendTexts();
          }),
          const Padding(
              padding: EdgeInsets.only(left: 16),
              child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text('Out', style: TextStyle(fontSize: 11, color: Colors.white54)))),
          _chips(_txOut, t.animOut, (i) {
            setState(() => t.animOut = i);
            _sendTexts();
          }),
        ]);
      default:
        body = SizedBox(
          height: 70,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 6),
            children: [
              _tool(Icons.edit, 'Edit text', () => _editText(t)),
              _tool(Icons.copy_outlined, 'Duplicate', _duplicateTx),
              _tool(Icons.straighten, 'Whole video', () {
                setState(() {
                  t.start = 0;
                  t.end = _total;
                });
                _sendTexts();
              }),
              _tool(Icons.center_focus_strong, 'Center', () {
                setState(() {
                  t.x = 0.5;
                  t.y = 0.5;
                  t.rot = 0;
                });
                _sendTexts();
              }),
              _tool(Icons.delete_outline, 'Delete', _deleteTx),
            ],
          ),
        );
    }
    return Column(
      children: [
        SizedBox(
          height: 34,
          child: Row(
            children: [
              const SizedBox(width: 16),
              Expanded(
                child: Text(
                    '${t.text.replaceAll('\n', ' ')} · ${_fmt(t.start)} - ${_fmt(t.end)}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 13.5, fontWeight: FontWeight.w800)),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                onPressed: () => _selectTx(null),
                icon: const Icon(Icons.check),
              ),
            ],
          ),
        ),
        _catRow(tabs, tabs[_txTab], (v) => setState(() => _txTab = tabs.indexOf(v))),
        body,
        const SizedBox(height: 4),
      ],
    );
  }

  /// Texts on the video: tap one to pick it; the picked one has a box -
  /// drag to move, pinch to resize, twist with two fingers to turn,
  /// double-tap to change the words.
  Widget _textOverlay() {
    return LayoutBuilder(builder: (context, b) {
      final double fw = b.maxWidth;
      final double fh = b.maxHeight;
      final _Txt? sel = _selTx;
      Rect boxOf(_Txt t) {
        final double bh = t.bh * t.scale / 1280 * fh;
        final double bw = t.bw * t.scale / 1280 * fh;
        return Rect.fromCenter(center: Offset(t.x * fw, t.y * fh), width: bw, height: bh);
      }

      return GestureDetector(
        behavior: HitTestBehavior.translucent,
        onTapUp: (d) {
          final double now = _time.value;
          _Txt? hit;
          for (final _Txt t in _texts.reversed) {
            if (now >= t.start && now < t.end && t.bw > 0 &&
                boxOf(t).inflate(8).contains(d.localPosition)) {
              hit = t;
              break;
            }
          }
          if (hit != null) {
            _selectTx(hit);
          } else if (_panel == _Panel.textEdit) {
            _selectTx(null);
          }
        },
        onDoubleTap: sel == null ? null : () => _editText(sel),
        onScaleStart: sel == null
            ? null
            : (_) {
                _s0 = sel.scale;
                _rot0 = sel.rot;
              },
        onScaleUpdate: sel == null
            ? null
            : (d) {
                setState(() {
                  sel.x = (sel.x + d.focalPointDelta.dx / fw).clamp(0.0, 1.0);
                  sel.y = (sel.y + d.focalPointDelta.dy / fh).clamp(0.0, 1.0);
                  if (d.pointerCount > 1) {
                    sel.scale = (_s0 * d.scale).clamp(0.15, 6.0);
                    sel.rot = _rot0 + d.rotation * 180 / math.pi;
                  }
                });
                _sendTexts();
              },
        child: Stack(
          children: [
            if (sel != null && sel.bw > 0)
              Positioned.fromRect(
                rect: boxOf(sel),
                child: IgnorePointer(
                  child: Transform.rotate(
                    angle: sel.rot * math.pi / 180,
                    child: Container(
                      decoration: BoxDecoration(
                        border: Border.all(color: Colors.white, width: 1.4),
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      );
    });
  }

  List<List<_Audio>> _auLanes() {
    final List<List<_Audio>> lanes = [];
    final List<_Audio> sorted = [..._music]
      ..sort((a, b) => a.start.compareTo(b.start));
    for (final _Audio a in sorted) {
      List<_Audio>? home;
      for (final List<_Audio> l in lanes) {
        if (l.last.end <= a.start + 0.01) {
          home = l;
          break;
        }
      }
      if (home == null) {
        lanes.add([a]);
      } else {
        home.add(a);
      }
    }
    return lanes;
  }

  /// A green music bar. Tap = select; selected: drag to move, ends to trim.
  Widget _auBar(_Audio a) {
    final bool sel = a.uid == _auSel;
    final double w = math.max(20.0, (a.end - a.start) * _pps);
    Widget handle(bool left) => GestureDetector(
          behavior: HitTestBehavior.opaque,
          onHorizontalDragUpdate: (d) => left
              ? _trimAu(a, dStart: d.delta.dx / _pps)
              : _trimAu(a, dEnd: d.delta.dx / _pps),
          child: Container(
            width: 14,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.horizontal(
                left: Radius.circular(left ? 5 : 0),
                right: Radius.circular(left ? 0 : 5),
              ),
            ),
            child: Container(width: 2, height: 10, color: Colors.black54),
          ),
        );
    return Positioned(
      left: a.start * _pps,
      top: 2,
      height: _laneH - 4,
      width: w,
      child: GestureDetector(
        onTap: () => setState(() {
          _auSel = sel ? null : a.uid;
          _sel = null;
          _fxSel = null;
          _txSel = null;
          _panel = sel ? _Panel.audio : _Panel.audioEdit;
        }),
        onHorizontalDragUpdate:
            sel ? (d) => _moveAu(a, d.delta.dx / _pps) : null,
        child: Container(
          decoration: BoxDecoration(
            color: sel ? const Color(0xFF1DB954) : const Color(0xFF17703A),
            borderRadius: BorderRadius.circular(5),
            border: Border.all(
                color: sel ? Colors.white : Colors.transparent, width: 1.2),
          ),
          child: Row(
            children: [
              if (sel) handle(true),
              const SizedBox(width: 4),
              const Icon(Icons.music_note, size: 11, color: Colors.white),
              const SizedBox(width: 3),
              Expanded(
                child: Text(a.name,
                    maxLines: 1,
                    overflow: TextOverflow.clip,
                    style: const TextStyle(
                        fontSize: 10, fontWeight: FontWeight.w700)),
              ),
              if (sel) handle(false),
            ],
          ),
        ),
      ),
    );
  }

  Widget _labeledSlider(String label, String value, double v, double min,
      double max, ValueChanged<double> onChanged) {
    return SizedBox(
      height: 36,
      child: Row(
        children: [
          const SizedBox(width: 16),
          SizedBox(
              width: 64, child: Text(label, style: const TextStyle(fontSize: 12))),
          Expanded(
            child: Slider(
                value: v.clamp(min, max), min: min, max: max, onChanged: onChanged),
          ),
          SizedBox(
              width: 46,
              child: Text(value,
                  style: const TextStyle(
                      fontSize: 11.5, fontWeight: FontWeight.w700))),
        ],
      ),
    );
  }

  /// Audio: add a song, turn the clips' own sound on / off.
  Widget _audioPanel() {
    return Column(
      children: [
        _panelHeader(_music.isEmpty
            ? 'Audio'
            : 'Audio · ${_music.length} song${_music.length == 1 ? '' : 's'}'),
        SizedBox(
          height: 70,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 6),
            children: [
              _tool(Icons.library_music_outlined,
                  _picking ? 'Opening...' : 'Add music', _addMusic),
              _tool(_origOn ? Icons.volume_up : Icons.volume_off,
                  _origOn ? 'Original: On' : 'Original: Off', () {
                setState(() => _origOn = !_origOn);
                _sendAudio();
              }, active: !_origOn),
            ],
          ),
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(16, 0, 16, 4),
          child: Text(
              'Songs from your phone go on the green track at the playhead · tap a green bar to change it',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 10.5, color: Colors.white54)),
        ),
      ],
    );
  }

  /// The selected song: volume, fades, length, delete.
  Widget _audioEditPanel() {
    final _Audio? a = _selAu;
    if (a == null) return _audioPanel();
    return Column(
      children: [
        SizedBox(
          height: 34,
          child: Row(
            children: [
              const SizedBox(width: 16),
              Expanded(
                child: Text('${a.name} · ${_fmt(a.start)} - ${_fmt(a.end)}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 13.5, fontWeight: FontWeight.w800)),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                onPressed: () => setState(() {
                  _auSel = null;
                  _panel = _Panel.audio;
                }),
                icon: const Icon(Icons.check),
              ),
            ],
          ),
        ),
        _labeledSlider('Volume', '${(a.vol * 100).round()}%', a.vol, 0, 2, (v) {
          setState(() => a.vol = v);
          _sendAudio();
        }),
        _labeledSlider('Fade in', '${a.fadeIn.toStringAsFixed(1)}s', a.fadeIn, 0, 5,
            (v) {
          setState(() => a.fadeIn = v);
          _sendAudio();
        }),
        _labeledSlider(
            'Fade out', '${a.fadeOut.toStringAsFixed(1)}s', a.fadeOut, 0, 5, (v) {
          setState(() => a.fadeOut = v);
          _sendAudio();
        }),
        SizedBox(
          height: 70,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 6),
            children: [
              _tool(Icons.straighten, 'Whole video', () {
                setState(() {
                  a.start = 0;
                  a.end = math.min(_total, a.srcDur - a.from);
                });
                _sendAudio();
              }),
              _tool(Icons.delete_outline, 'Delete', _deleteAu),
            ],
          ),
        ),
      ],
    );
  }

  /// The selected video clip's own sound.
  Widget _volumePanel() {
    final _Clip? c = _current;
    return Column(
      children: [
        _panelHeader('Clip sound'),
        if (c != null)
          _labeledSlider('Volume', '${(c.vol * 100).round()}%', c.vol, 0, 2, (v) {
            setState(() => c.vol = v);
            _sendTimeline();
          }),
        if (c != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: TextButton.icon(
              onPressed: () {
                setState(() => c.vol = c.vol == 0 ? 1 : 0);
                _sendTimeline();
              },
              icon: Icon(c.vol == 0 ? Icons.volume_up : Icons.volume_off),
              label: Text(c.vol == 0 ? 'Turn sound on' : 'Mute this clip'),
            ),
          ),
      ],
    );
  }

  Widget _timeline() {
    return LayoutBuilder(builder: (context, box) {
      final double half = box.maxWidth / 2;
      final List<List<_Fx>> lanes = _fxLanes();
      final List<List<_Audio>> auLanes = _auLanes();
      final List<List<_Txt>> txLanes = _txLanes();
      final double stripW = _clips.fold(0.0, (s, c) => s + _widthOf(c));
      final double h = 66 +
          (lanes.isEmpty ? 0 : lanes.length * _laneH + 4) +
          (auLanes.isEmpty ? 0 : auLanes.length * _laneH + 4) +
          (txLanes.isEmpty ? 0 : txLanes.length * _laneH + 4);
      return SizedBox(
        height: h,
        child: Stack(
          children: [
            NotificationListener<ScrollNotification>(
              onNotification: _onScroll,
              child: SingleChildScrollView(
                controller: _sc,
                scrollDirection: Axis.horizontal,
                padding: EdgeInsets.fromLTRB(half, 5, half - 60, 5),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      height: 56,
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
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
                    if (lanes.isNotEmpty) const SizedBox(height: 4),
                    for (final List<_Fx> lane in lanes)
                      SizedBox(
                        height: _laneH,
                        width: math.max(stripW, 1.0),
                        child: Stack(
                          clipBehavior: Clip.none,
                          children: [for (final _Fx f in lane) _fxBar(f)],
                        ),
                      ),
                    if (txLanes.isNotEmpty) const SizedBox(height: 4),
                    for (final List<_Txt> lane in txLanes)
                      SizedBox(
                        height: _laneH,
                        width: math.max(stripW, 1.0),
                        child: Stack(
                          clipBehavior: Clip.none,
                          children: [for (final _Txt t in lane) _txBar(t)],
                        ),
                      ),
                    if (auLanes.isNotEmpty) const SizedBox(height: 4),
                    for (final List<_Audio> lane in auLanes)
                      SizedBox(
                        height: _laneH,
                        width: math.max(stripW, 1.0),
                        child: Stack(
                          clipBehavior: Clip.none,
                          children: [for (final _Audio a in lane) _auBar(a)],
                        ),
                      ),
                  ],
                ),
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

  /// One effect bar. Tap = select; when selected, drag it to move it in
  /// time and drag its ends to change where it starts / stops.
  Widget _fxBar(_Fx f) {
    final bool sel = f.uid == _fxSel;
    final double w = math.max(20.0, (f.end - f.start) * _pps);
    Widget handle(bool left) => GestureDetector(
          behavior: HitTestBehavior.opaque,
          onHorizontalDragUpdate: (d) => left
              ? _trimFx(f, dStart: d.delta.dx / _pps)
              : _trimFx(f, dEnd: d.delta.dx / _pps),
          child: Container(
            width: 14,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.horizontal(
                left: Radius.circular(left ? 5 : 0),
                right: Radius.circular(left ? 0 : 5),
              ),
            ),
            child: Container(width: 2, height: 10, color: Colors.black54),
          ),
        );
    return Positioned(
      left: f.start * _pps,
      top: 2,
      height: _laneH - 4,
      width: w,
      child: GestureDetector(
        onTap: () => setState(() {
          _fxSel = sel ? null : f.uid;
          _sel = null;
          _auSel = null;
          _txSel = null;
          _panel = sel ? _Panel.none : _Panel.fxEdit;
        }),
        onHorizontalDragUpdate:
            sel ? (d) => _moveFx(f, d.delta.dx / _pps) : null,
        child: Container(
          decoration: BoxDecoration(
            color: sel ? const Color(0xFF9C4DFF) : const Color(0xFF5B3A8C),
            borderRadius: BorderRadius.circular(5),
            border: Border.all(
                color: sel ? Colors.white : Colors.transparent, width: 1.2),
          ),
          child: Row(
            children: [
              if (sel) handle(true),
              const SizedBox(width: 4),
              Icon(f.placed ? Icons.my_location : Icons.auto_awesome,
                  size: 11, color: Colors.white),
              const SizedBox(width: 3),
              Expanded(
                child: Text(flyLabel(f.id),
                    maxLines: 1,
                    overflow: TextOverflow.clip,
                    style: const TextStyle(
                        fontSize: 10, fontWeight: FontWeight.w700)),
              ),
              if (sel) handle(false),
            ],
          ),
        ),
      ),
    );
  }

  double _widthOf(_Clip c) => (c.dur * _pps).clamp(24.0, 4000.0) + 2;

  // Long-press a clip, then drag it left / right to change its place.
  double _dragBase = 0;

  void _dragMove(_Clip c, double dx) {
    final int i = _clips.indexOf(c);
    if (i < 0) return;
    final double rel = dx - _dragBase;
    int j = i;
    if (rel > 0 && i < _clips.length - 1) {
      final double nw = _widthOf(_clips[i + 1]);
      if (rel > nw / 2) {
        j = i + 1;
        _dragBase += nw;
      }
    } else if (rel < 0 && i > 0) {
      final double pw = _widthOf(_clips[i - 1]);
      if (rel < -pw / 2) {
        j = i - 1;
        _dragBase -= pw;
      }
    }
    if (j != i) {
      setState(() {
        _clips.removeAt(i);
        _clips.insert(j, c);
      });
    }
  }

  Widget _clipBox(_Clip c) {
    final bool sel = _sel == c.id;
    return GestureDetector(
      onTap: () {
        setState(() {
          _sel = sel ? null : c.id;
          _fxSel = null;
          _auSel = null;
          _txSel = null;
          _panel = sel ? _Panel.none : _Panel.edit;
        });
        if (!sel) _loadThumbs();
      },
      onLongPressStart: (_) {
        _dragBase = 0;
        setState(() => _sel = c.id);
      },
      onLongPressMoveUpdate: (d) => _dragMove(c, d.offsetFromOrigin.dx),
      onLongPressEnd: (_) => _sendTimeline(),
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
          _tool(Icons.music_note, 'Audio', () => _open(_Panel.audio),
              active: _music.isNotEmpty || !_origOn),
          _tool(Icons.title, 'Text', () => _open(_Panel.text),
              active: _texts.isNotEmpty),
          _tool(Icons.auto_awesome_motion, 'Templates',
              () => _open(_Panel.templates)),
          _tool(Icons.auto_awesome, 'Effects', () => _open(_Panel.effects),
              active: _fx.isNotEmpty),
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

  Widget _card(FlyItem t, bool sel, VoidCallback onTap,
      {int badge = 0, bool sample = false}) {
    final Uint8List? img = _thumbFor(t.id, sample: sample);
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
              if (c != null && c.video)
                _tool(c.vol == 0 ? Icons.volume_off : Icons.volume_up, 'Volume',
                    () => setState(() => _panel = _Panel.volume)),
              if (c != null && c.video)
                _tool(Icons.speed, 'Speed',
                    () => setState(() => _panel = _Panel.speed)),
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

  void _setSpeed(_Clip c, double v) {
    // Keep the same footage; only the timeline length changes.
    final double span = c.dur * c.speed;
    setState(() {
      c.speed = v;
      c.dur = (span / v).clamp(0.5, c.maxDur).toDouble();
    });
    _sendTimeline();
  }

  Widget _speedPanel() {
    final _Clip? c = _current;
    const List<double> steps = [0.5, 1, 1.5, 2, 3];
    return Column(
      children: [
        _panelHeader('Speed'),
        if (c != null)
          SizedBox(
            height: 70,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 10),
              children: [
                for (final double v in steps)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: ChoiceChip(
                      label: Text('${v % 1 == 0 ? v.toInt() : v}x'),
                      selected: c.speed == v,
                      onSelected: (_) => _setSpeed(c, v),
                    ),
                  ),
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
    final _Fx? sel = _selFx;
    return Column(
      children: [
        SizedBox(
          height: 34,
          child: Row(
            children: [
              const SizedBox(width: 16),
              Expanded(
                child: Text(
                    sel != null
                        ? 'Replace · ${flyLabel(sel.id)}'
                        : 'Effects · tap one to add at the playhead',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 13.5, fontWeight: FontWeight.w800)),
              ),
              _cardSource(),
              IconButton(
                visualDensity: VisualDensity.compact,
                onPressed: () => setState(() =>
                    _panel = sel != null ? _Panel.fxEdit : _Panel.none),
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
              for (final FlyItem t in flyEffectsIn(_fxCat))
                _card(t, sel != null && sel.id == t.id,
                    () => _pickEffect(t.id),
                    badge: _fx.where((f) => f.id == t.id).length,
                    sample: _sampleCards),
            ],
          ),
        ),
      ],
    );
  }

  /// "Sample | My clip": what the effect cards are drawn on.
  Widget _cardSource() {
    Widget chip(String label, bool sample) {
      final bool on = _sampleCards == sample;
      return GestureDetector(
        onTap: () {
          setState(() => _sampleCards = sample);
          _loadThumbs(sample: sample);
        },
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(12),
            gradient: on ? _brand : null,
          ),
          child: Text(label,
              style: TextStyle(
                  fontSize: 11,
                  fontWeight: on ? FontWeight.w800 : FontWeight.w500,
                  color: on ? Colors.white : Colors.white60)),
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(2),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(14),
        color: Colors.white10,
      ),
      child: Row(children: [chip('Sample', true), chip('My clip', false)]),
    );
  }

  /// The selected effect bar: replace, place on the video, length, delete.
  Widget _fxEditPanel() {
    final _Fx? f = _selFx;
    if (f == null) return _mainToolbar();
    return Column(
      children: [
        SizedBox(
          height: 34,
          child: Row(
            children: [
              const SizedBox(width: 16),
              Expanded(
                child: Text(
                    '${flyLabel(f.id)} · ${_fmt(f.start)} - ${_fmt(f.end)}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 13.5, fontWeight: FontWeight.w800)),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                onPressed: () => setState(() {
                  _fxSel = null;
                  _panel = _Panel.none;
                }),
                icon: const Icon(Icons.check),
              ),
            ],
          ),
        ),
        SizedBox(
          height: 70,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 6),
            children: [
              _tool(Icons.swap_horiz, 'Replace', () {
                setState(() => _panel = _Panel.effects);
                _loadThumbs(sample: _sampleCards);
              }),
              _tool(Icons.open_with, f.placed ? 'Full screen' : 'Place on video',
                  () {
                setState(() => f.placed = !f.placed);
                _status.value = f.placed
                    ? 'Drag the circle on the video · pinch to resize'
                    : '';
                _sendLook();
              }, active: f.placed),
              _tool(Icons.straighten, 'Whole video', () {
                setState(() {
                  f.start = 0;
                  f.end = _total;
                });
                _sendLook();
              }),
              _tool(Icons.delete_outline, 'Delete', _deleteFx),
            ],
          ),
        ),
        if (f.placed)
          SizedBox(
            height: 36,
            child: Row(
              children: [
                const SizedBox(width: 16),
                const SizedBox(
                    width: 50,
                    child: Text('Size', style: TextStyle(fontSize: 12))),
                Expanded(
                  child: Slider(
                    value: f.r.clamp(0.08, 0.9),
                    min: 0.08,
                    max: 0.9,
                    onChanged: (v) {
                      setState(() => f.r = v);
                      _sendLook();
                    },
                  ),
                ),
              ],
            ),
          ),
        const Padding(
          padding: EdgeInsets.fromLTRB(16, 0, 16, 4),
          child: Text(
              'Drag the bar on the timeline to move it · drag its ends to make it longer or shorter',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 10.5, color: Colors.white54)),
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
      _Panel.speed => _speedPanel(),
      _Panel.fxEdit => _fxEditPanel(),
      _Panel.volume => _volumePanel(),
      _Panel.audio => _audioPanel(),
      _Panel.audioEdit => _audioEditPanel(),
      _Panel.text => _textPanel(),
      _Panel.textEdit => _textEditPanel(),
      _Panel.templates => _templatesPanel(),
      _Panel.effects => _effectsPanel(),
      _Panel.filters => _filtersPanel(),
      _Panel.adjust => _adjustPanel(),
      _Panel.ratio => _ratioPanel(),
      _Panel.background => _backgroundPanel(),
    };
  }

  double _r0 = 0.3;

  /// The circle on the video for a placed effect: drag to move, pinch to
  /// resize, tap to jump there.
  Widget _placer(_Fx f) {
    return LayoutBuilder(builder: (context, b) {
      final double cx = f.x * b.maxWidth;
      final double cy = f.y * b.maxHeight;
      final double rad = f.r * b.maxHeight;
      return GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: (d) {
          setState(() {
            f.x = (d.localPosition.dx / b.maxWidth).clamp(0.0, 1.0);
            f.y = (d.localPosition.dy / b.maxHeight).clamp(0.0, 1.0);
          });
          _sendLook();
        },
        onScaleStart: (_) => _r0 = f.r,
        onScaleUpdate: (d) {
          setState(() {
            f.x = (f.x + d.focalPointDelta.dx / b.maxWidth).clamp(0.0, 1.0);
            f.y = (f.y + d.focalPointDelta.dy / b.maxHeight).clamp(0.0, 1.0);
            if (d.pointerCount > 1) f.r = (_r0 * d.scale).clamp(0.08, 0.9);
          });
          _sendLook();
        },
        child: Stack(
          children: [
            Positioned(
              left: cx - rad,
              top: cy - rad,
              width: rad * 2,
              height: rad * 2,
              child: IgnorePointer(
                child: Container(
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(color: Colors.white70, width: 1.5),
                  ),
                  alignment: Alignment.center,
                  child: const Icon(Icons.open_with,
                      size: 18, color: Colors.white70),
                ),
              ),
            ),
          ],
        ),
      );
    });
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
                          child: Stack(
                            fit: StackFit.expand,
                            children: [
                              Texture(textureId: tex),
                              if (_panel == _Panel.fxEdit &&
                                  (_selFx?.placed ?? false))
                                _placer(_selFx!),
                              if (_texts.isNotEmpty &&
                                  (_panel == _Panel.none ||
                                      _panel == _Panel.text ||
                                      _panel == _Panel.textEdit))
                                _textOverlay(),
                            ],
                          ),
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
