import 'package:flutter/services.dart';

/// Why a self-SMS check failed; each maps to one plain-words message.
class SelfSmsError implements Exception {
  final String key; // l10n key
  SelfSmsError(this.key);
  @override
  String toString() => key;
}

/// Self-SMS sign-in (server SMS_PROVIDER=self, until an SMS gateway is connected): the app sends the code from this
/// phone's own SIM to the typed number and reads it back. If the SMS comes back, that SIM is in this phone.
/// The server can't see the SMS, so it also binds the number to this phone (deviceId).
class SelfSms {
  SelfSms._();
  static const _ch = MethodChannel('dutygrid/sms');
  static const waitSeconds = 60;

  /// The code the server wants self-sent, or null in gateway mode (then the worker types the code).
  static String? codeFrom(Object? response) {
    if (response is! Map) return null;
    final self = response['selfSms'];
    final code = self is Map ? '${self['code'] ?? ''}' : '';
    return RegExp(r'^\d{6}$').hasMatch(code) ? code : null;
  }

  /// SMS text: short; the app hash at the end lets SMS Retriever hand this one message to the app.
  static String message(String code, String? appHash) => '<#> DutyGrid code: $code${appHash == null || appHash.isEmpty ? '' : '\n$appHash'}';

  /// The 6-digit code in a received SMS, or null.
  static String? extractCode(String? text) => RegExp(r'(?<!\d)(\d{6})(?!\d)').firstMatch(text ?? '')?.group(1);

  static Future<bool> hasPermission() async => (await _ch.invokeMethod<bool>('hasSendPermission')) ?? false;

  /// Sends [code] to [phone10] from this phone and waits for it to come back. Returns the code read back.
  /// Throws [SelfSmsError] with a translation key.
  static Future<String> verifyOwnSim(String phone10, String code) async {
    if (!((await _ch.invokeMethod<bool>('canSendSms')) ?? false)) throw SelfSmsError('self_unsupported');
    if (!((await _ch.invokeMethod<bool>('requestSendPermission')) ?? false)) throw SelfSmsError('self_perm_denied');
    final hash = await _ch.invokeMethod<String>('appHash');
    try {
      // listen first, then send, so the message can't arrive before we are listening
      await _ch.invokeMethod('startListening');
    } on PlatformException {
      throw SelfSmsError('self_unsupported');
    }
    try {
      await _ch.invokeMethod('send', {'to': '+91$phone10', 'text': message(code, hash)});
    } on PlatformException {
      await _ch.invokeMethod('stopListening');
      throw SelfSmsError('self_send_failed');
    }
    final text = await _ch.invokeMethod<String>('awaitSms', {'timeoutSeconds': waitSeconds});
    await _ch.invokeMethod('stopListening');
    final got = extractCode(text);
    if (got == null || got != code) throw SelfSmsError('self_timeout');
    return got;
  }
}
