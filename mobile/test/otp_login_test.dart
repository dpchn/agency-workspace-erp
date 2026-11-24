import 'package:dutygrid_mobile/core/api.dart';
import 'package:dutygrid_mobile/core/l10n.dart';
import 'package:dutygrid_mobile/core/otp.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('mobile number', () {
    test('accepts the usual ways of writing an Indian mobile number', () {
      for (final v in ['9845100010', '+91 98451 00010', '919845100010', '098451-00010', ' 98451 00010 ']) {
        expect(Otp.normalizePhone(v), '9845100010', reason: v);
      }
    });
    test('rejects things that are not a mobile number', () {
      for (final v in ['', '12345', '0845100010', '1234567890', 'abc', '98451000101234', null]) {
        expect(Otp.normalizePhone(v), isNull, reason: '$v');
      }
    });
    test('is shown back with the country code', () => expect(Otp.display('9845100010'), '+91 98451 00010'));
  });

  test('the code signs in by itself only when exactly 6 digits are typed', () {
    expect(Otp.codeComplete('12345'), isFalse);
    expect(Otp.codeComplete('123456'), isTrue);
    expect(Otp.codeComplete('12345a'), isFalse);
    expect(Otp.codeComplete('1234567'), isFalse);
  });

  test('server errors become plain words that exist in both languages', () {
    final cases = {
      ApiException(400, 'VALIDATION_FAILED', 'x', {'code': 'Wrong code'}): 'otp_wrong',
      ApiException(400, 'VALIDATION_FAILED', 'x', {'code': 'Code expired. Ask for a new one'}): 'otp_expired',
      ApiException(400, 'VALIDATION_FAILED', 'x', {'code': 'Too many wrong tries. Ask for a new code'}): 'otp_too_many_tries',
      ApiException(400, 'VALIDATION_FAILED', 'x', {'phone': 'Enter your 10-digit mobile number'}): 'phone_invalid',
      ApiException(429, 'OTP_LIMIT', 'x'): 'otp_limit',
      ApiException(429, 'OTP_WAIT', 'x'): 'otp_wait',
      ApiException(403, 'FORBIDDEN', 'This number has no active account'): 'otp_no_account',
      ApiException(0, 'NETWORK', 'x'): 'no_network',
    };
    cases.forEach((e, key) {
      expect(Otp.errorKey(e), key);
      for (final lang in L10n.languages.keys) {
        expect(L10n.strings[lang]!.containsKey(key), isTrue, reason: '$lang.$key');
      }
    });
    expect(Otp.errorKey(Exception('other')), isNull);
  });

  test('every sign-in string exists in English and Hindi', () {
    const keys = ['phone_label', 'send_code', 'code_label', 'code_sent_to', 'resend_in', 'resend', 'change_number', 'use_password',
      'use_otp', 'forgot_password', 'reset_title', 'new_password', 'new_password_again', 'passwords_differ', 'password_short',
      'save_and_sign_in', 'kiosk_send_code', 'kiosk_use_password', 'kiosk_code_hint'];
    for (final lang in ['en', 'hi']) {
      for (final k in keys) {
        expect(L10n.strings[lang]![k], isNotNull, reason: '$lang.$k');
      }
    }
  });
}
