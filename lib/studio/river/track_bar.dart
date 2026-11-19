// Fly Creative Studio – one bar on a timeline track (P0.2).
//
// The SAME widget draws Layers (text, stickers, photos), Magic (effects) and
// Sound (music) bars – before P0.2 the editor had three copies of it.
//
// The widget is wider than the bar by [TrackBar.reach] on each side so the
// trim handles get a 44 dp finger area that sticks out past the bar's ends
// (a small handle is drawn inside the bar). The caller places it with
// left = barStart - TrackBar.reach.

import 'package:flutter/material.dart';

import '../studio_tokens.dart';

class TrackBar extends StatelessWidget {
  const TrackBar({
    super.key,
    required this.width,
    required this.color,
    required this.tint,
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
    required this.onMove,
    required this.onTrimStart,
    required this.onTrimEnd,
  });

  /// Half of the finger area of a trim handle, outside the bar.
  static const double reach = StudioSizes.touch / 2;

  /// Width of the bar itself (time x pixels per second).
  final double width;

  /// The track family's solid colour and its dark tint.
  final Color color;
  final Color tint;
  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  /// Drag of the whole bar / of its start / of its end, in pixels.
  final ValueChanged<double> onMove;
  final ValueChanged<double> onTrimStart;
  final ValueChanged<double> onTrimEnd;

  Widget _handle(bool left) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onHorizontalDragUpdate: (d) =>
          left ? onTrimStart(d.delta.dx) : onTrimEnd(d.delta.dx),
      child: Stack(
        children: [
          Positioned(
            left: left ? reach : reach - StudioSizes.handle,
            width: StudioSizes.handle,
            top: 0,
            bottom: 0,
            child: Container(
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: StudioColors.text1,
                borderRadius: BorderRadius.horizontal(
                  left: Radius.circular(left ? 7 : 0),
                  right: Radius.circular(left ? 0 : 7),
                ),
              ),
              child: Container(width: 2, height: 12, color: StudioColors.grip),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final Color ink = selected ? StudioColors.ink0 : StudioColors.text1;
    final double pad = selected ? StudioSizes.handle + 4 : 6;
    return SizedBox(
      width: width + reach * 2,
      height: StudioSizes.bar,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned(
            left: reach,
            right: reach,
            top: 0,
            bottom: 0,
            child: GestureDetector(
              onTap: onTap,
              onHorizontalDragUpdate:
                  selected ? (d) => onMove(d.delta.dx) : null,
              child: Container(
                decoration: BoxDecoration(
                  color: selected ? color : tint,
                  borderRadius: BorderRadius.circular(7),
                  border: Border.all(
                      color: selected ? StudioColors.text1 : color, width: 1.2),
                ),
                padding: EdgeInsets.symmetric(horizontal: pad),
                child: Row(
                  children: [
                    Icon(icon, size: 12, color: ink),
                    const SizedBox(width: 4),
                    Expanded(
                      child: Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.clip,
                        style: StudioText.captionStrong.copyWith(color: ink),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (selected)
            Positioned(
                left: 0,
                width: reach * 2,
                top: 0,
                bottom: 0,
                child: _handle(true)),
          if (selected)
            Positioned(
                right: 0,
                width: reach * 2,
                top: 0,
                bottom: 0,
                child: _handle(false)),
        ],
      ),
    );
  }
}
