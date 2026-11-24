import 'package:material_ui/material_ui.dart';

import '../../core/action_queue.dart';
import '../../core/api.dart';
import '../../core/l10n.dart';
import '../../core/photos.dart';
import '../../core/punch.dart';
import '../../core/session.dart';
import '../../widgets/common.dart';
import '../../widgets/result_screen.dart';

/// Who in a roll-call can be marked for [kind]: not yet in (for IN), in but not out (for OUT).
bool rollCallEligible(Map<String, dynamic> row, String kind) {
  if (row['assignmentStatus'] == 'CANCELLED' || row['pendingCorrection'] == true) return false;
  return kind == 'CHECK_IN' ? row['checkInAt'] == null : row['checkInAt'] != null && row['checkOutAt'] == null;
}

/// Who is selected when a roll-call sheet opens: everyone who can be marked (one tap marks the whole team).
Set<String> defaultRollCallSelection(List<Map<String, dynamic>> rows, String kind) =>
    {for (final r in rows) if (rollCallEligible(r, kind)) '${r['assignmentId']}'};

/// Supervisor roll-call: one team photo + the supervisor's location marks everyone selected at a site.
/// Meant for workers without a phone (or whose phone is dead). Each marking needs a second person's approval.
class RollCallTab extends StatefulWidget {
  final Session session;
  const RollCallTab({super.key, required this.session});
  @override
  State<RollCallTab> createState() => _RollCallTabState();
}

class _RollCallTabState extends State<RollCallTab> {
  Session get s => widget.session;
  List<(String, String)> _sites = const []; // (id, name)
  String? _siteId;
  String _kind = 'CHECK_IN';
  DateTime _day = DateTime.now();
  List<Map<String, dynamic>>? _rows;
  String? _error;
  bool _loading = false;
  final _selected = <String>{};

  @override
  void initState() {
    super.initState();
    s.actions.addListener(_refresh);
    _loadSites();
  }

  @override
  void dispose() {
    s.actions.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  Future<void> _loadSites() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final d = ymd(_day);
      final rows = (await s.api.get('/v1/roster?from=$d&to=$d') as List).map((e) => Map<String, dynamic>.from(e));
      final sites = <String, String>{};
      for (final r in rows) {
        sites['${r['shift']['siteId']}'] = '${r['siteName']}';
      }
      final list = sites.entries.map((e) => (e.key, e.value)).toList()..sort((a, b) => a.$2.compareTo(b.$2));
      setState(() {
        _sites = list;
        if (_siteId == null || !sites.containsKey(_siteId)) _siteId = list.isEmpty ? null : list.first.$1;
      });
      await _loadSheet();
    } catch (e) {
      setState(() => _error = errorText(e));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _loadSheet() async {
    if (_siteId == null) {
      setState(() => _rows = const []);
      return;
    }
    try {
      final rows = (await s.api.get('/v1/attendance/sheet?siteId=$_siteId&date=${ymd(_day)}') as List).map((e) => Map<String, dynamic>.from(e)).toList();
      setState(() {
        _rows = rows;
        _error = null;
        _selected
          ..clear()
          ..addAll(defaultRollCallSelection(rows, _kind));
      });
    } catch (e) {
      setState(() => _error = errorText(e));
    }
  }

  void _shiftDay(int days) {
    setState(() {
      _day = _day.add(Duration(days: days));
      _selected.clear();
    });
    _loadSites();
  }

  void _setKind(String k) => setState(() {
        _kind = k;
        _selected
          ..clear()
          ..addAll(defaultRollCallSelection(_rows ?? const [], k));
      });

  Future<void> _submit() async {
    if (_selected.isEmpty || _siteId == null) throw Exception(tr('rc_select_first'));
    final photo = await Photos.capture(front: false);
    if (photo == null) throw Exception(tr('photo_needed'));
    final pos = await Punch.location();
    final batchId = Api.newId();
    final body = <String, dynamic>{
      'batchId': batchId,
      'siteId': _siteId,
      'kind': _kind,
      'occurredAt': DateTime.now().toUtc().toIso8601String(),
      'assignmentIds': _selected.toList(),
      ...Punch.locationFields(pos),
      'deviceId': await s.deviceId(),
    };
    final photoBody = Photos.uploadBody(photo.base64, 'TEAM', photo.takenAt, siteId: _siteId);
    final names = {for (final r in _rows ?? const <Map<String, dynamic>>[]) '${r['assignmentId']}': '${r['employeeName']}'};
    final siteName = _sites.firstWhere((e) => e.$1 == _siteId, orElse: () => ('', '')).$2;
    final label = '${_kind == 'CHECK_IN' ? 'IN' : 'OUT'} · $siteName · ${_selected.length} people';

    Future<void> queue() async {
      await s.actions.add(QueuedAction('ROLL_CALL', {...body, 'offline': true}, photo: photoBody, label: label));
      setState(_selected.clear);
      if (mounted) await ResultScreen.forPunch(context, PunchResult('QUEUED', null, const []), kind: _kind);
    }

    try {
      body['photoId'] = await Photos.upload(s.api, photoBody);
    } on ApiException catch (e) {
      if (e.isNetwork) return queue();
      rethrow;
    }
    Map<String, dynamic> r;
    try {
      r = Map<String, dynamic>.from(await s.api.post('/v1/attendance/roll-call', body, true, batchId));
    } on ApiException catch (e) {
      if (e.isNetwork) return queue();
      rethrow;
    }
    setState(_selected.clear);
    if (!mounted) return;
    final results = ((r['results'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e)).toList();
    final marked = (r['marked'] as num?)?.toInt() ?? 0;
    final rejected = (r['rejected'] as num?)?.toInt() ?? 0;
    final lines = [
      for (final x in results)
        (
          names['${x['assignmentId']}'] ?? '${x['assignmentId']}',
          '${x['message'] ?? ((x['flags'] as List?) ?? const []).join(', ').replaceAll('_', ' ').toLowerCase()}',
          outcomeOf('${x['status']}'),
        )
    ];
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ResultScreen(
          outcome: rejected == 0 ? Outcome.ok : marked == 0 ? Outcome.failed : Outcome.flagged,
          title: tr('rc_marked', {'n': marked}),
          detail: rejected == 0 ? null : tr('rc_rejected', {'n': rejected}),
          lines: lines,
        ),
      ),
    );
    await _loadSheet();
  }

  Future<void> _sync() async {
    try {
      final n = await s.actions.sync();
      if (mounted) toast(context, tr('sent_n', {'n': n}));
      await _loadSheet();
    } catch (e) {
      if (mounted) toast(context, errorText(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final rows = _rows ?? const <Map<String, dynamic>>[];
    final eligible = rows.where((r) => rollCallEligible(r, _kind)).toList();
    final q = s.actions;
    final isIn = _kind == 'CHECK_IN';
    final all = _selected.length == eligible.length;
    final label = tr(isIn ? (all ? 'rc_mark_all_in' : 'rc_mark_in') : (all ? 'rc_mark_all_out' : 'rc_mark_out'), {'n': _selected.length});
    return Scaffold(
      bottomNavigationBar: _selected.isEmpty
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: SizedBox(
                  height: 76,
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(backgroundColor: isIn ? Colors.green.shade700 : Colors.orange.shade800),
                    onPressed: () async {
                      try {
                        await _submit();
                      } catch (e) {
                        if (context.mounted) toast(context, errorText(e).replaceFirst('Exception: ', ''), error: true);
                      }
                    },
                    icon: const Icon(Icons.groups, size: 34),
                    label: Text(label, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
                  ),
                ),
              ),
            ),
      body: RefreshIndicator(
        onRefresh: _loadSites,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 96),
          children: [
            if (q.items.isNotEmpty)
              Card(
                color: q.pending > 0 ? Colors.orange.shade50 : null,
                child: ListTile(
                  leading: Icon(q.pending > 0 ? Icons.cloud_upload_outlined : Icons.cloud_done_outlined),
                  title: Text(q.pending > 0 ? tr('rc_waiting', {'n': q.pending}) : tr('rc_all_sent')),
                  subtitle: Text(q.items.reversed.take(3).map((a) => '${a.label}: ${a.state.toLowerCase()}${a.message == null ? '' : ' (${a.message})'}').join('\n')),
                  trailing: q.pending > 0
                      ? TextButton(onPressed: q.syncing ? null : _sync, child: Text(tr('send_now')))
                      : TextButton(onPressed: q.clearDone, child: Text(tr('clear'))),
                ),
              ),
            Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              IconButton(icon: const Icon(Icons.chevron_left), onPressed: () => _shiftDay(-1)),
              Text(fmtDate(_day.toIso8601String()), style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
              IconButton(icon: const Icon(Icons.chevron_right), onPressed: () => _shiftDay(1)),
            ]),
            if (_sites.isNotEmpty)
              DropdownButtonFormField<String>(
                initialValue: _siteId,
                isExpanded: true,
                decoration: InputDecoration(labelText: tr('rc_site'), border: const OutlineInputBorder()),
                items: [for (final x in _sites) DropdownMenuItem(value: x.$1, child: Text(x.$2))],
                onChanged: (v) {
                  setState(() => _siteId = v);
                  _loadSheet();
                },
              ),
            const SizedBox(height: 8),
            SegmentedButton<String>(
              segments: [
                ButtonSegment(value: 'CHECK_IN', label: Text(tr('rc_coming')), icon: const Icon(Icons.login)),
                ButtonSegment(value: 'CHECK_OUT', label: Text(tr('rc_going')), icon: const Icon(Icons.logout)),
              ],
              selected: {_kind},
              onSelectionChanged: (v) => _setKind(v.first),
            ),
            if (_loading) const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator())),
            if (_error != null) Padding(padding: const EdgeInsets.all(16), child: Text(_error!, style: TextStyle(color: Colors.red.shade700))),
            if (!_loading && _error == null && _sites.isEmpty)
              Padding(padding: const EdgeInsets.all(32), child: Center(child: Text(tr('rc_none'), style: const TextStyle(color: Colors.grey)))),
            if (rows.isNotEmpty)
              Row(children: [
                Expanded(child: Text(tr('rc_can_mark', {'n': eligible.length, 'total': rows.length}), style: const TextStyle(color: Colors.grey))),
                TextButton(
                  onPressed: eligible.isEmpty
                      ? null
                      : () => setState(() => _selected.length == eligible.length
                          ? _selected.clear()
                          : _selected.addAll(eligible.map((r) => '${r['assignmentId']}'))),
                  child: Text(_selected.length == eligible.length && eligible.isNotEmpty ? tr('clear') : tr('rc_select_all')),
                ),
              ]),
            for (final r in rows) _row(r),
            if (rows.isNotEmpty)
              Padding(
                padding: const EdgeInsets.all(12),
                child: Text(tr('rc_note'), style: const TextStyle(color: Colors.grey, fontSize: 13)),
              ),
          ],
        ),
      ),
    );
  }

  Widget _row(Map<String, dynamic> r) {
    final id = '${r['assignmentId']}';
    final can = rollCallEligible(r, _kind);
    final noPhone = r['hasSmartphone'] == false;
    final sources = ((r['sources'] as List?) ?? const []).join(', ').toLowerCase();
    final state = r['checkOutAt'] != null
        ? tr('out_at', {'time': fmtTime(r['checkOutAt'])})
        : r['checkInAt'] != null
            ? tr('in_at', {'time': fmtTime(r['checkInAt'])})
            : tr('not_in');
    return Card(
      child: CheckboxListTile(
        value: _selected.contains(id),
        onChanged: can ? (v) => setState(() => v == true ? _selected.add(id) : _selected.remove(id)) : null,
        title: Row(children: [
          Flexible(child: Text('${r['employeeName']} (${r['employeeCode']})', overflow: TextOverflow.ellipsis)),
          if (noPhone)
            const Padding(
              padding: EdgeInsets.only(left: 6),
              child: Icon(Icons.phonelink_erase, size: 20, color: Colors.deepOrange),
            ),
        ]),
        subtitle: Text('${r['serviceType']} · ${r['postName']} · ${fmtTime(r['startAt'])}–${fmtTime(r['endAt'])}\n'
            '$state${sources.isEmpty ? '' : ' · via $sources'}${r['pendingCorrection'] == true ? ' · correction pending' : ''}'),
        isThreeLine: true,
        secondary: r['status'] == null ? null : StatusChip('${r['status']}'),
      ),
    );
  }
}
