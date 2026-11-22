import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:uuid/uuid.dart';

/// Error envelope from the backend: {code, message, fieldErrors}.
class ApiException implements Exception {
  final int status;
  final String code;
  final String message;
  final Map<String, dynamic>? fieldErrors;
  ApiException(this.status, this.code, this.message, [this.fieldErrors]);

  /// True when the request never reached the server (no network / server down).
  bool get isNetwork => status == 0;

  @override
  String toString() {
    final f = fieldErrors?.values.join(', ');
    return f == null || f.isEmpty ? message : '$message: $f';
  }
}

class Api {
  String baseUrl;
  String? token;
  final http.Client _client = http.Client();
  static const _uuid = Uuid();

  Api(this.baseUrl);

  static String newId() => _uuid.v4();

  Uri _uri(String path) {
    final b = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
    return Uri.parse('$b$path');
  }

  Map<String, String> _headers({String? idempotencyKey}) => {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        if (token != null) 'Authorization': 'Bearer $token',
        'Idempotency-Key': ?idempotencyKey,
      };

  Future<dynamic> get(String path) => _send(() => _client.get(_uri(path), headers: _headers()));

  /// [idempotent]: sends an Idempotency-Key (required by approvals, check-ins, assignments...).
  Future<dynamic> post(String path, [Object? body, bool idempotent = false, String? key]) => _send(() => _client.post(
        _uri(path),
        headers: _headers(idempotencyKey: idempotent ? (key ?? newId()) : null),
        body: jsonEncode(body ?? {}),
      ));

  Future<dynamic> _send(Future<http.Response> Function() call) async {
    http.Response r;
    try {
      r = await call().timeout(const Duration(seconds: 20));
    } on SocketException catch (e) {
      throw ApiException(0, 'NETWORK', 'No connection to server (${e.osError?.message ?? e.message})');
    } on TimeoutException {
      throw ApiException(0, 'NETWORK', 'Server did not respond');
    } on http.ClientException catch (e) {
      throw ApiException(0, 'NETWORK', 'Network error: ${e.message}');
    } on ArgumentError catch (e) {
      throw ApiException(0, 'BAD_URL', 'Invalid server address: ${e.message}');
    }
    final text = utf8.decode(r.bodyBytes);
    dynamic data;
    if (text.isNotEmpty && (text.startsWith('{') || text.startsWith('['))) data = jsonDecode(text);
    if (r.statusCode >= 400) {
      if (data is Map) {
        throw ApiException(r.statusCode, '${data['code'] ?? 'ERROR'}', '${data['message'] ?? 'Request failed'}',
            data['fieldErrors'] is Map ? Map<String, dynamic>.from(data['fieldErrors']) : null);
      }
      throw ApiException(r.statusCode, 'HTTP_${r.statusCode}', r.statusCode == 401 ? 'Session expired, please sign in again' : 'Request failed (${r.statusCode})');
    }
    return data ?? text;
  }
}
