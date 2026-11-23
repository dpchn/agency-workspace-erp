import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../core/api.dart';
import '../../core/kiosk.dart';
import '../../core/photos.dart';
import '../../core/punch.dart';
import '../../core/session.dart';
import '../../widgets/common.dart';

/// Shared attendance device for workers without a phone: a gate tablet (SITE_KIOSK login) or a
/// supervisor's phone handed around. Worker taps their name (or types their code), enters their PIN,
/// and the front camera takes a photo. Leaving needs the signed-in person's password.
class KioskScreen extends StatefulWidget {
  final Session session;

  /// True on a supervisor's phone (can go back to the app); false on a fixed site device (can only sign out).
  final bool handedOver;
  const KioskScreen({super.key, required this.session, this.handedOver = false});
  @override
  State<KioskScreen> createState() => _KioskScreenState();
}

class _KioskScreenState extends State<KioskScreen> {
  Map<String, dynamic>? _data;
  bool _offline = false;
  String? _error;
  Timer? _timer;
  Session get s => widget.session;

  @override
  void initState() {
    super.initState();
    s.actions.addListener(_refresh);
    _load();
    // keep the list fresh and push queued punches every minute
    _timer = Timer.periodic(const Duration(minutes: 1), (_) async {
      await _sync(silent: true);
      await _load();
    });
    _sync(silent: true);
  }

  @override
  void dispose() {
    _timer?.cancel();
    s.actions.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  Future<void> _load() async {
    try {
      final d = Map<String, dynamic>.from(await s.api.get('/v1/attendance/kiosk/duties'));
      await s.cacheKioskDuties(d);
      if (mounted) {
        setState(() {
          _data = d;
          _offline = false;
          _error = null;
        });
      }
    } on ApiException catch (e) {
      final cached = e.isNetwork ? await s.cachedKioskDuties() : null;
      if (mounted) {
        setState(() {
          _data = cached ?? _data;
          _offline = e.isNetwork;
          _error = e.isNetwork ? null : e.toString();
        });
      }
    }
  }

  Future<void> _sync({bool silent = false}) async {
    if (s.actions.pending == 0) return;
    try {
      final n = await s.actions.sync();
      if (!silent && mounted) toast(context, 'Sent $n queued punch(es)');
    } catch (e) {
      if (!silent && mounted) toast(context, errorText(e), error: true);
    }
  }

  List<Map<String, dynamic>> get _duties => ((_data?['duties'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e)).toList();

  String get _siteNames {
    final sites = ((_data?['sites'] as List?) ?? const []).map((e) => '${(e as Map)['name']}').toList();
    if (sites.isNotEmpty) return sites.join(', ');
    final fromDuties = _duties.map((d) => '${d['siteName']}').toSet();
    return fromDuties.isEmpty ? 'All my sites' : fromDuties.join(', ');
  }

  /// Full punch flow: PIN pad -> photo -> send -> big result.
  Future<void> _start({Map<String, dynamic>? duty, String? code, required String kind}) async {
    final name = duty == null ? 'Code $code' : '${duty['employeeName']} (${duty['employeeCode']})';
    final pin = await Navigator.push<String>(context, MaterialPageRoute(builder: (_) => _PinPad(title: name, kind: kind)));
    if (pin == null || !mounted) return;
    final photo = await Photos.capture(front: true);
    if (photo == null) {
      if (mounted) toast(context, 'A photo is needed to mark attendance', error: true);
      return;
    }
    if (!mounted) return;
    PunchResult r;
    showDialog(context: context, barrierDismissible: false, builder: (_) => const Center(child: CircularProgressIndicator()));
    try {
      r = await Kiosk.punch(s,
          kind: kind, pin: pin, employeeId: duty?['employeeId'] as String?, employeeCode: code,
          siteId: duty?['siteId'] as String?, photo: photo, label: name);
    } on ApiException catch (e) {
      r = PunchResult('REJECTED', e.fieldErrors?.values.join(', ') ?? e.message, const []);
    } finally {
      if (mounted) Navigator.pop(context);
    }
    if (!mounted) return;
    await Navigator.push(context, MaterialPageRoute(builder: (_) => _Result(name: name, kind: kind, result: r)));
    _load();
  }

  Future<void> _enterCode() async {
    final c = TextEditingController();
    final res = await showDialog<(String, String)>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Your employee code'),
        content: TextField(
          controller: c,
          autofocus: true,
          textCapitalization: TextCapitalization.characters,
          style: const TextStyle(fontSize: 24),
          decoration: const InputDecoration(hintText: 'e.g. G12'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          OutlinedButton(onPressed: () => c.text.trim().isEmpty ? null : Navigator.pop(ctx, (c.text.trim(), 'CHECK_OUT')), child: const Text('Check OUT')),
          FilledButton(onPressed: () => c.text.trim().isEmpty ? null : Navigator.pop(ctx, (c.text.trim(), 'CHECK_IN')), child: const Text('Check IN')),
        ],
      ),
    );
    if (res != null) await _start(code: res.$1, kind: res.$2);
  }

  /// Leaving kiosk mode: the signed-in supervisor / office person must type their password.
  Future<void> _exit() async {
    final c = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(widget.handedOver ? 'Leave kiosk mode' : 'Sign out this device'),
        content: TextField(controller: c, obscureText: true, autofocus: true, decoration: InputDecoration(labelText: 'Password of ${s.userName}')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
              onPressed: () async {
                final ok = await s.checkOwnPassword(c.text);
                if (ctx.mounted) Navigator.pop(ctx, ok);
              },
              child: const Text('Unlock')),
        ],
      ),
    );
    if (!mounted) return;
    if (ok != true) {
      if (ok == false && c.text.isNotEmpty) toast(context, 'Wrong password', error: true);
      return;
    }
    if (s.actions.pending > 0) await _sync();
    if (!mounted) return;
    if (widget.handedOver) {
      Navigator.pop(context);
    } else {
      await s.logout();
    }
  }

  @override
  Widget build(BuildContext context) {
    final duties = _duties;
    final q = s.actions;
    return PopScope(
      canPop: false,
      child: Scaffold(
        appBar: AppBar(
          automaticallyImplyLeading: false,
          title: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('Attendance', style: TextStyle(fontWeight: FontWeight.bold)),
            Text(_siteNames, style: const TextStyle(fontSize: 12, color: Colors.grey), overflow: TextOverflow.ellipsis),
          ]),
          actions: [
            if (q.pending > 0)
              TextButton.icon(onPressed: _sync, icon: const Icon(Icons.cloud_upload_outlined), label: Text('${q.pending} to send')),
            IconButton(tooltip: 'Refresh', icon: const Icon(Icons.refresh), onPressed: _load),
            // long-press so a worker does not leave by accident. Not an IconButton: its tooltip would
            // take the long-press and the exit would never fire.
            Semantics(
              button: true,
              label: 'Hold to exit',
              child: InkResponse(
                radius: 24,
                onTap: () => toast(context, 'Hold the lock to exit kiosk mode'),
                onLongPress: _exit,
                child: const Padding(padding: EdgeInsets.all(12), child: Icon(Icons.lock_outline)),
              ),
            ),
          ],
        ),
        body: Column(children: [
          if (_offline)
            Container(
              width: double.infinity,
              color: Colors.orange.shade100,
              padding: const EdgeInsets.all(8),
              child: const Text('No network: punches are saved on this device and sent later.', textAlign: TextAlign.center),
            ),
          if (_error != null) Padding(padding: const EdgeInsets.all(12), child: Text(_error!, style: TextStyle(color: Colors.red.shade700))),
          Padding(
            padding: const EdgeInsets.all(12),
            child: SizedBox(
              width: double.infinity,
              height: 56,
              child: FilledButton.icon(
                onPressed: _enterCode,
                icon: const Icon(Icons.dialpad),
                label: const Text('Enter my employee code', style: TextStyle(fontSize: 18)),
              ),
            ),
          ),
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 16),
            child: Align(alignment: Alignment.centerLeft, child: Text('Or tap your name', style: TextStyle(color: Colors.grey))),
          ),
          Expanded(
            child: _data == null && _error == null
                ? const Center(child: CircularProgressIndicator())
                : duties.isEmpty
                    ? const Center(child: Text('No duties at this site right now', style: TextStyle(color: Colors.grey)))
                    : RefreshIndicator(
                        onRefresh: _load,
                        child: ListView(
                          padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
                          children: [for (final d in duties) _DutyTile(session: s, duty: d, onTap: _start)],
                        ),
                      ),
          ),
        ]),
      ),
    );
  }
}

class _DutyTile extends StatelessWidget {
  final Session session;
  final Map<String, dynamic> duty;
  final Future<void> Function({Map<String, dynamic>? duty, String? code, required String kind}) onTap;
  const _DutyTile({required this.session, required this.duty, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final kind = Kiosk.nextKind(duty);
    final photoId = duty['photoId'] as String?;
    final state = duty['checkOutAt'] != null
        ? 'Out ${fmtTime(duty['checkOutAt'])}'
        : duty['checkInAt'] != null
            ? 'In ${fmtTime(duty['checkInAt'])}'
            : 'Not in yet';
    return Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        leading: CircleAvatar(
          radius: 26,
          backgroundImage: photoId == null ? null : NetworkImage(session.photoUrl(photoId), headers: session.authHeaders),
          child: photoId == null ? Text('${duty['employeeName']}'.characters.first, style: const TextStyle(fontSize: 20)) : null,
        ),
        title: Text('${duty['employeeName']}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
        subtitle: Text('${duty['employeeCode']} · ${duty['postName']} · ${fmtTime(duty['startAt'])}–${fmtTime(duty['endAt'])}\n$state'
            '${duty['pinSet'] == false ? ' · no PIN set' : ''}'),
        isThreeLine: true,
        trailing: kind == null
            ? const Icon(Icons.check_circle, color: Colors.green)
            : FilledButton(
                style: FilledButton.styleFrom(backgroundColor: kind == 'CHECK_IN' ? Colors.green.shade700 : Colors.blueGrey.shade700),
                onPressed: duty['pinSet'] == false ? null : () => onTap(duty: duty, kind: kind),
                child: Text(kind == 'CHECK_IN' ? 'IN' : 'OUT', style: const TextStyle(fontSize: 18)),
              ),
        onTap: kind == null || duty['pinSet'] == false ? null : () => onTap(duty: duty, kind: kind),
      ),
    );
  }
}

/// Big obscured PIN pad. Returns the PIN.
class _PinPad extends StatefulWidget {
  final String title;
  final String kind;
  const _PinPad({required this.title, required this.kind});
  @override
  State<_PinPad> createState() => _PinPadState();
}

class _PinPadState extends State<_PinPad> {
  String _pin = '';

  void _tap(String k) {
    setState(() {
      if (k == '<') {
        if (_pin.isNotEmpty) _pin = _pin.substring(0, _pin.length - 1);
      } else if (_pin.length < 6) {
        _pin += k;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    Widget key(String k) => Expanded(
          child: Padding(
            padding: const EdgeInsets.all(6),
            child: SizedBox(
              height: 72,
              child: k.isEmpty
                  ? const SizedBox()
                  : OutlinedButton(
                      onPressed: () => _tap(k),
                      child: k == '<' ? const Icon(Icons.backspace_outlined) : Text(k, style: const TextStyle(fontSize: 28)),
                    ),
            ),
          ),
        );
    return Scaffold(
      appBar: AppBar(title: Text(widget.kind == 'CHECK_IN' ? 'Check IN' : 'Check OUT')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(children: [
            Text(widget.title, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold), textAlign: TextAlign.center),
            const SizedBox(height: 8),
            const Text('Enter your PIN', style: TextStyle(color: Colors.grey)),
            const SizedBox(height: 16),
            Text(_pin.isEmpty ? '- - - -' : '●' * _pin.length, style: const TextStyle(fontSize: 36, letterSpacing: 8)),
            const Spacer(),
            for (final row in const [['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9'], ['', '0', '<']]) Row(children: row.map(key).toList()),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              height: 60,
              child: FilledButton(
                onPressed: Kiosk.validPin(_pin) ? () => Navigator.pop(context, _pin) : null,
                child: const Text('Next: photo', style: TextStyle(fontSize: 20)),
              ),
            ),
          ]),
        ),
      ),
    );
  }
}

/// Full-screen outcome that closes itself after a few seconds, ready for the next worker.
class _Result extends StatefulWidget {
  final String name;
  final String kind;
  final PunchResult result;
  const _Result({required this.name, required this.kind, required this.result});
  @override
  State<_Result> createState() => _ResultState();
}

class _ResultState extends State<_Result> {
  Timer? _t;
  @override
  void initState() {
    super.initState();
    _t = Timer(Duration(seconds: widget.result.ok ? 4 : 7), () {
      if (mounted) Navigator.pop(context);
    });
  }

  @override
  void dispose() {
    _t?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final r = widget.result;
    final (color, icon, title) = switch (r.status) {
      'ACCEPTED' => (Colors.green.shade700, Icons.check_circle, widget.kind == 'CHECK_IN' ? 'Checked IN' : 'Checked OUT'),
      'FLAGGED' => (Colors.orange.shade800, Icons.flag, 'Recorded, supervisor will review'),
      'QUEUED' => (Colors.blue.shade700, Icons.cloud_queue, 'Saved, will be sent'),
      _ => (Colors.red.shade700, Icons.cancel, 'Not recorded'),
    };
    return Scaffold(
      backgroundColor: color,
      body: SafeArea(
        child: InkWell(
          onTap: () => Navigator.pop(context),
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Icon(icon, size: 120, color: Colors.white),
                const SizedBox(height: 16),
                Text(title, style: const TextStyle(fontSize: 32, color: Colors.white, fontWeight: FontWeight.bold), textAlign: TextAlign.center),
                const SizedBox(height: 8),
                Text(widget.name, style: const TextStyle(fontSize: 22, color: Colors.white), textAlign: TextAlign.center),
                if (r.message != null || r.flags.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  Text(r.message ?? r.flags.join(', ').replaceAll('_', ' ').toLowerCase(),
                      style: const TextStyle(fontSize: 18, color: Colors.white), textAlign: TextAlign.center),
                ],
                const SizedBox(height: 32),
                const Text('Tap to continue', style: TextStyle(color: Colors.white70)),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}
