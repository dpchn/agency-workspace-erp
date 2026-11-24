import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../core/l10n.dart';
import '../core/punch.dart';

/// Outcome of a punch in the simplest possible words: green ✓, amber !, blue (saved offline) or red ✗,
/// with a vibration so it is clear even without reading. Closes itself, or on a tap.
enum Outcome { ok, flagged, queued, failed }

Outcome outcomeOf(String status) => switch (status) {
      'ACCEPTED' => Outcome.ok,
      'FLAGGED' => Outcome.flagged,
      'QUEUED' => Outcome.queued,
      _ => Outcome.failed,
    };

class ResultScreen extends StatefulWidget {
  final Outcome outcome;
  final String title;
  final String? name;
  final String? detail;
  final List<(String, String, Outcome)> lines; // (name, note, outcome) for roll-calls
  const ResultScreen({super.key, required this.outcome, required this.title, this.name, this.detail, this.lines = const []});

  /// Shows the result of one punch. [kind] is CHECK_IN / CHECK_OUT; [self] true for a guard's own duty.
  static Future<void> forPunch(BuildContext context, PunchResult r, {required String kind, String? name, bool self = false}) {
    final o = outcomeOf(r.status);
    final title = switch (o) {
      Outcome.ok => tr(self ? (kind == 'CHECK_IN' ? 'result_started' : 'result_ended') : (kind == 'CHECK_IN' ? 'result_in' : 'result_out')),
      Outcome.flagged => tr('result_flagged'),
      Outcome.queued => tr('result_queued'),
      Outcome.failed => tr('result_failed'),
    };
    // server messages are shown as they come; flags are only shown when there is no message
    final detail = o == Outcome.queued ? null : r.message ?? (r.flags.isEmpty ? null : r.flags.join(', ').replaceAll('_', ' ').toLowerCase());
    return Navigator.push(context, MaterialPageRoute(builder: (_) => ResultScreen(outcome: o, title: title, name: name, detail: detail)));
  }

  @override
  State<ResultScreen> createState() => _ResultScreenState();
}

class _ResultScreenState extends State<ResultScreen> {
  Timer? _t;

  @override
  void initState() {
    super.initState();
    _buzz();
    final failed = widget.outcome == Outcome.failed || widget.lines.any((l) => l.$3 == Outcome.failed);
    _t = Timer(Duration(seconds: failed ? 7 : 4), _close);
  }

  Future<void> _buzz() async {
    if (widget.outcome == Outcome.failed) {
      // three short buzzes = something went wrong
      for (var i = 0; i < 3; i++) {
        await HapticFeedback.vibrate();
        await Future<void>.delayed(const Duration(milliseconds: 180));
      }
    } else {
      await HapticFeedback.heavyImpact();
    }
  }

  void _close() {
    _t?.cancel();
    if (mounted && Navigator.canPop(context)) Navigator.pop(context);
  }

  @override
  void dispose() {
    _t?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final (color, icon) = switch (widget.outcome) {
      Outcome.ok => (Colors.green.shade700, Icons.check_circle),
      Outcome.flagged => (Colors.orange.shade800, Icons.error),
      Outcome.queued => (Colors.blue.shade700, Icons.cloud_upload),
      Outcome.failed => (Colors.red.shade700, Icons.cancel),
    };
    const white = TextStyle(color: Colors.white);
    return Scaffold(
      backgroundColor: color,
      body: SafeArea(
        child: InkWell(
          onTap: _close,
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Icon(icon, size: 150, color: Colors.white),
                const SizedBox(height: 16),
                Text(widget.title, style: white.copyWith(fontSize: 34, fontWeight: FontWeight.bold), textAlign: TextAlign.center),
                if (widget.name != null) ...[
                  const SizedBox(height: 8),
                  Text(widget.name!, style: white.copyWith(fontSize: 24), textAlign: TextAlign.center),
                ],
                if (widget.detail != null) ...[
                  const SizedBox(height: 16),
                  Text(widget.detail!, style: white.copyWith(fontSize: 20), textAlign: TextAlign.center),
                ],
                for (final l in widget.lines)
                  Padding(
                    padding: const EdgeInsets.only(top: 10),
                    child: Row(children: [
                      Icon(l.$3 == Outcome.failed ? Icons.cancel : l.$3 == Outcome.flagged ? Icons.error : Icons.check_circle, color: Colors.white),
                      const SizedBox(width: 8),
                      Expanded(child: Text(l.$2.isEmpty ? l.$1 : '${l.$1}: ${l.$2}', style: white.copyWith(fontSize: 18))),
                    ]),
                  ),
                const SizedBox(height: 32),
                Text(tr('tap_continue'), style: const TextStyle(color: Colors.white70, fontSize: 16)),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}
