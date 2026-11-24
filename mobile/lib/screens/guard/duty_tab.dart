import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../core/duty_logic.dart';
import '../../core/l10n.dart';
import '../../core/punch.dart';
import '../../core/session.dart';
import '../../widgets/common.dart';
import '../../widgets/result_screen.dart';

/// Guard home: one huge button that does the right thing now (START DUTY / END DUTY), then the coming duties.
/// Built for people who may not read well: colour, icon and one short line say everything.
class DutyTab extends StatefulWidget {
  final Session session;
  const DutyTab({super.key, required this.session});
  @override
  State<DutyTab> createState() => _DutyTabState();
}

class _DutyTabState extends State<DutyTab> {
  final _list = GlobalKey<AsyncListState<Map<String, dynamic>>>();
  Timer? _timer;
  List<Map<String, dynamic>> _duties = const [];

  @override
  void initState() {
    super.initState();
    widget.session.queue.addListener(_refresh);
    // retry offline punches and refresh the button every minute
    _timer = Timer.periodic(const Duration(minutes: 1), (_) {
      _sync(silent: true);
      _refresh();
    });
    _sync(silent: true);
  }

  @override
  void dispose() {
    _timer?.cancel();
    widget.session.queue.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  Future<void> _sync({bool silent = false}) async {
    if (widget.session.queue.pending == 0) return;
    try {
      final n = await widget.session.queue.sync();
      if (!silent && mounted) toast(context, tr('sent_n', {'n': n}));
      _list.currentState?.reload();
    } catch (e) {
      if (!silent && mounted) toast(context, errorText(e), error: true);
    }
  }

  Future<List<Map<String, dynamic>>> _load() async {
    final now = DateTime.now();
    final res = await widget.session.api.get('/v1/me/duties?from=${ymd(now.subtract(const Duration(days: 1)))}&to=${ymd(now.add(const Duration(days: 7)))}');
    final list = (res as List).map((e) => Map<String, dynamic>.from(e)).toList();
    if (mounted) setState(() => _duties = list);
    return list;
  }

  Future<void> _punch(String kind, Map<String, dynamic> duty) async {
    final r = await Punch.send(widget.session, kind: kind, assignmentId: duty['assignment_id'] as String?);
    if (!mounted) return;
    await ResultScreen.forPunch(context, r, kind: kind, self: true);
    _list.currentState?.reload();
  }

  @override
  Widget build(BuildContext context) {
    final q = widget.session.queue;
    final hero = heroState(_duties, DateTime.now());
    final heroId = hero.duty?['assignment_id'];
    return AsyncList<Map<String, dynamic>>(
      key: _list,
      load: _load,
      empty: tr('no_duty'),
      header: Column(children: [
        if (q.items.isNotEmpty) _QueueCard(queue: q, onSync: () => _sync()),
        // with no duties at all the list's own empty message says it
        if (_duties.isNotEmpty) _HeroCard(state: hero, onPunch: _punch),
        if (_duties.any((d) => d['assignment_id'] != heroId && !_isOver(d)))
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 20, 4, 4),
            child: Align(alignment: Alignment.centerLeft, child: Text(tr('upcoming'), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold))),
          ),
      ]),
      // the hero duty is already shown big at the top
      // finished duties belong in the Attendance tab, not under "Coming duties"
      item: (context, d, reload) => d['assignment_id'] == heroId || _isOver(d) ? const SizedBox.shrink() : _DutyRow(duty: d),
    );
  }

  static bool _isOver(Map<String, dynamic> d) {
    final end = DateTime.tryParse('${d['end_at']}'.replaceFirst(' ', 'T'));
    return end != null && end.isBefore(DateTime.now());
  }
}

class _HeroCard extends StatelessWidget {
  final HeroState state;
  final Future<void> Function(String kind, Map<String, dynamic> duty) onPunch;
  const _HeroCard({required this.state, required this.onPunch});

  @override
  Widget build(BuildContext context) {
    final d = state.duty;
    if (state.action == HeroAction.none || d == null) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(children: [
            Icon(Icons.event_busy, size: 64, color: Colors.grey.shade600),
            const SizedBox(height: 8),
            Text(tr('no_duty'), style: const TextStyle(fontSize: 20), textAlign: TextAlign.center),
          ]),
        ),
      );
    }
    if (state.action == HeroAction.next) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Row(children: [
            Icon(Icons.schedule, size: 48, color: Theme.of(context).colorScheme.primary),
            const SizedBox(width: 16),
            Expanded(
              child: Text(tr('next_duty', {'when': '${fmtDate(d['start_at'])} ${fmtTime(d['start_at'])}', 'site': d['site_name']}),
                  style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w600)),
            ),
          ]),
        ),
      );
    }
    final (color, icon, label) = switch (state.action) {
      HeroAction.start => (Colors.green.shade700, Icons.login, tr('start_duty')),
      HeroAction.end => (Colors.red.shade700, Icons.logout, tr('end_duty')),
      _ => (Colors.grey.shade500, Icons.check_circle, tr('done_today')),
    };
    final next = state.next;
    return Card(
      elevation: 3,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: color, width: 2)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Icon(Icons.location_on, color: color, size: 32),
            const SizedBox(width: 8),
            Expanded(child: Text('${d['site_name']}', style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold))),
          ]),
          const SizedBox(height: 4),
          Text('${d['post_name']} · ${fmtTime(d['start_at'])} – ${fmtTime(d['end_at'])}', style: const TextStyle(fontSize: 18)),
          if (state.action == HeroAction.end)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(tr('on_duty_since', {'time': fmtTime(d['check_in_at'])}),
                  style: TextStyle(fontSize: 18, color: Colors.green.shade800, fontWeight: FontWeight.w600)),
            ),
          if (state.action == HeroAction.start && d['geofence_policy'] == 'ENFORCE')
            Padding(padding: const EdgeInsets.only(top: 6), child: Text(tr('go_to_site'), style: TextStyle(fontSize: 16, color: Colors.grey.shade700))),
          const SizedBox(height: 16),
          SizedBox(
            height: 88,
            child: state.action == HeroAction.done
                ? FilledButton.icon(
                    onPressed: null,
                    icon: Icon(icon, size: 36),
                    label: Text(label, style: const TextStyle(fontSize: 24)),
                  )
                : _BigPunchButton(
                    color: color,
                    icon: icon,
                    label: label,
                    onPressed: () => onPunch(state.action == HeroAction.start ? 'CHECK_IN' : 'CHECK_OUT', d),
                  ),
          ),
          if (next != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(tr('next_duty', {'when': '${fmtDate(next['start_at'])} ${fmtTime(next['start_at'])}', 'site': next['site_name']}),
                  style: const TextStyle(fontSize: 16), textAlign: TextAlign.center),
            ),
        ]),
      ),
    );
  }
}

/// The one big button. Shows "please wait" while GPS / photo / upload run, so nobody taps twice.
class _BigPunchButton extends StatefulWidget {
  final Color color;
  final IconData icon;
  final String label;
  final Future<void> Function() onPressed;
  const _BigPunchButton({required this.color, required this.icon, required this.label, required this.onPressed});
  @override
  State<_BigPunchButton> createState() => _BigPunchButtonState();
}

class _BigPunchButtonState extends State<_BigPunchButton> {
  bool _busy = false;
  Future<void> _run() async {
    setState(() => _busy = true);
    try {
      await widget.onPressed();
    } catch (e) {
      if (mounted) toast(context, errorText(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => FilledButton.icon(
        style: FilledButton.styleFrom(backgroundColor: widget.color, foregroundColor: Colors.white, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14))),
        onPressed: _busy ? null : _run,
        icon: _busy ? const SizedBox(width: 32, height: 32, child: CircularProgressIndicator(strokeWidth: 3)) : Icon(widget.icon, size: 40),
        label: Text(_busy ? tr('please_wait') : widget.label, style: const TextStyle(fontSize: 26, fontWeight: FontWeight.bold)),
      );
}

/// A coming (or past) duty: day, time, site and its status, no buttons.
class _DutyRow extends StatelessWidget {
  final Map<String, dynamic> duty;
  const _DutyRow({required this.duty});
  @override
  Widget build(BuildContext context) {
    final inAt = duty['check_in_at'], outAt = duty['check_out_at'];
    final status = inAt != null && outAt == null ? 'ON_DUTY' : '${duty['attendance_status'] ?? duty['status']}';
    return Card(
      child: ListTile(
        minTileHeight: 72,
        leading: const Icon(Icons.event, size: 32),
        title: Text('${fmtDate(duty['start_at'])} · ${fmtTime(duty['start_at'])} – ${fmtTime(duty['end_at'])}',
            style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: Text('${duty['site_name']} · ${duty['post_name']}${duty['role'] != 'REGULAR' ? ' · ${duty['role']}' : ''}'),
        trailing: StatusChip(status),
      ),
    );
  }
}

class _QueueCard extends StatelessWidget {
  final dynamic queue;
  final VoidCallback onSync;
  const _QueueCard({required this.queue, required this.onSync});
  @override
  Widget build(BuildContext context) {
    final items = (queue.items as List).reversed.take(5).toList();
    return Card(
      color: Colors.amber.shade50,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.cloud_queue),
            const SizedBox(width: 6),
            Expanded(child: Text(tr('saved_waiting', {'n': queue.pending}), style: const TextStyle(fontWeight: FontWeight.w600))),
            if (queue.pending > 0)
              TextButton(onPressed: queue.syncing ? null : onSync, child: Text(queue.syncing ? tr('sending') : tr('send_now')))
            else
              TextButton(onPressed: () => queue.clearDone(), child: Text(tr('clear'))),
          ]),
          for (final p in items)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(children: [
                Text('${p.kind == 'CHECK_IN' ? tr('in') : tr('out')} ${fmtTime(p.at.toIso8601String())}  '),
                StatusChip(p.state),
                if (p.message != null) Expanded(child: Text('  ${p.message}', overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12))),
              ]),
            ),
        ]),
      ),
    );
  }
}
