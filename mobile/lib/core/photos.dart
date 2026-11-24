import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:image_picker/image_picker.dart';
import 'package:material_ui/material_ui.dart';

import '../screens/camera_screen.dart';
import 'api.dart';
import 'nav.dart';

/// A compressed photo taken in the app (selfie, team photo), kept in memory until uploaded.
class Photo {
  final Uint8List bytes;
  final DateTime takenAt;
  Photo(this.bytes, this.takenAt);

  String get sha256Hex => sha256.convert(bytes).toString();
  String get base64 => base64Encode(bytes);
}

/// Camera capture and upload of attendance photo evidence (POST /v1/attendance/photos).
class Photos {
  /// Opens the in-app camera, which takes the picture by itself after a countdown (no shutter, no confirm).
  /// Front camera for selfies / kiosk, rear for team photos. Null if cancelled. Falls back to the phone's
  /// camera app when the in-app camera can't start (no camera, permission refused, old device).
  static Future<Photo?> capture({bool front = true}) async {
    final nav = appNavigator.currentState;
    if (nav != null) {
      final r = await nav.push<Object?>(MaterialPageRoute(fullscreenDialog: true, builder: (_) => CameraScreen(front: front)));
      if (r is Uint8List) return Photo(r, DateTime.now());
      if (r is! CameraUnavailable) return null; // cancelled
    }
    return _pick(front);
  }

  static Future<Photo?> _pick(bool front) async {
    final x = await ImagePicker().pickImage(
      source: ImageSource.camera,
      preferredCameraDevice: front ? CameraDevice.front : CameraDevice.rear,
      imageQuality: 60,
      maxWidth: front ? 800 : 1280,
    );
    if (x == null) return null;
    return Photo(await x.readAsBytes(), DateTime.now());
  }

  /// Body for the upload endpoint; also what the offline queue stores until the phone is back online.
  static Map<String, dynamic> uploadBody(String base64Data, String kind, DateTime takenAt, {String? siteId, String? employeeId}) => {
        'kind': kind,
        'contentType': 'image/jpeg',
        'dataBase64': base64Data,
        'siteId': ?siteId,
        'employeeId': ?employeeId,
        'takenAt': takenAt.toUtc().toIso8601String(),
      };

  /// Uploads and returns the photo id. Throws ApiException (isNetwork when offline).
  static Future<String> upload(Api api, Map<String, dynamic> body) async {
    final r = Map<String, dynamic>.from(await api.post('/v1/attendance/photos', body));
    return '${r['id']}';
  }
}
