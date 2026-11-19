// Fly Creative Studio – the track names on the left of the timeline (P0.2).
//
// Each track family shows its colour AND its name (Story, Layers, Magic,
// Sound) so a beginner can read the timeline. A family with more lanes
// than StudioSizes.foldAfter shows a "+N" pill that opens / folds them.
// The gutter is opaque: bars that scroll left slide under it.

import 'package:flutter/material.dart';

import '../studio_tokens.dart';

/// One row of the gutter.
class GutterEntry {
  const GutterEntry({
    required this.top,
    required this.label,
    required this.color,
    this.hidden = 0,
    this.open = false,
    this.onFold,
  });

  /// Where the family's first lane starts (from the top of the timeline).
  final double top;
  final String label;
  final Color color;

  /// Lanes folded away (0 = none) and whether the family is opened.
  final int hidden;
  final bool open;
  final VoidCallback? onFold;
}

class TrackGutter extends StatelessWidget {
  const TrackGutter({super.key, required this.entries});

  final List<GutterEntry> entries;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: StudioSizes.gutter,
      decoration: const BoxDecoration(
        color: StudioColors.ink1,
        border: Border(right: BorderSide(color: StudioColors.line)),
      ),
      child: Stack(
        children: [
          for (final GutterEntry e in entries) ...[
            Positioned(
              left: 8,
              right: 4,
              top: e.top + (StudioSizes.lane - 16) / 2,
              height: 16,
              child: Row(
                children: [
                  Container(
                    width: 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: e.color,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                  const SizedBox(width: 5),
                  Expanded(
                    child: Text(
                      e.label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: StudioText.captionStrong,
                    ),
                  ),
                ],
              ),
            ),
            if (e.onFold != null && (e.hidden > 0 || e.open))
              Positioned(
                left: 4,
                top: e.top + StudioSizes.lane,
                height: StudioSizes.lane,
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: e.onFold,
                  child: Container(
                    constraints: const BoxConstraints(minWidth: 60),
                    alignment: Alignment.centerLeft,
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: Container(
                      padding:
                          const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: StudioColors.ink3,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(e.open ? 'Less' : '+${e.hidden}',
                          style: StudioText.captionStrong),
                    ),
                  ),
                ),
              ),
          ],
        ],
      ),
    );
  }
}
