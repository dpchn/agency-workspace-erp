import 'action_queue.dart';
import 'api.dart';
import 'photos.dart';
import 'punch.dart';
import 'session.dart';

/// Shared-device attendance for workers without a phone: employee code (or tap on name) + PIN + photo.
class Kiosk {
  /// Which punch a tap on a duty means: in if not yet in, out if in but not out, null when the duty is done.
  static String? nextKind(Map<String, dynamic> duty) {
    if (duty['checkInAt'] == null) return 'CHECK_IN';
    if (duty['checkOutAt'] == null) return 'CHECK_OUT';
    return null;
  }

  /// A PIN is 4 to 6 digits.
  static bool validPin(String pin) => RegExp(r'^\d{4,6}$').hasMatch(pin);

  static Map<String, dynamic> punchBody({
    required String clientEventId,
    required String kind,
    required String pin,
    required DateTime at,
    String? employeeId,
    String? employeeCode,
    Map<String, dynamic> location = const {},
    String? deviceId,
    String? photoId,
    bool offline = false,
  }) =>
      {
        'clientEventId': clientEventId,
        'employeeId': ?employeeId,
        if (employeeId == null) 'employeeCode': ?employeeCode,
        'pin': pin,
        'kind': kind,
        'occurredAt': at.toUtc().toIso8601String(),
        ...location,
        'deviceId': ?deviceId,
        'photoId': ?photoId,
        if (offline) 'offline': true,
      };

  /// Sends one kiosk punch. Without network, the punch and its photo are queued on the device.
  static Future<PunchResult> punch(Session s,
      {required String kind, required String pin, String? employeeId, String? employeeCode, String? siteId, Photo? photo, String label = ''}) async {
    final pos = await Punch.location();
    final id = Api.newId();
    final at = DateTime.now();
    final photoBody = photo == null ? null : Photos.uploadBody(photo.base64, 'SELFIE', photo.takenAt, siteId: siteId, employeeId: employeeId);
    final device = await s.deviceId();
    Future<PunchResult> queue() async {
      final body = punchBody(
          clientEventId: id, kind: kind, pin: pin, at: at, employeeId: employeeId, employeeCode: employeeCode,
          location: Punch.locationFields(pos), deviceId: device, offline: true);
      await s.actions.add(QueuedAction('KIOSK', body, photo: photoBody, label: label));
      return PunchResult('QUEUED', 'No network. Saved on this device; it will be sent automatically.', const []);
    }

    String? photoId;
    try {
      if (photoBody != null) photoId = await Photos.upload(s.api, photoBody);
    } on ApiException catch (e) {
      if (e.isNetwork) return queue();
      rethrow;
    }
    final body = punchBody(
        clientEventId: id, kind: kind, pin: pin, at: at, employeeId: employeeId, employeeCode: employeeCode,
        location: Punch.locationFields(pos), deviceId: device, photoId: photoId);
    try {
      return PunchResult.fromServer(Map<String, dynamic>.from(await s.api.post('/v1/attendance/kiosk/punch', body, true, id)));
    } on ApiException catch (e) {
      if (e.isNetwork) return queue();
      rethrow;
    }
  }
}
