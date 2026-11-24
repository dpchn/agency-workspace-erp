import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../config/brand.dart';
import '../core/api.dart';
import '../core/l10n.dart';
import '../core/otp.dart';
import '../core/self_sms.dart';
import '../core/session.dart';
import '../widgets/common.dart';
import 'self_sms_screen.dart';

/// Which step of signing in is on screen.
enum _Step {
  /// Default: type the mobile number, get an SMS code.
  phone,

  /// Type the 6-digit code (sign-in, or password reset with a new password).
  code,

  /// Office staff / supervisors with an e-mail and password.
  password,
}

/// Sign-in for people who may not read well: one big field, one big button. A worker types their mobile
/// number, gets an SMS code and is in; nobody can register themselves (codes only go to numbers the agency added).
class LoginScreen extends StatefulWidget {
  final Session session;
  const LoginScreen({super.key, required this.session});
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  late final _server = TextEditingController(text: widget.session.api.baseUrl);
  final _phone = TextEditingController();
  final _code = TextEditingController();
  final _login = TextEditingController();
  final _pw = TextEditingController();
  final _newPw = TextEditingController();
  final _newPw2 = TextEditingController();
  bool _hide = true;
  bool _showServer = false;
  bool _busy = false;
  _Step _step = _Step.phone;
  String _purpose = 'LOGIN';
  String? _phone10;
  String? _error;
  int _resendIn = 0;

  /// Self-SMS mode (no SMS gateway yet): this phone's SIM was checked and the code is filled in already.
  bool _selfVerified = false;
  Timer? _timer;

  Session get s => widget.session;

  @override
  void initState() {
    super.initState();
    _showServer = _server.text.isEmpty;
    s.lastPhone().then((p) {
      if (p != null && mounted && _phone.text.isEmpty) setState(() => _phone.text = p);
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  /// Runs an action with a spinner; turns errors into one big plain sentence under the field.
  Future<void> _run(Future<void> Function() action) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      if (_server.text.trim().isEmpty) {
        _showServer = true;
        throw Exception(tr('server_needed'));
      }
      await action();
    } catch (e) {
      final key = Otp.errorKey(e);
      final msg = key != null ? tr(key) : e is ApiException ? errorText(e) : '$e'.replaceFirst('Exception: ', '');
      if (mounted) setState(() => _error = msg);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _startCountdown() {
    _timer?.cancel();
    setState(() => _resendIn = Otp.resendSeconds);
    _timer = Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted) return t.cancel();
      setState(() => _resendIn--);
      if (_resendIn <= 0) t.cancel();
    });
  }

  Future<void> _sendCode() => _run(() async {
        final p = Otp.normalizePhone(_phone.text);
        if (p == null) throw Exception(tr('phone_invalid'));
        final res = await s.requestOtp(_server.text, p, purpose: _purpose);
        _phone10 = p;
        _code.clear();
        // self-SMS mode: send the code from this phone's SIM, read it back, then sign in by itself
        final self = SelfSms.codeFrom(res);
        if (self != null) {
          if (!mounted) return;
          final got = await SelfSmsScreen.run(context, p, self);
          if (got == null || !mounted) return;
          _code.text = got;
          if (_purpose == 'RESET') {
            setState(() {
              _selfVerified = true;
              _step = _Step.code;
            });
          } else {
            await s.loginWithOtp(_server.text, p, got);
          }
          return;
        }
        setState(() => _step = _Step.code);
        _startCountdown();
      });

  Future<void> _resend() => _run(() async {
        await s.requestOtp(_server.text, _phone10!, purpose: _purpose);
        _code.clear();
        if (mounted) toast(context, tr('code_sent'));
        _startCountdown();
      });

  Future<void> _verify() => _run(() async {
        if (!Otp.codeComplete(_code.text)) throw Exception(tr('otp_wrong'));
        if (_purpose == 'RESET') {
          if (_newPw.text.length < 8) throw Exception(tr('password_short'));
          if (_newPw.text != _newPw2.text) throw Exception(tr('passwords_differ'));
          await s.resetPassword(_server.text, _phone10!, _code.text, _newPw.text);
        } else {
          await s.loginWithOtp(_server.text, _phone10!, _code.text);
        }
      });

  Future<void> _passwordLogin() => _run(() => s.login(_server.text, _login.text, _pw.text));

  void _go(_Step step, {String purpose = 'LOGIN'}) => setState(() {
        _step = step;
        _selfVerified = false;
        _purpose = purpose;
        _error = null;
        _timer?.cancel();
        _resendIn = 0;
      });

  @override
  Widget build(BuildContext context) {
    final big = const TextStyle(fontSize: 26, fontWeight: FontWeight.w600, letterSpacing: 1);
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: AutofillGroup(
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  const LanguageSwitch(),
                  const SizedBox(height: 24),
                  Icon(Icons.shield, size: 64, color: Brand.primary),
                  const SizedBox(height: 8),
                  Text(Brand.name, textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
                  Text(_purpose == 'RESET' ? tr('reset_title') : Brand.tagline,
                      textAlign: TextAlign.center, style: const TextStyle(color: Colors.grey)),
                  const SizedBox(height: 28),
                  ...switch (_step) {
                    _Step.phone => _phoneStep(big),
                    _Step.code => _codeStep(big),
                    _Step.password => _passwordStep(),
                  },
                  if (_error != null) ...[
                    const SizedBox(height: 14),
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(color: Colors.red.shade50, borderRadius: BorderRadius.circular(12), border: Border.all(color: Colors.red.shade200)),
                      child: Row(children: [
                        Icon(Icons.error_outline, color: Colors.red.shade700, size: 30),
                        const SizedBox(width: 10),
                        Expanded(child: Text(_error!, style: TextStyle(fontSize: 18, color: Colors.red.shade800, fontWeight: FontWeight.w600))),
                      ]),
                    ),
                  ],
                  if (_showServer) ...[
                    const SizedBox(height: 16),
                    TextField(
                      controller: _server,
                      keyboardType: TextInputType.url,
                      decoration: InputDecoration(
                        labelText: tr('server'),
                        hintText: 'https://erp.youragency.com',
                        helperText: tr('server_help'),
                        prefixIcon: const Icon(Icons.dns_outlined),
                        border: const OutlineInputBorder(),
                      ),
                    ),
                  ] else
                    TextButton(onPressed: () => setState(() => _showServer = true), child: Text(tr('server_label', {'url': _server.text}), style: const TextStyle(fontSize: 12))),
                ]),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _bigButton({required IconData icon, required String label, required VoidCallback? onPressed}) => SizedBox(
        height: 64,
        child: FilledButton.icon(
          onPressed: _busy ? null : onPressed,
          icon: _busy ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 3)) : Icon(icon, size: 28),
          label: Text(label, style: const TextStyle(fontSize: 22)),
        ),
      );

  List<Widget> _phoneStep(TextStyle big) => [
        TextField(
          controller: _phone,
          keyboardType: TextInputType.phone,
          autofillHints: const [AutofillHints.telephoneNumberNational],
          inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(10)],
          style: big,
          onSubmitted: (_) => _sendCode(),
          decoration: InputDecoration(
            labelText: tr('phone_label'),
            prefixIcon: const Icon(Icons.phone_android, size: 30),
            prefixText: '+91 ',
            prefixStyle: big.copyWith(color: Colors.grey.shade700),
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 20),
        _bigButton(icon: Icons.sms_outlined, label: tr('send_code'), onPressed: _sendCode),
        const SizedBox(height: 12),
        if (_purpose == 'RESET')
          TextButton(onPressed: () => _go(_Step.password), child: Text(tr('cancel'), style: const TextStyle(fontSize: 16)))
        else
          TextButton.icon(
            onPressed: () => _go(_Step.password),
            icon: const Icon(Icons.alternate_email),
            label: Text(tr('use_password'), style: const TextStyle(fontSize: 16)),
          ),
      ];

  List<Widget> _codeStep(TextStyle big) => [
        if (_selfVerified)
          Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(Icons.verified, color: Colors.green.shade700, size: 30),
            const SizedBox(width: 8),
            Text(Otp.display(_phone10 ?? '0000000000'), style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
          ])
        else ...[
          Text(tr('code_sent_to', {'phone': Otp.display(_phone10 ?? '0000000000')}),
              textAlign: TextAlign.center, style: const TextStyle(fontSize: 18)),
          const SizedBox(height: 16),
          TextField(
            controller: _code,
            autofocus: true,
            keyboardType: TextInputType.number,
            autofillHints: const [AutofillHints.oneTimeCode],
            inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(Otp.codeLength)],
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 38, fontWeight: FontWeight.bold, letterSpacing: 14),
            decoration: InputDecoration(labelText: tr('code_label'), hintText: '••••••', border: const OutlineInputBorder()),
            // sign-in goes by itself when the 6th digit is typed; a reset still needs the new password
            onChanged: (v) {
              if (_purpose == 'LOGIN' && Otp.codeComplete(v)) _verify();
            },
          ),
        ],
        if (_purpose == 'RESET') ...[
          const SizedBox(height: 12),
          TextField(
            controller: _newPw,
            obscureText: _hide,
            autofillHints: const [AutofillHints.newPassword],
            style: const TextStyle(fontSize: 20),
            decoration: InputDecoration(
              labelText: tr('new_password'),
              prefixIcon: const Icon(Icons.lock, size: 28),
              border: const OutlineInputBorder(),
              suffixIcon: IconButton(icon: Icon(_hide ? Icons.visibility : Icons.visibility_off), onPressed: () => setState(() => _hide = !_hide)),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _newPw2,
            obscureText: _hide,
            style: const TextStyle(fontSize: 20),
            decoration: InputDecoration(labelText: tr('new_password_again'), prefixIcon: const Icon(Icons.lock, size: 28), border: const OutlineInputBorder()),
          ),
        ],
        const SizedBox(height: 20),
        _bigButton(
            icon: _purpose == 'RESET' ? Icons.save : Icons.login,
            label: tr(_purpose == 'RESET' ? 'save_and_sign_in' : 'sign_in'),
            onPressed: _verify),
        const SizedBox(height: 8),
        if (!_selfVerified)
          Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            TextButton.icon(
              onPressed: () => _go(_Step.phone, purpose: _purpose),
              icon: const Icon(Icons.edit),
              label: Text(tr('change_number'), style: const TextStyle(fontSize: 16)),
            ),
            TextButton.icon(
              onPressed: _resendIn > 0 || _busy ? null : _resend,
              icon: const Icon(Icons.refresh),
              label: Text(_resendIn > 0 ? tr('resend_in', {'s': _resendIn}) : tr('resend'), style: const TextStyle(fontSize: 16)),
            ),
          ]),
      ];

  List<Widget> _passwordStep() => [
        TextField(
          controller: _login,
          keyboardType: TextInputType.emailAddress,
          autofillHints: const [AutofillHints.username],
          style: const TextStyle(fontSize: 20),
          decoration: InputDecoration(labelText: tr('login_id'), prefixIcon: const Icon(Icons.person, size: 28), border: const OutlineInputBorder()),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _pw,
          obscureText: _hide,
          autofillHints: const [AutofillHints.password],
          style: const TextStyle(fontSize: 20),
          onSubmitted: (_) => _passwordLogin(),
          decoration: InputDecoration(
            labelText: tr('password'),
            prefixIcon: const Icon(Icons.lock, size: 28),
            border: const OutlineInputBorder(),
            suffixIcon: IconButton(icon: Icon(_hide ? Icons.visibility : Icons.visibility_off), onPressed: () => setState(() => _hide = !_hide)),
          ),
        ),
        Align(
          alignment: Alignment.centerRight,
          child: TextButton(
            onPressed: () => _go(_Step.phone, purpose: 'RESET'),
            child: Text(tr('forgot_password'), style: const TextStyle(fontSize: 16)),
          ),
        ),
        const SizedBox(height: 8),
        _bigButton(icon: Icons.login, label: tr('sign_in'), onPressed: _passwordLogin),
        const SizedBox(height: 12),
        TextButton.icon(
          onPressed: () => _go(_Step.phone),
          icon: const Icon(Icons.phone_android),
          label: Text(tr('use_otp'), style: const TextStyle(fontSize: 16)),
        ),
      ];
}
