import 'package:material_ui/material_ui.dart';

import '../../core/l10n.dart';
import '../../core/punch.dart';
import '../../core/session.dart';
import '../../widgets/common.dart';

/// Today's posts across the supervisor's sites: who is on duty, gaps, and fixes on the spot.
class TodayTab extends StatefulWidget {
  final Session session;
  const TodayTab({super.key, required this.session});
  @override
  State<TodayTab> createState() => _TodayTabState();
}

class _TodayTabState extends State<TodayTab> {
  DateTime _day = DateTime.now();
  Session get s => widget.session;

  Future<List<Map<String, dynamic>>> _load() async {
    final d = ymd(_day);
    final rows = (await s.api.get('/v1/roster?from=$d&to=$d') as List).map((e) => Map<String, dynamic>.from(e)).toList();
    // uncovered first
    rows.sort((a, b) => _rank(a).compareTo(_rank(b)));
    final dash = Map<String, dynamic>.from(await s.api.get('/v1/reports/dashboard?date=$d'));
    return [{'_dash': dash}, ...rows];
  }

  int _rank(Map<String, dynamic> v) => switch ('${v['shift']['status']}') { 'OPEN' => 0, 'PARTIAL' => 1, _ => 2 };

  /// Pick a guard from the ranked replacement list.
  Future<Map<String, dynamic>?> _pickGuard(String shiftId) async {
    final cands = (await s.api.get('/v1/shifts/$shiftId/eligible-employees') as List).map((e) => Map<String, dynamic>.from(e)).toList();
    if (!mounted) return null;
    return showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      builder: (c) => cands.isEmpty
          ? const Padding(padding: EdgeInsets.all(32), child: Text('No available qualified guard in this branch'))
          : ListView(children: [
              const ListTile(title: Text('Available guards (best first)', style: TextStyle(fontWeight: FontWeight.bold))),
              for (final g in cands)
                ListTile(
                  leading: CircleAvatar(child: Text('${g['code']}'.substring(0, 1))),
                  title: Text('${g['name']} (${g['code']})'),
                  subtitle: Text('${'${g['source']}'.replaceAll('_', ' ').toLowerCase()}${(g['notes'] as List).isNotEmpty ? ' · ${(g['notes'] as List).join(', ')}' : ''}'),
                  trailing: g['phone'] == null ? null : Text('${g['phone']}', style: const TextStyle(fontSize: 12)),
                  onTap: () => Navigator.pop(c, g),
                ),
            ]),
    );
  }

  Future<void> _fill(Map<String, dynamic> shift, VoidCallback reload) async {
    final g = await _pickGuard('${shift['id']}');
    if (g == null || !mounted) return;
    final reason = await askText(context, 'Reason', hint: 'e.g. covering vacancy');
    if (reason == null) return;
    await s.api.post('/v1/assignments', {'shiftId': shift['id'], 'employeeId': g['employeeId'], 'role': 'RELIEF', 'reason': reason}, true);
    if (mounted) toast(context, '${g['name']} assigned');
    reload();
  }

  Future<void> _replace(Map<String, dynamic> shift, Map<String, dynamic> a, VoidCallback reload) async {
    final g = await _pickGuard('${shift['id']}');
    if (g == null || !mounted) return;
    final reason = await askText(context, 'Why is ${a['employeeName']} being replaced?', hint: 'e.g. absent, sick');
    if (reason == null) return;
    await s.api.post('/v1/assignments/${a['id']}/replace', {'employeeId': g['employeeId'], 'reason': reason}, true);
    if (mounted) toast(context, '${g['name']} replaces ${a['employeeName']}');
    reload();
  }

  Future<void> _mark(Map<String, dynamic> a, String kind, VoidCallback reload) async {
    final r = await Punch.send(s, kind: kind, assignmentId: '${a['id']}', employeeId: '${a['employeeId']}');
    if (mounted) toast(context, '${a['employeeName']}: ${kind == 'CHECK_IN' ? 'in' : 'out'} ${r.status.toLowerCase()}${r.message != null ? ' - ${r.message}' : ''}');
    reload();
  }

  @override
  Widget build(BuildContext context) {
    final write = s.can('ROSTER_WRITE');
    final mark = s.can('ATTENDANCE_MARK');
    return AsyncList<Map<String, dynamic>>(
      key: ValueKey(_day),
      load: _load,
      empty: tr('no_shifts'),
      header: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        IconButton(icon: const Icon(Icons.chevron_left), onPressed: () => setState(() => _day = _day.subtract(const Duration(days: 1)))),
        Text(fmtDate(_day.toIso8601String()), style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
        IconButton(icon: const Icon(Icons.chevron_right), onPressed: () => setState(() => _day = _day.add(const Duration(days: 1)))),
      ]),
      item: (context, v, reload) {
        if (v.containsKey('_dash')) return _DashCard(Map<String, dynamic>.from(v['_dash']));
        final shift = Map<String, dynamic>.from(v['shift']);
        final as = ((v['assignments'] as List?) ?? []).map((e) => Map<String, dynamic>.from(e)).toList();
        final status = '${shift['status']}';
        return Card(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: BorderSide(color: statusColor(status).withValues(alpha: 0.5))),
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(child: Text('${v['siteName']} · ${v['postName']}', style: const TextStyle(fontWeight: FontWeight.bold))),
                StatusChip(status),
              ]),
              Text('${fmtTime(shift['startAt'])} – ${fmtTime(shift['endAt'])}${shift['kind'] == 'EXTRA' ? ' · EXTRA' : ''}',
                  style: const TextStyle(color: Colors.grey, fontSize: 13)),
              const Divider(),
              for (final a in as)
                Row(children: [
                  Expanded(
                    child: Text('${a['employeeName']} (${a['employeeCode']})${a['role'] != 'REGULAR' ? ' · ${'${a['role']}'.toLowerCase()}' : ''}',
                        style: TextStyle(decoration: a['status'] == 'NO_SHOW' || a['status'] == 'CANCELLED' ? TextDecoration.lineThrough : null)),
                  ),
                  StatusChip('${a['status']}'),
                  PopupMenuButton<String>(
                    onSelected: (k) async {
                      try {
                        if (k == 'replace') await _replace(shift, a, reload);
                        if (k == 'in') await _mark(a, 'CHECK_IN', reload);
                        if (k == 'out') await _mark(a, 'CHECK_OUT', reload);
                      } catch (e) {
                        if (context.mounted) toast(context, errorText(e), error: true);
                      }
                    },
                    itemBuilder: (_) => [
                      if (write && (a['status'] == 'PLANNED' || a['status'] == 'CONFIRMED' || a['status'] == 'NO_SHOW'))
                        const PopupMenuItem(value: 'replace', child: Text('Replace guard')),
                      if (mark && (a['status'] == 'PLANNED' || a['status'] == 'NO_SHOW')) const PopupMenuItem(value: 'in', child: Text('Mark checked in')),
                      if (mark && a['status'] == 'CONFIRMED') const PopupMenuItem(value: 'out', child: Text('Mark checked out')),
                    ],
                  ),
                ]),
              if (write && status != 'FILLED' && status != 'CANCELLED')
                Align(
                  alignment: Alignment.centerRight,
                  child: BusyButton(icon: Icons.person_add, onPressed: () => _fill(shift, reload), child: Text(tr('find_guard'))),
                ),
            ]),
          ),
        );
      },
    );
  }
}

class _DashCard extends StatelessWidget {
  final Map<String, dynamic> d;
  const _DashCard(this.d);
  @override
  Widget build(BuildContext context) {
    final r = Map<String, dynamic>.from(d['roster'] ?? {});
    final a = Map<String, dynamic>.from(d['attendance'] ?? {});
    Widget stat(String label, Object? v, [Color? c]) => Expanded(
          child: Column(children: [
            Text('${v ?? 0}', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: c)),
            Text(label, style: const TextStyle(fontSize: 11, color: Colors.grey)),
          ]),
        );
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(children: [
          stat('Fill rate', d['fillRatePct'] == null ? '-' : '${d['fillRatePct']}%'),
          stat('Uncovered', r['uncovered'], Colors.red.shade700),
          stat('Checked in', a['checked_in']),
          stat('Late', a['late'], Colors.orange.shade800),
          stat('Absent', a['absent'], Colors.red.shade700),
        ]),
      ),
    );
  }
}

/// Attendance waiting for approval (flagged / late / adjusted), approve or reject in bulk.
class ApprovalsTab extends StatefulWidget {
  final Session session;
  const ApprovalsTab({super.key, required this.session});
  @override
  State<ApprovalsTab> createState() => _ApprovalsTabState();
}

class _ApprovalsTabState extends State<ApprovalsTab> {
  final _selected = <String>{};
  final _key = GlobalKey<AsyncListState<Map<String, dynamic>>>();

  Future<void> _decide(String decision) async {
    if (_selected.isEmpty) throw Exception('Select records first');
    final r = await widget.session.api.post('/v1/attendance/approvals', {'recordIds': _selected.toList(), 'decision': decision}, true);
    if (mounted) toast(context, '${r['updated']} ${decision.toLowerCase()}${(r['skipped'] as List).isNotEmpty ? ', ${(r['skipped'] as List).length} skipped' : ''}');
    setState(_selected.clear);
    _key.currentState?.reload();
  }

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    return Scaffold(
      bottomNavigationBar: _selected.isEmpty
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Row(children: [
                  Expanded(child: BusyButton(filled: false, onPressed: () => _decide('REJECTED'), child: Text(tr('reject')))),
                  const SizedBox(width: 12),
                  Expanded(child: BusyButton(onPressed: () => _decide('APPROVED'), child: Text('${tr('approve')} ${_selected.length}'))),
                ]),
              ),
            ),
      body: AsyncList<Map<String, dynamic>>(
        key: _key,
        empty: tr('nothing_to_approve'),
        load: () async => (await widget.session.api.get(
                    '/v1/attendance/records?from=${ymd(now.subtract(const Duration(days: 31)))}&to=${ymd(now)}&approvalStatus=PENDING') as List)
            .map((e) => Map<String, dynamic>.from(e))
            .where((r) => r['status'] != 'PENDING')
            .toList(),
        item: (context, r, _) {
          final id = '${r['id']}';
          final flags = ((r['flags'] as List?) ?? const []).join(', ');
          return Card(
            child: CheckboxListTile(
              value: _selected.contains(id),
              onChanged: (v) => setState(() => v == true ? _selected.add(id) : _selected.remove(id)),
              title: Text('${r['employee_name']} · ${r['site_name']}'),
              subtitle: Text('${fmtDate(r['duty_date'])} · In ${fmtTime(r['check_in_at'])} · Out ${fmtTime(r['check_out_at'])}'
                  '${(r['late_minutes'] ?? 0) > 0 ? '\nLate ${r['late_minutes']} min' : ''}${flags.isNotEmpty ? '\n$flags' : ''}'),
              secondary: StatusChip('${r['status']}'),
              isThreeLine: flags.isNotEmpty || (r['late_minutes'] ?? 0) > 0,
            ),
          );
        },
      ),
    );
  }
}
