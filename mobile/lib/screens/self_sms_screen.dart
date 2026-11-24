import 'package:material_ui/material_ui.dart';

import '../core/l10n.dart';
import '../core/otp.dart';
import '../core/self_sms.dart';

/// Full-screen "Checking this phone's SIM…": sends the sign-in code by SMS from this phone to [phone10] and reads it
/// back. Pops with the code on success, or null if the person cancels. Starts by itself once SMS is allowed.
class SelfSmsScreen extends StatefulWidget {
  final String phone10;
  final String code;
  const SelfSmsScreen({super.key, required this.phone10, required this.code});

  /// Runs the check; the code read back from the SMS, or null.
  static Future<String?> run(BuildContext context, String phone10, String code) => Navigator.push<String>(
    context,
    MaterialPageRoute(
      builder: (_) => SelfSmsScreen(phone10: phone10, code: code),
    ),
  );

  @override
  State<SelfSmsScreen> createState() => _SelfSmsScreenState();
}

class _SelfSmsScreenState extends State<SelfSmsScreen> {
  bool _checking = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    // no extra tap once SMS permission was given before
    SelfSms.hasPermission()
        .then((ok) {
          if (ok && mounted) _check();
        })
        .catchError((_) {});
  }

  Future<void> _check() async {
    if (_checking) return;
    setState(() {
      _checking = true;
      _error = null;
    });
    try {
      final got = await SelfSms.verifyOwnSim(widget.phone10, widget.code);
      if (mounted) Navigator.pop(context, got);
    } on SelfSmsError catch (e) {
      if (mounted) setState(() => _error = tr(e.key, {'phone': Otp.display(widget.phone10)}));
    } catch (_) {
      if (mounted) setState(() => _error = tr('self_send_failed'));
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final phone = Otp.display(widget.phone10);
    return Scaffold(
      appBar: AppBar(title: Text(tr('self_title'))),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 24),
              Icon(
                _error != null ? Icons.sim_card_alert_outlined : Icons.sim_card_outlined,
                size: 96,
                color: _error != null ? Colors.red.shade700 : Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(height: 20),
              Text(
                phone,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 16),
              Text(tr('self_explain', {'phone': phone}), textAlign: TextAlign.center, style: const TextStyle(fontSize: 18)),
              const SizedBox(height: 28),
              if (_checking) ...[
                const Center(child: SizedBox(width: 64, height: 64, child: CircularProgressIndicator(strokeWidth: 6))),
                const SizedBox(height: 16),
                Text(
                  tr('self_checking'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
                ),
              ],
              if (_error != null)
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: Colors.red.shade50,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: Colors.red.shade200),
                  ),
                  child: Row(
                    children: [
                      Icon(Icons.error_outline, color: Colors.red.shade700, size: 30),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          _error!,
                          style: TextStyle(fontSize: 18, color: Colors.red.shade800, fontWeight: FontWeight.w600),
                        ),
                      ),
                    ],
                  ),
                ),
              const Spacer(),
              if (!_checking)
                SizedBox(
                  height: 64,
                  child: FilledButton.icon(
                    onPressed: _check,
                    icon: Icon(_error != null ? Icons.refresh : Icons.verified_user_outlined, size: 28),
                    label: Text(tr(_error != null ? 'retry' : 'self_verify'), style: const TextStyle(fontSize: 22)),
                  ),
                ),
              const SizedBox(height: 8),
              TextButton(
                onPressed: _checking ? null : () => Navigator.pop(context),
                child: Text(tr('cancel'), style: const TextStyle(fontSize: 16)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
