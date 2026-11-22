import 'package:material_ui/material_ui.dart';

import '../core/session.dart';
import '../widgets/common.dart';

/// Report incidents (everyone) and work them (supervisors with INCIDENT_MANAGE).
class IncidentsTab extends StatelessWidget {
  final Session session;
  const IncidentsTab({super.key, required this.session});

  @override
  Widget build(BuildContext context) {
    final key = GlobalKey<AsyncListState<Map<String, dynamic>>>();
    final manage = session.can('INCIDENT_MANAGE');
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        backgroundColor: Colors.red.shade700,
        foregroundColor: Colors.white,
        icon: const Icon(Icons.report),
        label: const Text('Report incident'),
        onPressed: () async {
          final ok = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, builder: (_) => _ReportForm(session: session));
          if (ok == true) key.currentState?.reload();
        },
      ),
      body: AsyncList<Map<String, dynamic>>(
        key: key,
        empty: 'No incidents',
        load: () async => (await session.api.get('/v1/incidents') as List).map((e) => Map<String, dynamic>.from(e)).toList(),
        item: (context, i, reload) {
          final status = '${i['status']}';
          Future<void> act(String action, [Map<String, dynamic>? body]) async {
            await session.api.post('/v1/incidents/${i['id']}/$action', body ?? {}, true);
            reload();
          }

          return Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  StatusChip('${i['severity']}'),
                  const SizedBox(width: 6),
                  Expanded(child: Text('${i['title']}', style: const TextStyle(fontWeight: FontWeight.bold))),
                  StatusChip(status),
                ]),
                if (i['description'] != null) Padding(padding: const EdgeInsets.only(top: 6), child: Text('${i['description']}')),
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text('${fmtDate(i['occurredAt'])} ${fmtTime(i['occurredAt'])}${(i['escalationLevel'] ?? 0) > 0 ? ' · escalated x${i['escalationLevel']}' : ''}',
                      style: const TextStyle(fontSize: 12, color: Colors.grey)),
                ),
                if (manage && status != 'CLOSED')
                  Wrap(spacing: 8, children: [
                    if (status == 'OPEN') BusyButton(onPressed: () => act('acknowledge'), child: const Text('Acknowledge')),
                    if (status == 'ACKNOWLEDGED') BusyButton(filled: false, onPressed: () => act('start'), child: const Text('Start')),
                    if (status == 'ACKNOWLEDGED' || status == 'IN_PROGRESS')
                      BusyButton(
                          onPressed: () async {
                            final n = await askText(context, 'Resolution notes');
                            if (n != null) await act('resolve', {'notes': n});
                          },
                          child: const Text('Resolve')),
                    if (status == 'RESOLVED') BusyButton(onPressed: () => act('close'), child: const Text('Close')),
                  ]),
              ]),
            ),
          );
        },
      ),
    );
  }
}

class _ReportForm extends StatefulWidget {
  final Session session;
  const _ReportForm({required this.session});
  @override
  State<_ReportForm> createState() => _ReportFormState();
}

class _ReportFormState extends State<_ReportForm> {
  final _title = TextEditingController();
  final _desc = TextEditingController();
  String _severity = 'MEDIUM';
  String? _siteId;
  late final Future<List<Map<String, dynamic>>> _sites = widget.session.api
      .get('/v1/sites?size=200')
      .then((r) => ((r['items'] as List)).map((e) => Map<String, dynamic>.from(e)).toList());

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 16, 16, MediaQuery.of(context).viewInsets.bottom + 16),
      child: FutureBuilder<List<Map<String, dynamic>>>(
        future: _sites,
        builder: (context, snap) {
          final sites = snap.data ?? const [];
          if (_siteId == null && sites.length == 1) _siteId = '${sites.first['id']}';
          return Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const Text('Report incident', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              initialValue: _siteId,
              decoration: const InputDecoration(labelText: 'Site', border: OutlineInputBorder()),
              items: sites.map((s) => DropdownMenuItem(value: '${s['id']}', child: Text('${s['name']}'))).toList(),
              onChanged: (v) => setState(() => _siteId = v),
            ),
            const SizedBox(height: 12),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'LOW', label: Text('Low')),
                ButtonSegment(value: 'MEDIUM', label: Text('Med')),
                ButtonSegment(value: 'HIGH', label: Text('High')),
                ButtonSegment(value: 'CRITICAL', label: Text('Critical')),
              ],
              selected: {_severity},
              onSelectionChanged: (s) => setState(() => _severity = s.first),
            ),
            const SizedBox(height: 12),
            TextField(controller: _title, decoration: const InputDecoration(labelText: 'What happened?', border: OutlineInputBorder())),
            const SizedBox(height: 12),
            TextField(controller: _desc, maxLines: 3, decoration: const InputDecoration(labelText: 'Details', border: OutlineInputBorder())),
            const SizedBox(height: 16),
            BusyButton(
              onPressed: () async {
                if (_siteId == null) throw Exception('Choose the site');
                if (_title.text.trim().isEmpty) throw Exception('Describe what happened');
                await widget.session.api.post('/v1/incidents', {'siteId': _siteId, 'severity': _severity, 'title': _title.text.trim(), 'description': _desc.text.trim()});
                if (context.mounted) Navigator.pop(context, true);
              },
              child: const Text('Send'),
            ),
          ]);
        },
      ),
    );
  }
}
