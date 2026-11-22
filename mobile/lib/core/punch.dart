import 'package:crypto/crypto.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';

import '../config/brand.dart';
import 'api.dart';
import 'session.dart';

/// Result shown to the guard after a check-in/out attempt.
class PunchResult {
  final String status; // ACCEPTED | FLAGGED | REJECTED | QUEUED
  final String? message;
  final List<String> flags;
  PunchResult(this.status, this.message, this.flags);
  bool get ok => status == 'ACCEPTED' || status == 'FLAGGED' || status == 'QUEUED';
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

  static Future<String?> selfieHash() async {
    if (!Brand.selfieAtCheckIn) return null;
    final photo = await ImagePicker().pickImage(source: ImageSource.camera, preferredCameraDevice: CameraDevice.front, imageQuality: 60);
    if (photo == null) return null;
    return sha256.convert(await photo.readAsBytes()).toString();
  }

  /// Check in/out. [employeeId] is set when a supervisor marks someone else.
  /// Online: sent immediately (Idempotency-Key = event id). Offline: queued on the phone and synced later.
  static Future<PunchResult> send(Session s, {required String kind, String? assignmentId, String? employeeId}) async {
    final pos = await location();
    final selfie = employeeId == null && kind == 'CHECK_IN' ? await selfieHash() : null;
    final id = Api.newId();
    final event = <String, dynamic>{
      'clientEventId': id,
      'kind': kind,
      'occurredAt': DateTime.now().toUtc().toIso8601String(),
      'assignmentId': ?assignmentId,
      'employeeId': ?employeeId,
      if (pos != null) ...{'latitude': pos.latitude, 'longitude': pos.longitude, 'accuracyM': pos.accuracy},
      'deviceId': await s.deviceId(),
      'selfieHash': ?selfie,
    };
    try {
      final r = Map<String, dynamic>.from(await s.api.post('/v1/attendance/events', event, true, id));
      return PunchResult('${r['status']}', r['message'] as String?, ((r['flags'] as List?) ?? const []).map((e) => '$e').toList());
    } on ApiException catch (e) {
      if (!e.isNetwork) rethrow;
      await s.queue.add({...event, 'offline': true});
      return PunchResult('QUEUED', 'No network. Saved on this phone and will sync automatically.', const []);
    }
  }
}
