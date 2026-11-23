import 'package:geolocator/geolocator.dart';

import '../config/brand.dart';
import 'api.dart';
import 'photos.dart';
import 'session.dart';

/// Result shown to the guard after a check-in/out attempt.
class PunchResult {
  final String status; // ACCEPTED | FLAGGED | REJECTED | QUEUED
  final String? message;
  final List<String> flags;
  PunchResult(this.status, this.message, this.flags);
  bool get ok => status == 'ACCEPTED' || status == 'FLAGGED' || status == 'QUEUED';

  static PunchResult fromServer(Map<String, dynamic> r) =>
      PunchResult('${r['status']}', r['message'] as String?, ((r['flags'] as List?) ?? const []).map((e) => '$e').toList());
}

class Punch {
  /// Best-effort GPS fix. Never blocks the punch: without location the server flags it for review.
  static Future<Position?> location() async {
    try {
      if (!await Geolocator.isLocationServiceEnabled()) return null;
      var perm = await Geolocator.checkPermission();
      if (perm == LocationPermission.denied) perm = await Geolocator.requestPermission();
      if (perm == LocationPermission.denied || perm == LocationPermission.deniedForever) return null;
      return await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.high, timeLimit: Duration(seconds: 15)),
      );
    } catch (_) {
      try {
        return await Geolocator.getLastKnownPosition();
      } catch (_) {
        return null;
      }
    }
  }

  static Map<String, dynamic> locationFields(Position? pos) =>
      pos == null ? const {} : {'latitude': pos.latitude, 'longitude': pos.longitude, 'accuracyM': pos.accuracy};

  /// Check in/out. [employeeId] is set when a supervisor marks someone else.
  /// Online: sent immediately (Idempotency-Key = event id). Offline: queued on the phone and synced later.
  /// With a selfie, the photo itself is uploaded as evidence when online; offline only its hash travels.
  static Future<PunchResult> send(Session s, {required String kind, String? assignmentId, String? employeeId}) async {
    final pos = await location();
    final selfie = employeeId == null && kind == 'CHECK_IN' && Brand.selfieAtCheckIn ? await Photos.capture(front: true) : null;
    String? photoId;
    if (selfie != null) {
      try {
        photoId = await Photos.upload(s.api, Photos.uploadBody(selfie.base64, 'SELFIE', selfie.takenAt, employeeId: s.employeeId));
      } on ApiException catch (e) {
        if (!e.isNetwork) rethrow;
      }
    }
    final id = Api.newId();
    final event = <String, dynamic>{
      'clientEventId': id,
      'kind': kind,
      'occurredAt': DateTime.now().toUtc().toIso8601String(),
      'assignmentId': ?assignmentId,
      'employeeId': ?employeeId,
      ...locationFields(pos),
      'deviceId': await s.deviceId(),
      if (selfie != null) 'selfieHash': selfie.sha256Hex,
      'photoId': ?photoId,
    };
    try {
      return PunchResult.fromServer(Map<String, dynamic>.from(await s.api.post('/v1/attendance/events', event, true, id)));
    } on ApiException catch (e) {
      if (!e.isNetwork) rethrow;
      await s.queue.add({...event, 'offline': true});
      return PunchResult('QUEUED', 'No network. Saved on this phone and will sync automatically.', const []);
    }
  }
}
