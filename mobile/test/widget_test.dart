import 'package:dutygrid_mobile/config/brand.dart';
import 'package:dutygrid_mobile/core/api.dart';
import 'package:dutygrid_mobile/core/session.dart';
import 'package:dutygrid_mobile/screens/login_screen.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('tenant colour parsing', () {
    expect(parseHexColor('#0f766e'), const Color(0xFF0F766E));
    expect(parseHexColor('bad'), isNull);
    expect(parseHexColor(null), isNull);
  });

  test('api error message includes field errors', () {
    final e = ApiException(400, 'VALIDATION_FAILED', 'Request validation failed', {'gstin': 'GSTIN must be 15 characters'});
    expect(e.toString(), contains('GSTIN must be 15 characters'));
    expect(ApiException(0, 'NETWORK', 'x').isNetwork, isTrue);
  });

  testWidgets('login screen asks for server when none configured', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final s = Session();
    await s.init();
    await tester.pumpWidget(MaterialApp(home: LoginScreen(session: s)));
    expect(find.text('Sign in'), findsOneWidget);
    expect(find.text('Server address'), findsOneWidget);
    expect(find.text(Brand.name), findsOneWidget);
  });
}
