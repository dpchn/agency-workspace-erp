import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../core/api.dart';
import '../../core/kiosk.dart';
import '../../core/l10n.dart';
import '../../core/otp.dart';
import '../../core/photos.dart';
import '../../core/punch.dart';
import '../../core/self_sms.dart';
import '../../core/session.dart';
import '../../widgets/common.dart';
import '../../widgets/result_screen.dart';
import '../self_sms_screen.dart';

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
    // the language button is on this screen, which may sit above the app's home on a supervisor's phone
    L10n.instance.addListener(_refresh);
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
    L10n.instance.removeListener(_refresh);
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
      if (!silent && mounted) toast(context, tr('sent_n', {'n': n}));
    } catch (e) {
      if (!silent && mounted) toast(context, errorText(e), error: true);
    }
  }

  List<Map<String, dynamic>> get _duties => ((_data?['duties'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e)).toList();

  String get _siteNames {
    final sites = ((_data?['sites'] as List?) ?? const []).map((e) => '${(e as Map)['name']}').toList();
    if (sites.isNotEmpty) return sites.join(', ');
    final fromDuties = _duties.map((d) => '${d['siteName']}').toSet();
    return fromDuties.join(', ');
  }

  /// Full punch flow: PIN pad (submits itself) -> photo (takes itself) -> send -> big result. For a worker:
  /// tap your name, type 4 digits, look at the camera.
  Future<void> _start({Map<String, dynamic>? duty, String? code, required String kind}) async {
    final name = duty == null ? '$code' : '${duty['employeeName']} (${duty['employeeCode']})';
    final pin = await Navigator.push<String>(context, MaterialPageRoute(builder: (_) => _PinPad(title: name, kind: kind)));
    if (pin == null || !mounted) return;
    final photo = await Photos.capture(front: true);
    if (photo == null) {
      if (mounted) toast(context, tr('photo_needed'), error: true);
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
    await ResultScreen.forPunch(context, r, kind: kind, name: name);
    _load();
  }

  Future<void> _enterCode() async {
    final c = TextEditingController();
    final res = await showDialog<(String, String)>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(tr('your_code')),
        content: TextField(
          controller: c,
          autofocus: true,
          textCapitalization: TextCapitalization.characters,
          style: const TextStyle(fontSize: 28),
          decoration: const InputDecoration(hintText: 'G12'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: Text(tr('cancel'))),
          FilledButton.icon(
              style: FilledButton.styleFrom(backgroundColor: Colors.orange.shade800),
              onPressed: () => c.text.trim().isEmpty ? null : Navigator.pop(ctx, (c.text.trim(), 'CHECK_OUT')),
              icon: const Icon(Icons.logout),
              label: Text(tr('out'))),
          FilledButton.icon(
              style: FilledButton.styleFrom(backgroundColor: Colors.green.shade700),
              onPressed: () => c.text.trim().isEmpty ? null : Navigator.pop(ctx, (c.text.trim(), 'CHECK_IN')),
              icon: const Icon(Icons.login),
              label: Text(tr('in'))),
        ],
      ),
    );
    if (res != null) await _start(code: res.$1, kind: res.$2);
  }

  /// Leaving kiosk mode: the signed-in supervisor / office person proves it is them, with their password, or
  /// (for people who sign in by SMS code and have no password) a fresh SMS code to their own number.
  Future<void> _exit() async {
    final c = TextEditingController();
    final hasPw = await s.hasUnlockPassword();
    final phone = await s.unlockPhone();
    if (!mounted) return;
    var bySms = !hasPw && phone != null;
    var sent = false;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setD) {
        Future<void> send() async {
          try {
            final res = await s.sendUnlockCode();
            // self-SMS mode: check this phone's SIM instead of typing a code, then unlock by itself
            final self = SelfSms.codeFrom(res);
            if (self != null && phone != null) {
              if (!ctx.mounted) return;
              final got = await SelfSmsScreen.run(ctx, phone, self);
              if (got == null) return;
              final ok = await s.checkOwnCode(got);
              if (ctx.mounted) Navigator.pop(ctx, ok);
              return;
            }
            setD(() => sent = true);
            if (ctx.mounted) toast(ctx, tr('code_sent'));
          } catch (e) {
            final k = Otp.errorKey(e);
            if (ctx.mounted) toast(ctx, k != null ? tr(k) : errorText(e), error: true);
          }
        }

        return AlertDialog(
          title: Text(tr(widget.handedOver ? 'kiosk_leave' : 'kiosk_signout_device')),
          content: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(
              controller: c,
              obscureText: !bySms,
              autofocus: true,
              keyboardType: bySms ? TextInputType.number : TextInputType.visiblePassword,
              autofillHints: bySms ? const [AutofillHints.oneTimeCode] : null,
              style: bySms ? const TextStyle(fontSize: 28, letterSpacing: 8) : null,
              decoration: InputDecoration(
                  labelText: bySms ? tr('kiosk_code_hint', {'name': s.userName}) : tr('kiosk_password_of', {'name': s.userName})),
            ),
            if (bySms && !sent) TextButton.icon(onPressed: send, icon: const Icon(Icons.sms_outlined), label: Text(tr('send_code'))),
            if (bySms && sent) TextButton.icon(onPressed: send, icon: const Icon(Icons.refresh), label: Text(tr('resend'))),
            if (!bySms && phone != null)
              TextButton(onPressed: () => setD(() { bySms = true; c.clear(); }), child: Text(tr('kiosk_send_code'))),
            if (bySms && hasPw)
              TextButton(onPressed: () => setD(() { bySms = false; c.clear(); }), child: Text(tr('kiosk_use_password'))),
          ]),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(tr('cancel'))),
            FilledButton(
                onPressed: () async {
                  bool ok;
                  try {
                    ok = bySms ? await s.checkOwnCode(c.text) : await s.checkOwnPassword(c.text);
                  } catch (_) {
                    ok = false;
                  }
                  if (ctx.mounted) Navigator.pop(ctx, ok);
                },
                child: Text(tr('unlock'))),
          ],
        );
      }),
    );
    if (!mounted) return;
    if (ok != true) {
      if (ok == false && c.text.isNotEmpty) toast(context, tr(Otp.codeComplete(c.text) ? 'otp_wrong' : 'wrong_password'), error: true);
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
            Text(tr('kiosk_title'), style: const TextStyle(fontWeight: FontWeight.bold)),
            Text(_siteNames, style: const TextStyle(fontSize: 12, color: Colors.grey), overflow: TextOverflow.ellipsis),
          ]),
          actions: [
            if (q.pending > 0)
              TextButton.icon(onPressed: _sync, icon: const Icon(Icons.cloud_upload_outlined), label: Text(tr('kiosk_to_send', {'n': q.pending}))),
            const LanguageButton(),
            IconButton(tooltip: tr('retry'), icon: const Icon(Icons.refresh), onPressed: _load),
            // long-press so a worker does not leave by accident. Not an IconButton: its tooltip would
            // take the long-press and the exit would never fire.
            Semantics(
              button: true,
              label: tr('kiosk_hold_exit'),
              child: InkResponse(
                radius: 24,
                onTap: () => toast(context, tr('kiosk_hold_exit')),
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
              child: Text(tr('kiosk_offline'), textAlign: TextAlign.center),
            ),
          if (_error != null) Padding(padding: const EdgeInsets.all(12), child: Text(_error!, style: TextStyle(color: Colors.red.shade700))),
          Padding(
            padding: const EdgeInsets.all(12),
            child: SizedBox(
              width: double.infinity,
              height: 64,
              child: OutlinedButton.icon(
                onPressed: _enterCode,
                icon: const Icon(Icons.dialpad, size: 28),
                label: Text(tr('kiosk_enter_code'), style: const TextStyle(fontSize: 20)),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Align(alignment: Alignment.centerLeft, child: Text(tr('kiosk_tap_name'), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600))),
          ),
          Expanded(
            child: _data == null && _error == null
                ? const Center(child: CircularProgressIndicator())
                : duties.isEmpty
                    ? Center(child: Text(tr('kiosk_no_duties'), style: const TextStyle(color: Colors.grey, fontSize: 18)))
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
    final noPin = duty['pinSet'] == false;
    final state = duty['checkOutAt'] != null
        ? tr('out_at', {'time': fmtTime(duty['checkOutAt'])})
        : duty['checkInAt'] != null
            ? tr('in_at', {'time': fmtTime(duty['checkInAt'])})
            : tr('not_in');
    final chipColor = kind == 'CHECK_IN' ? Colors.green.shade700 : Colors.orange.shade800;
    final enabled = kind != null && !noPin;
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: enabled ? () => onTap(duty: duty, kind: kind) : null,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(children: [
            CircleAvatar(
              radius: 34,
              backgroundImage: photoId == null ? null : NetworkImage(session.photoUrl(photoId), headers: session.authHeaders),
              child: photoId == null ? Text('${duty['employeeName']}'.characters.first, style: const TextStyle(fontSize: 28)) : null,
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${duty['employeeName']}', style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
                Text('${duty['employeeCode']} · ${fmtTime(duty['startAt'])}–${fmtTime(duty['endAt'])}', style: const TextStyle(fontSize: 15)),
                Text(noPin ? tr('no_pin') : state, style: TextStyle(fontSize: 15, color: noPin ? Colors.red.shade700 : Colors.grey.shade700)),
              ]),
            ),
            if (kind == null)
              Icon(Icons.check_circle, color: Colors.green.shade700, size: 44)
            else
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                decoration: BoxDecoration(color: enabled ? chipColor : Colors.grey.shade400, borderRadius: BorderRadius.circular(14)),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  Icon(kind == 'CHECK_IN' ? Icons.login : Icons.logout, color: Colors.white, size: 24),
                  const SizedBox(width: 6),
                  Text(tr(kind == 'CHECK_IN' ? 'in' : 'out'), style: const TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold)),
                ]),
              ),
          ]),
        ),
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
  Timer? _auto;

  /// PINs are 4 to 6 digits: once 4 are typed, submit by itself shortly after the last digit (no button to find).
  static const autoSubmitDelay = Duration(milliseconds: 1200);

  void _tap(String k) {
    _auto?.cancel();
    setState(() {
      if (k == '<') {
        if (_pin.isNotEmpty) _pin = _pin.substring(0, _pin.length - 1);
      } else if (_pin.length < 6) {
        _pin += k;
      }
    });
    if (_pin.length == 6) {
      _submit();
    } else if (Kiosk.validPin(_pin)) {
      _auto = Timer(autoSubmitDelay, _submit);
    }
  }

  void _submit() {
    _auto?.cancel();
    if (mounted && Kiosk.validPin(_pin)) Navigator.pop(context, _pin);
  }

  @override
  void dispose() {
    _auto?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    Widget key(String k) => Expanded(
          child: Padding(
            padding: const EdgeInsets.all(6),
            child: SizedBox(
              height: 76,
              child: k.isEmpty
                  ? const SizedBox()
                  : OutlinedButton(
                      onPressed: () => _tap(k),
                      child: k == '<' ? const Icon(Icons.backspace_outlined, size: 30) : Text(k, style: const TextStyle(fontSize: 34, fontWeight: FontWeight.bold)),
                    ),
            ),
          ),
        );
    final isIn = widget.kind == 'CHECK_IN';
    return Scaffold(
      appBar: AppBar(
        backgroundColor: isIn ? Colors.green.shade700 : Colors.orange.shade800,
        foregroundColor: Colors.white,
        title: Row(children: [
          Icon(isIn ? Icons.login : Icons.logout),
          const SizedBox(width: 8),
          Text(tr(isIn ? 'check_in' : 'check_out')),
        ]),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(children: [
            Text(widget.title, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold), textAlign: TextAlign.center),
            const SizedBox(height: 8),
            Text(tr('enter_pin'), style: const TextStyle(fontSize: 20)),
            const SizedBox(height: 16),
            Text(_pin.isEmpty ? '○ ○ ○ ○' : '● ' * _pin.length, style: const TextStyle(fontSize: 40, letterSpacing: 6)),
            const Spacer(),
            for (final row in const [['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9'], ['', '0', '<']]) Row(children: row.map(key).toList()),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              height: 72,
              child: FilledButton.icon(
                style: FilledButton.styleFrom(backgroundColor: Colors.green.shade700),
                onPressed: Kiosk.validPin(_pin) ? _submit : null,
                icon: const Icon(Icons.check, size: 40),
                label: Text(tr('ok'), style: const TextStyle(fontSize: 26)),
              ),
            ),
          ]),
        ),
      ),
    );
  }
}

