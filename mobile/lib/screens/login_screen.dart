import 'package:material_ui/material_ui.dart';

import '../config/brand.dart';
import '../core/session.dart';
import '../widgets/common.dart';

class LoginScreen extends StatefulWidget {
  final Session session;
  const LoginScreen({super.key, required this.session});
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  late final _server = TextEditingController(text: widget.session.api.baseUrl);
  final _login = TextEditingController();
  final _pw = TextEditingController();
  bool _hide = true;
  bool _showServer = false;

  @override
  void initState() {
    super.initState();
    _showServer = _server.text.isEmpty;
  }

  Future<void> _submit() async {
    if (_server.text.trim().isEmpty) {
      setState(() => _showServer = true);
      throw Exception('Enter your agency server address');
    }
    await widget.session.login(_server.text, _login.text, _pw.text);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: AutofillGroup(
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  Icon(Icons.shield, size: 64, color: Brand.primary),
                  const SizedBox(height: 8),
                  Text(Brand.name, textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
                  Text(Brand.tagline, textAlign: TextAlign.center, style: const TextStyle(color: Colors.grey)),
                  const SizedBox(height: 32),
                  TextField(
                    controller: _login,
                    keyboardType: TextInputType.emailAddress,
                    autofillHints: const [AutofillHints.username],
                    decoration: const InputDecoration(labelText: 'Mobile number or e-mail', prefixIcon: Icon(Icons.person_outline), border: OutlineInputBorder()),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _pw,
                    obscureText: _hide,
                    autofillHints: const [AutofillHints.password],
                    decoration: InputDecoration(
                      labelText: 'Password',
                      prefixIcon: const Icon(Icons.lock_outline),
                      border: const OutlineInputBorder(),
                      suffixIcon: IconButton(icon: Icon(_hide ? Icons.visibility : Icons.visibility_off), onPressed: () => setState(() => _hide = !_hide)),
                    ),
                  ),
                  if (_showServer) ...[
                    const SizedBox(height: 12),
                    TextField(
                      controller: _server,
                      keyboardType: TextInputType.url,
                      decoration: const InputDecoration(
                        labelText: 'Server address',
                        hintText: 'https://erp.youragency.com',
                        helperText: 'Ask your office for this once; it is remembered.',
                        prefixIcon: Icon(Icons.dns_outlined),
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ],
                  const SizedBox(height: 20),
                  SizedBox(height: 52, child: BusyButton(onPressed: _submit, child: const Text('Sign in', style: TextStyle(fontSize: 16)))),
                  if (!_showServer)
                    TextButton(onPressed: () => setState(() => _showServer = true), child: Text('Server: ${_server.text}', style: const TextStyle(fontSize: 12))),
                ]),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
