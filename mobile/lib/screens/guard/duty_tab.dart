import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../core/punch.dart';
import '../../core/session.dart';
import '../../widgets/common.dart';

/// Today's and upcoming duties with big Check in / Check out buttons, plus the offline queue.
class DutyTab extends StatefulWidget {
  final Session session;
  const DutyTab({super.key, required this.session});
  @override
  State<DutyTab> createState() => _DutyTabState();
}

class _DutyTabState extends State<DutyTab> {
  final _list = GlobalKey<AsyncListState<Map<String, dynamic>>>();
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    widget.session.queue.addListener(_refresh);
    // retry offline punches every minute
    _timer = Timer.periodic(const Duration(minutes: 1), (_) => _sync(silent: true));
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
      if (!silent && mounted) toast(context, 'Synced $n punch(es)');
      _list.currentState?.reload();
    } catch (e) {
      if (!silent && mounted) toast(context, errorText(e), error: true);
    }
  }

  Future<List<Map<String, dynamic>>> _load() async {
    final now = DateTime.now();
    final res = await widget.session.api.get('/v1/me/duties?from=${ymd(now.subtract(const Duration(days: 1)))}&to=${ymd(now.add(const Duration(days: 7)))}');
    return (res as List).map((e) => Map<String, dynamic>.from(e)).toList();
  }

  Future<void> _punch(String kind, Map<String, dynamic> duty) async {
    final r = await Punch.send(widget.session, kind: kind, assignmentId: duty['assignment_id'] as String?);
    if (!mounted) return;
    final label = kind == 'CHECK_IN' ? 'Check-in' : 'Check-out';
    if (r.status == 'REJECTED') {
      await showDialog(context: context, builder: (c) => AlertDialog(title: Text('$label rejected'), content: Text(r.message ?? r.flags.join(', ')), actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('OK'))]));
    } else {
      toast(context, '$label ${r.status.toLowerCase()}${r.message != null ? ': ${r.message}' : r.flags.isNotEmpty ? ' (${r.flags.join(', ')})' : ''}');
    }
    _list.currentState?.reload();
  }

  @override
  Widget build(BuildContext context) {
    final q = widget.session.queue;
    return AsyncList<Map<String, dynamic>>(
      key: _list,
      load: _load,
      empty: 'No duties rostered for the next 7 days',
      header: q.items.isEmpty ? null : _QueueCard(queue: q, onSync: () => _sync()),
      item: (context, d, reload) => _DutyCard(duty: d, onPunch: (k) => _punch(k, d)),
    );
  }
}

class _DutyCard extends StatelessWidget {
  final Map<String, dynamic> duty;
  final Future<void> Function(String kind) onPunch;
  const _DutyCard({required this.duty, required this.onPunch});

  @override
  Widget build(BuildContext context) {
    final start = parseTs(duty['start_at']);
    final end = parseTs(duty['end_at']);
    final now = DateTime.now();
    final inAt = duty['check_in_at'];
    final outAt = duty['check_out_at'];
    final current = start != null && end != null && now.isAfter(start.subtract(const Duration(hours: 2))) && now.isBefore(end.add(const Duration(hours: 2)));
    final status = '${duty['attendance_status'] ?? duty['status']}';
    return Card(
      elevation: current ? 2 : 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: BorderSide(color: current ? Theme.of(context).colorScheme.primary : Colors.grey.shade300)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text('${duty['site_name']}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold))),
            StatusChip(status),
          ]),
          const SizedBox(height: 4),
          Text('${duty['post_name']} · ${fmtDate(duty['duty_date'])}'),
          Text('${fmtTime(duty['start_at'])} – ${fmtTime(duty['end_at'])}${duty['role'] != 'REGULAR' ? '  ·  ${duty['role']}' : ''}',
              style: const TextStyle(fontWeight: FontWeight.w500)),
          if (duty['instructions'] != null && '${duty['instructions']}'.isNotEmpty)
            Padding(padding: const EdgeInsets.only(top: 6), child: Text('${duty['instructions']}', style: const TextStyle(color: Colors.grey, fontSize: 13))),
          if (duty['geofence_policy'] == 'ENFORCE')
            const Padding(padding: EdgeInsets.only(top: 6), child: Text('You must be at the site to check in', style: TextStyle(fontSize: 12, color: Colors.grey))),
          if (current) ...[
            const SizedBox(height: 14),
            Row(children: [
              Expanded(
                child: SizedBox(
                  height: 56,
                  child: inAt == null
                      ? BusyButton(icon: Icons.login, onPressed: () => onPunch('CHECK_IN'), child: const Text('Check in', style: TextStyle(fontSize: 16)))
                      : OutlinedButton.icon(onPressed: null, icon: const Icon(Icons.check), label: Text('In ${fmtTime(inAt)}')),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: SizedBox(
                  height: 56,
                  child: outAt == null
                      ? BusyButton(filled: inAt != null, icon: Icons.logout, onPressed: () => onPunch('CHECK_OUT'), child: const Text('Check out', style: TextStyle(fontSize: 16)))
                      : OutlinedButton.icon(onPressed: null, icon: const Icon(Icons.check), label: Text('Out ${fmtTime(outAt)}')),
                ),
              ),
            ]),
          ],
        ]),
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
            const Icon(Icons.cloud_queue, size: 18),
            const SizedBox(width: 6),
            Expanded(child: Text('Saved on phone: ${queue.pending} waiting to sync', style: const TextStyle(fontWeight: FontWeight.w600))),
            if (queue.pending > 0)
              TextButton(onPressed: queue.syncing ? null : onSync, child: Text(queue.syncing ? 'Syncing…' : 'Sync now'))
            else
              TextButton(onPressed: () => queue.clearDone(), child: const Text('Clear')),
          ]),
          for (final p in items)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(children: [
                Text('${p.kind == 'CHECK_IN' ? 'In' : 'Out'} ${fmtTime(p.at.toIso8601String())}  '),
                StatusChip(p.state),
                if (p.message != null) Expanded(child: Text('  ${p.message}', overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12))),
              ]),
            ),
        ]),
      ),
    );
  }
}
