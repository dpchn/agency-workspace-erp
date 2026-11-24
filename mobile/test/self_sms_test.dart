import 'package:dutygrid_mobile/core/api.dart';
import 'package:dutygrid_mobile/core/l10n.dart';
import 'package:dutygrid_mobile/core/otp.dart';
import 'package:dutygrid_mobile/core/self_sms.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('self-SMS mode only when the server sends a 6-digit selfSms code', () {
    expect(SelfSms.codeFrom({'sent': true, 'selfSms': {'code': '123456', 'message': 'x'}}), '123456');
    expect(SelfSms.codeFrom({'sent': true, 'expiresInSeconds': 300}), isNull); // gateway mode: type the code
    expect(SelfSms.codeFrom({'selfSms': {'code': '12a456'}}), isNull);
    expect(SelfSms.codeFrom(null), isNull);
  });

  test('SMS text carries the code and the app hash for SMS Retriever', () {
    expect(SelfSms.message('123456', 'AbCdEfGhIjK'), '<#> DutyGrid code: 123456\nAbCdEfGhIjK');
    expect(SelfSms.message('123456', null), '<#> DutyGrid code: 123456');
  });

  test('code is read back from the received SMS', () {
    expect(SelfSms.extractCode('<#> DutyGrid code: 654321\nAbCdEfGhIjK'), '654321');
    expect(SelfSms.extractCode('code 1234567 is too long'), isNull);
    expect(SelfSms.extractCode(null), isNull);
  });

  test('another phone for a bound number gets its own plain message', () {
    expect(Otp.errorKey(ApiException(403, 'NEW_PHONE', 'x')), 'otp_new_phone');
    expect(Otp.errorKey(ApiException(403, 'FORBIDDEN', 'x')), 'otp_no_account');
  });

  test('self-SMS strings exist in English and Hindi', () {
    const keys = ['otp_new_phone', 'self_title', 'self_explain', 'self_verify', 'self_checking', 'self_perm_denied',
        'self_send_failed', 'self_timeout', 'self_unsupported'];
    for (final lang in ['en', 'hi']) {
      for (final k in keys) {
        expect(L10n.strings[lang]![k], isNotNull, reason: '$lang.$k');
      }
    }
    expect(L10n.strings['hi']!['self_timeout'], contains('{phone}'));
  });
}
