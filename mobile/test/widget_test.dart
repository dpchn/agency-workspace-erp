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
    // default is the mobile-number step; the password form is one tap away
    expect(find.text('Send code'), findsOneWidget);
    expect(find.text('Mobile number'), findsOneWidget);
    expect(find.text('Server address'), findsOneWidget);
    expect(find.text(Brand.name), findsOneWidget);
    await tester.tap(find.text('Sign in with e-mail / password'));
    await tester.pump();
    expect(find.text('Sign in'), findsOneWidget);
    expect(find.text('Forgot password?'), findsOneWidget);
  });

  testWidgets('a bad mobile number shows a plain error and sends nothing', (tester) async {
    SharedPreferences.setMockInitialValues({'server': 'http://localhost:1'});
    final s = Session();
    await s.init();
    await tester.pumpWidget(MaterialApp(home: LoginScreen(session: s)));
    await tester.enterText(find.byType(TextField).first, '12345');
    await tester.tap(find.text('Send code'));
    await tester.pump();
    expect(find.text('Enter your 10-digit mobile number'), findsOneWidget);
  });

  testWidgets('last mobile number is prefilled', (tester) async {
    SharedPreferences.setMockInitialValues({'server': 'http://localhost:1', 'last_phone': '9845100010'});
    final s = Session();
    await s.init();
    await tester.pumpWidget(MaterialApp(home: LoginScreen(session: s)));
    await tester.pump();
    expect(find.text('9845100010'), findsOneWidget);
  });
}
