import 'package:material_ui/material_ui.dart';

import '../../core/l10n.dart';
import '../../core/session.dart';
import '../../widgets/common.dart';

/// Month attendance for the signed-in employee.
class MyAttendanceTab extends StatefulWidget {
  final Session session;
  const MyAttendanceTab({super.key, required this.session});
  @override
  State<MyAttendanceTab> createState() => _MyAttendanceTabState();
}

class _MyAttendanceTabState extends State<MyAttendanceTab> {
  DateTime _month = DateTime(DateTime.now().year, DateTime.now().month);

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        IconButton(icon: const Icon(Icons.chevron_left), onPressed: () => setState(() => _month = DateTime(_month.year, _month.month - 1))),
        Text(ym(_month), style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
        IconButton(icon: const Icon(Icons.chevron_right), onPressed: () => setState(() => _month = DateTime(_month.year, _month.month + 1))),
      ]),
      Expanded(
        child: AsyncList<Map<String, dynamic>>(
          key: ValueKey(_month),
          empty: tr('no_attendance'),
          load: () async {
            final rows = (await widget.session.api.get('/v1/me/attendance?month=${ym(_month)}') as List).map((e) => Map<String, dynamic>.from(e)).toList();
            final units = rows.fold<num>(0, (a, r) => a + (num.tryParse('${r['pay_units']}') ?? 0));
            return [{'_summary': units}, ...rows];
          },
          item: (context, r, _) {
            if (r.containsKey('_summary')) {
              return Card(child: ListTile(leading: const Icon(Icons.summarize), title: Text(tr('paid_duties', {'n': r['_summary']}))));
            }
            return Card(
              child: ListTile(
                title: Text('${fmtDate(r['duty_date'])} · ${r['site_name']}'),
                subtitle: Text('In ${fmtTime(r['check_in_at'])} · Out ${fmtTime(r['check_out_at'])}'
                    '${(r['ot_minutes'] ?? 0) > 0 ? ' · OT ${r['ot_minutes']}m' : ''}'),
                trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                  StatusChip('${r['status']}'),
                  const SizedBox(height: 4),
                  Text('${r['approval_status']}', style: const TextStyle(fontSize: 10, color: Colors.grey)),
                ]),
              ),
            );
          },
        ),
      ),
    ]);
  }
}

class PayslipsTab extends StatelessWidget {
  final Session session;
  const PayslipsTab({super.key, required this.session});

  @override
  Widget build(BuildContext context) {
    return AsyncList<Map<String, dynamic>>(
      empty: tr('no_payslips'),
      load: () async => (await session.api.get('/v1/me/payslips') as List).map((e) => Map<String, dynamic>.from(e)).toList(),
      item: (context, p, _) => Card(
        child: ListTile(
          leading: const Icon(Icons.receipt_long),
          title: Text('${'${p['period_start']}'.substring(0, 7)} · Net ${fmtMoney(p['net'])}'),
          subtitle: Text('${p['duties']} duties · Gross ${fmtMoney(p['gross'])}'),
          trailing: StatusChip('${p['payment_status']}'),
          onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => PayslipScreen(session: session, id: '${p['id']}'))),
        ),
      ),
    );
  }
}

class PayslipScreen extends StatelessWidget {
  final Session session;
  final String id;
  const PayslipScreen({super.key, required this.session, required this.id});
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(tr('payslip'))),
      body: FutureBuilder(
        future: session.api.get('/v1/payroll/payslips/$id'),
        builder: (context, snap) {
          if (snap.hasError) return Center(child: Text(errorText(snap.error!)));
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          final p = Map<String, dynamic>.from(snap.data as Map);
          final lines = ((p['lines'] as List?) ?? []).map((e) => Map<String, dynamic>.from(e)).toList();
          Widget section(String title, String kind) {
            final ls = lines.where((l) => l['kind'] == kind).toList();
            if (ls.isEmpty) return const SizedBox.shrink();
            return Card(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Padding(padding: const EdgeInsets.fromLTRB(16, 12, 16, 4), child: Text(title, style: const TextStyle(fontWeight: FontWeight.bold))),
                for (final l in ls)
                  ListTile(
                    dense: true,
                    title: Text('${l['component_name']}${l['site_name'] != null ? ' · ${l['site_name']}' : ''}'),
                    subtitle: l['quantity'] != null ? Text('${l['quantity']} ${l['component_code'] == 'DUTY_WAGE' ? 'duties' : ''}') : null,
                    trailing: Text(fmtMoney(l['amount'])),
                  ),
              ]),
            );
          }

          return ListView(padding: const EdgeInsets.all(12), children: [
            Card(
              child: ListTile(
                title: Text('${p['employee_name']} (${p['employee_code']})'),
                subtitle: Text('${p['period_start']} to ${p['period_end']}\nA/c ${p['bank_account_no'] ?? '-'}'),
                trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                  Text(tr('net_pay'), style: const TextStyle(fontSize: 12, color: Colors.grey)),
                  Text(fmtMoney(p['net']), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                ]),
              ),
            ),
            section('Earnings', 'EARNING'),
            section('Deductions', 'DEDUCTION'),
            section('Employer contributions', 'EMPLOYER_CONTRIBUTION'),
          ]);
        },
      ),
    );
  }
}

class LeaveTab extends StatelessWidget {
  final Session session;
  const LeaveTab({super.key, required this.session});

  Future<void> _apply(BuildContext context, VoidCallback reload) async {
    final ok = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, builder: (_) => _LeaveForm(session: session));
    if (ok == true) reload();
  }

  @override
  Widget build(BuildContext context) {
    final key = GlobalKey<AsyncListState<Map<String, dynamic>>>();
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
          onPressed: () => _apply(context, () => key.currentState?.reload()), icon: const Icon(Icons.add), label: Text(tr('apply_leave'))),
      body: AsyncList<Map<String, dynamic>>(
        key: key,
        empty: tr('no_leave'),
        load: () async => (await session.api.get('/v1/leaves') as List).map((e) => Map<String, dynamic>.from(e)).toList(),
        item: (context, l, _) => Card(
          child: ListTile(
            title: Text('${l['leaveType']} · ${l['fromDate']} → ${l['toDate']}'),
            subtitle: l['reason'] == null ? null : Text('${l['reason']}'),
            trailing: StatusChip('${l['status']}'),
          ),
        ),
      ),
    );
  }
}

class _LeaveForm extends StatefulWidget {
  final Session session;
  const _LeaveForm({required this.session});
  @override
  State<_LeaveForm> createState() => _LeaveFormState();
}

class _LeaveFormState extends State<_LeaveForm> {
  String _type = 'CASUAL';
  DateTimeRange? _range;
  final _reason = TextEditingController();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 16, 16, MediaQuery.of(context).viewInsets.bottom + 16),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(tr('apply_for_leave'), style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _type,
          decoration: InputDecoration(labelText: tr('leave_type'), border: const OutlineInputBorder()),
          items: const ['CASUAL', 'SICK', 'EARNED', 'UNPAID'].map((t) => DropdownMenuItem(value: t, child: Text(t))).toList(),
          onChanged: (v) => setState(() => _type = v!),
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          icon: const Icon(Icons.date_range),
          label: Text(_range == null ? 'Pick dates' : '${ymd(_range!.start)} → ${ymd(_range!.end)}'),
          onPressed: () async {
            final now = DateTime.now();
            final r = await showDateRangePicker(context: context, firstDate: now.subtract(const Duration(days: 30)), lastDate: now.add(const Duration(days: 365)));
            if (r != null) setState(() => _range = r);
          },
        ),
        const SizedBox(height: 12),
        TextField(controller: _reason, decoration: InputDecoration(labelText: tr('reason'), border: const OutlineInputBorder()), maxLines: 2),
        const SizedBox(height: 16),
        BusyButton(
          onPressed: () async {
            if (_range == null) throw Exception('Pick the dates');
            await widget.session.api.post('/v1/leaves', {'leaveType': _type, 'fromDate': ymd(_range!.start), 'toDate': ymd(_range!.end), 'reason': _reason.text});
            if (context.mounted) Navigator.pop(context, true);
          },
          child: Text(tr('submit')),
        ),
      ]),
    );
  }
}
