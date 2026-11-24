import 'api.dart';

/// Rules for signing in with a mobile number + SMS code, kept free of widgets so they can be tested.
class Otp {
  Otp._();

  static const codeLength = 6;
  static const resendSeconds = 30;

  /// Last 10 digits of an Indian mobile number ("+91 98451 00002", "098451 00002" -> "9845100002"),
  /// or null when it isn't a mobile number (10 digits starting with 6-9). Same rule as the server.
  static String? normalizePhone(String? input) {
    if (input == null) return null;
    var d = input.replaceAll(RegExp(r'\D'), '');
    if (d.length > 10 && (d.startsWith('91') || d.startsWith('0'))) d = d.substring(d.length - 10);
    return RegExp(r'^[6-9]\d{9}$').hasMatch(d) ? d : null;
  }

  /// "9845100002" -> "+91 98451 00002" for showing back to the worker.
  static String display(String phone10) => '+91 ${phone10.substring(0, 5)} ${phone10.substring(5)}';

  /// The code is complete (and may be sent by itself) once exactly 6 digits are typed.
  static bool codeComplete(String code) => RegExp(r'^\d{6}$').hasMatch(code.trim());

  /// Turns a server error into a plain-words translation key; null keeps the server's own message.
  static String? errorKey(Object e) {
    if (e is! ApiException) return null;
    if (e.isNetwork) return 'no_network';
    if (e.status == 403) return 'otp_no_account';
    if (e.code == 'OTP_LIMIT') return 'otp_limit';
    if (e.code == 'OTP_WAIT') return 'otp_wait';
    final f = e.fieldErrors ?? const {};
    final code = '${f['code'] ?? ''}'.toLowerCase();
    if (code.contains('too many')) return 'otp_too_many_tries';
    if (code.contains('expired')) return 'otp_expired';
    if (code.contains('wrong') || code.contains('6-digit')) return 'otp_wrong';
    if (f.containsKey('phone')) return 'phone_invalid';
    if (f.containsKey('newPassword')) return 'password_short';
    return null;
  }
}
