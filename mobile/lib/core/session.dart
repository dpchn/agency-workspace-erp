import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../config/brand.dart';
import 'action_queue.dart';
import 'api.dart';
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

  Future<void> login(String server, String login, String password) async {
    api.baseUrl = server.trim();
    api.token = null;
    final res = await api.post('/v1/auth/login', {'login': login.trim(), 'password': password});
    api.token = res['accessToken'] as String;
    me = Map<String, dynamic>.from(await api.get('/v1/me'));
    final p = await SharedPreferences.getInstance();
    await p.setString('server', api.baseUrl);
    await p.setString('token', api.token!);
    await p.setString('me', _encode(me!));
    // lets the signed-in person leave kiosk mode later, even offline, without sending the password anywhere
    await p.setString('unlock', unlockHash(login, password));
    await p.setString('unlock_login', login);
    notifyListeners();
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
