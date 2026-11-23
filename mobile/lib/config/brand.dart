import 'package:material_ui/material_ui.dart';

/// Product branding: the ONLY place the app's name, colour and default server live.
/// Override at build time, e.g.:
///   flutter build apk --dart-define=BRAND_NAME=Rakshak --dart-define=BRAND_COLOR=0xFF0F766E \
///                     --dart-define=API_URL=https://erp.example.com
/// The Android launcher label is in android/app/src/main/res/values/strings.xml (app_name).
/// After login, the agency's own name and colour (Settings -> Branding on the web) are used instead.
class Brand {
  static const name = String.fromEnvironment('BRAND_NAME', defaultValue: 'DutyGrid');
  static const tagline = String.fromEnvironment('BRAND_TAGLINE', defaultValue: 'Guard & supervisor app');
  static const _color = int.fromEnvironment('BRAND_COLOR', defaultValue: 0xFF1D4ED8);
  static const primary = Color(_color);

  /// Default server (web app URL; it proxies /v1 to the backend). Users can change it on the login screen.
  static const defaultServer = String.fromEnvironment('API_URL', defaultValue: '');

  /// Ask for a selfie at check-in. Online, the photo is uploaded as evidence; offline, only its SHA-256 hash is sent.
  static const selfieAtCheckIn = bool.fromEnvironment('SELFIE_AT_CHECKIN', defaultValue: false);
}

/// Parses "#1d4ed8" style colours coming from tenant branding.
Color? parseHexColor(Object? v) {
  if (v is! String) return null;
  final s = v.replaceAll('#', '').trim();
  if (s.length != 6) return null;
  final n = int.tryParse(s, radix: 16);
  return n == null ? null : Color(0xFF000000 | n);
}
