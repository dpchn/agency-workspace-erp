import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../config/brand.dart';
import 'action_queue.dart';
import 'api.dart';
import 'otp.dart';
import 'offline_queue.dart';

/// Login state, current user (GET /v1/me) and tenant branding.
class Session extends ChangeNotifier {
  late final Api api;
  late final OfflineQueue queue;
  /// Kiosk punches and roll-calls for other people, recorded while offline.
  late final ActionQueue actions;
  Map<String, dynamic>? me;
  bool ready = false;

  Future<void> init() async {
    final p = await SharedPreferences.getInstance();
    api = Api(p.getString('server') ?? Brand.defaultServer);
    api.token = p.getString('token');
    queue = OfflineQueue(api);
    await queue.load();
    actions = ActionQueue(api);
    await actions.load();
    if (api.token != null) {
      try {
        me = Map<String, dynamic>.from(await api.get('/v1/me'));
      } on ApiException catch (e) {
        if (!e.isNetwork) await _clearToken();
        // offline: keep the token, show cached profile if any
        final cached = p.getString('me');
        if (e.isNetwork && cached != null) me = Map<String, dynamic>.from(_decode(cached));
      }
    }
    ready = true;
    notifyListeners();
  }

  /// Password sign-in (office staff with e-mail, or anyone who set a password). 30-day app session.
  Future<void> login(String server, String login, String password) async {
    api.baseUrl = server.trim();
    api.token = null;
    final res = await api.post('/v1/auth/login', {'login': login.trim(), 'password': password, 'mobile': true});
    await _signedIn(res, unlockLogin: login, unlockPassword: password, phone: Otp.normalizePhone(login));
  }

  /// Step 1 of mobile sign-in / password reset: the server texts a code (same answer for unknown numbers).
  /// In self-SMS mode the answer carries the code to send from this phone (see SelfSms).
  Future<Map<String, dynamic>> requestOtp(String server, String phone10, {String purpose = 'LOGIN'}) async {
    api.baseUrl = server.trim();
    api.token = null;
    final res = await api.post('/v1/auth/otp/request', {'phone': phone10, 'purpose': purpose});
    final p = await SharedPreferences.getInstance();
    await p.setString('server', api.baseUrl);
    await p.setString('last_phone', phone10);
    return res is Map ? Map<String, dynamic>.from(res) : <String, dynamic>{};
  }

  /// Step 2: the SMS code signs the worker in. No password is needed (or stored) for this.
  Future<void> loginWithOtp(String server, String phone10, String code) async {
    api.baseUrl = server.trim();
    api.token = null;
    final res = await api.post('/v1/auth/otp/verify', {'phone': phone10, 'code': code.trim(), 'mobile': true, 'deviceId': await deviceId()});
    await _signedIn(res, phone: phone10);
  }

  /// Forgot password: SMS code + new password; signs in (and signs out every other device).
  Future<void> resetPassword(String server, String phone10, String code, String newPassword) async {
    api.baseUrl = server.trim();
    api.token = null;
    final res = await api.post('/v1/auth/otp/reset-password',
        {'phone': phone10, 'code': code.trim(), 'newPassword': newPassword, 'mobile': true, 'deviceId': await deviceId()});
    await _signedIn(res, unlockLogin: phone10, unlockPassword: newPassword, phone: phone10);
  }

  /// Saves the session the same way for every kind of sign-in.
  Future<void> _signedIn(dynamic res, {String? unlockLogin, String? unlockPassword, String? phone}) async {
    api.token = res['accessToken'] as String;
    me = Map<String, dynamic>.from(await api.get('/v1/me'));
    final p = await SharedPreferences.getInstance();
    await p.setString('server', api.baseUrl);
    await p.setString('token', api.token!);
    await p.setString('me', _encode(me!));
    // lets the signed-in person leave kiosk mode later, even offline, without sending the password anywhere
    if (unlockLogin != null && unlockPassword != null) {
      await p.setString('unlock', unlockHash(unlockLogin, unlockPassword));
      await p.setString('unlock_login', unlockLogin);
    } else {
      await p.remove('unlock');
      await p.remove('unlock_login');
    }
    // the number SMS codes go to (kiosk exit without a password); from the sign-in or the profile
    final ph = phone ?? Otp.normalizePhone('${me!['phone'] ?? ''}');
    if (ph != null) {
      await p.setString('unlock_phone', ph);
      await p.setString('last_phone', ph);
    } else {
      await p.remove('unlock_phone');
    }
    notifyListeners();
  }

  /// Last mobile number used on this phone (prefilled on the sign-in screen).
  Future<String?> lastPhone() async => (await SharedPreferences.getInstance()).getString('last_phone');

  /// Whether this person can unlock with a password typed on the phone (they signed in with one).
  Future<bool> hasUnlockPassword() async => (await SharedPreferences.getInstance()).getString('unlock') != null;

  /// Number an unlock code can be texted to, if any.
  Future<String?> unlockPhone() async => (await SharedPreferences.getInstance()).getString('unlock_phone');

  /// Kiosk exit for people who signed in by SMS code: text a fresh code to their own number.
  /// In self-SMS mode the answer carries the code to send from this phone (see SelfSms).
  Future<Map<String, dynamic>> sendUnlockCode() async {
    final ph = await unlockPhone();
    if (ph == null) throw ApiException(400, 'NO_PHONE', 'No mobile number on this account');
    final res = await api.post('/v1/auth/otp/request', {'phone': ph, 'purpose': 'LOGIN'});
    return res is Map ? Map<String, dynamic>.from(res) : <String, dynamic>{};
  }

  /// True if [code] is a valid code for the signed-in person's own number. Refreshes the session token.
  Future<bool> checkOwnCode(String code) async {
    final ph = await unlockPhone();
    if (ph == null || !Otp.codeComplete(code)) return false;
    try {
      final res = await api.post('/v1/auth/otp/verify', {'phone': ph, 'code': code.trim(), 'mobile': true, 'deviceId': await deviceId()});
      if (me?['userId'] != null && res['userId'] != me!['userId']) return false;
      api.token = res['accessToken'] as String;
      final p = await SharedPreferences.getInstance();
      await p.setString('token', api.token!);
      return true;
    } on ApiException catch (e) {
      if (e.isNetwork) rethrow;
      return false;
    }
  }

  static String unlockHash(String login, String password) => sha256.convert(utf8.encode('${login.trim().toLowerCase()}\n$password')).toString();

  /// True if [password] is the password of the person who signed in on this phone.
  Future<bool> checkOwnPassword(String password) async {
    final p = await SharedPreferences.getInstance();
    final h = p.getString('unlock');
    return h != null && h == unlockHash(p.getString('unlock_login') ?? '', password);
  }

  /// Last kiosk duty list, so a site device without network can still show who is on duty.
  Future<void> cacheKioskDuties(Map<String, dynamic> data) async {
    final p = await SharedPreferences.getInstance();
    await p.setString('kiosk_duties', jsonEncode(data));
  }

  Future<Map<String, dynamic>?> cachedKioskDuties() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString('kiosk_duties');
    return raw == null ? null : Map<String, dynamic>.from(jsonDecode(raw));
  }

  Future<void> logout() async {
    await _clearToken();
    me = null;
    notifyListeners();
  }

  Future<void> _clearToken() async {
    api.token = null;
    final p = await SharedPreferences.getInstance();
    await p.remove('token');
    await p.remove('me');
    await p.remove('unlock');
    await p.remove('unlock_login');
    await p.remove('unlock_phone');
    await p.remove('kiosk_duties');
  }

  /// Stable per-install device id (used for device-sharing checks on the server).
  Future<String> deviceId() async {
    final p = await SharedPreferences.getInstance();
    var d = p.getString('device_id');
    if (d == null) {
      d = 'app-${Api.newId().substring(0, 8)}';
      await p.setString('device_id', d);
    }
    return d;
  }

  // ---------------------------------------------------------------- helpers
  String get role => '${me?['role'] ?? ''}';
  String get userName => '${me?['name'] ?? ''}';
  String? get employeeId => me?['employeeId'] as String?;
  List<String> get permissions => ((me?['permissions'] as List?) ?? const []).map((e) => '$e').toList();
  bool can(String perm) => permissions.contains(perm);

  /// Supervisors / operations / owners get the team screens; guards get self-service only.
  bool get isSupervisor => can('ROSTER_READ') || can('ATTENDANCE_MARK');

  /// A shared device fixed at a site (gate tablet): the app opens straight into kiosk mode.
  bool get isSiteKiosk => role == 'SITE_KIOSK';

  /// Authorization header for images served by the API (profile photos).
  Map<String, String> get authHeaders => {if (api.token != null) 'Authorization': 'Bearer ${api.token}'};
  String photoUrl(String id) => '${api.baseUrl.endsWith('/') ? api.baseUrl.substring(0, api.baseUrl.length - 1) : api.baseUrl}/v1/attendance/photos/$id';

  Map<String, dynamic> get _branding => Map<String, dynamic>.from((me?['tenant']?['branding'] as Map?) ?? const {});
  String get orgName => '${me?['tenant']?['name'] ?? Brand.name}';
  Color get primary => parseHexColor(_branding['primaryColor']) ?? Brand.primary;
  String? get logoUrl {
    final l = _branding['logoUrl'];
    if (l is! String || l.isEmpty) return null;
    return l.startsWith('http') ? l : '${api.baseUrl}$l';
  }

  static String _encode(Map<String, dynamic> m) => jsonEncode(m);
  static dynamic _decode(String s) => jsonDecode(s);
}
