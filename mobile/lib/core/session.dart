import 'dart:convert';
import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../config/brand.dart';
import 'api.dart';
import 'offline_queue.dart';

/// Login state, current user (GET /v1/me) and tenant branding.
class Session extends ChangeNotifier {
  late final Api api;
  late final OfflineQueue queue;
  Map<String, dynamic>? me;
  bool ready = false;

  Future<void> init() async {
    final p = await SharedPreferences.getInstance();
    api = Api(p.getString('server') ?? Brand.defaultServer);
    api.token = p.getString('token');
    queue = OfflineQueue(api);
    await queue.load();
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
    notifyListeners();
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
