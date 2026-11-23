import 'dart:convert';

import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api.dart';
import 'photos.dart';

/// Attendance taken for OTHER people while offline: kiosk punches (worker code + PIN) and supervisor
/// roll-calls. Each item carries its photo (base64) until uploaded, then a device-generated id makes
/// every retry safe (the server de-duplicates).
class QueuedAction {
  final String type; // KIOSK | ROLL_CALL
  final Map<String, dynamic> payload;
  Map<String, dynamic>? photo; // upload body, dropped once uploaded
  String state; // PENDING | ACCEPTED | FLAGGED | REJECTED | DONE
  String? message;
  final String label;

  QueuedAction(this.type, this.payload, {this.photo, this.state = 'PENDING', this.message, this.label = ''});

  String get id => '${payload['clientEventId'] ?? payload['batchId']}';
  DateTime get at => DateTime.parse('${payload['occurredAt']}').toLocal();

  Map<String, dynamic> toJson() => {'type': type, 'payload': payload, 'photo': photo, 'state': state, 'message': message, 'label': label};
  static QueuedAction fromJson(Map<String, dynamic> j) => QueuedAction('${j['type']}', Map<String, dynamic>.from(j['payload']),
      photo: j['photo'] == null ? null : Map<String, dynamic>.from(j['photo']),
      state: j['state'] ?? 'PENDING',
      message: j['message'],
      label: j['label'] ?? '');
}

class ActionQueue extends ChangeNotifier {
  final Api api;
  final List<QueuedAction> items = [];
  bool syncing = false;
  ActionQueue(this.api);

  static const _key = 'action_queue';
  int get pending => items.where((e) => e.state == 'PENDING').length;

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString(_key);
    if (raw == null) return;
    items
      ..clear()
      ..addAll((jsonDecode(raw) as List).map((e) => QueuedAction.fromJson(Map<String, dynamic>.from(e))));
  }

  Future<void> _save() async {
    final p = await SharedPreferences.getInstance();
    // never drop unsynced work; keep at most 30 finished ones for the history list
    final done = items.where((e) => e.state != 'PENDING').toList();
    if (done.length > 30) items.removeWhere((e) => done.take(done.length - 30).contains(e));
    await p.setString(_key, jsonEncode(items.map((e) => e.toJson()).toList()));
    notifyListeners();
  }

  Future<void> add(QueuedAction a) async {
    items.add(a);
    await _save();
  }

  /// Uploads pending photos, then the punches (one batch) and roll-calls. Throws on network failure;
  /// whatever was already sent stays marked, so the next sync continues where this one stopped.
  Future<int> sync() async {
    final pend = items.where((e) => e.state == 'PENDING').toList();
    if (pend.isEmpty || syncing) return 0;
    syncing = true;
    notifyListeners();
    var n = 0;
    try {
      for (final a in pend) {
        if (a.photo == null) continue;
        try {
          a.payload['photoId'] = await Photos.upload(api, a.photo!);
        } on ApiException catch (e) {
          if (e.isNetwork) rethrow;
          a.state = 'REJECTED';
          a.message = 'Photo not accepted: $e';
        }
        a.photo = null;
        await _save();
      }
      final kiosk = pend.where((a) => a.type == 'KIOSK' && a.state == 'PENDING').toList();
      if (kiosk.isNotEmpty) {
        final res = await api.post('/v1/attendance/kiosk/punch/batch', kiosk.map((a) => a.payload).toList()) as List;
        for (final r in res) {
          final m = Map<String, dynamic>.from(r);
          for (final a in kiosk.where((a) => a.id == '${m['clientEventId']}')) {
            a.state = '${m['status']}';
            final flags = (m['flags'] as List?)?.join(', ');
            a.message = m['message'] ?? (flags == null || flags.isEmpty ? null : flags);
            n++;
          }
        }
        // a punch the server could not even parse comes back without its id: never leave it pending forever
        for (final a in kiosk.where((a) => a.state == 'PENDING')) {
          a.state = 'REJECTED';
          a.message ??= 'Not accepted by server';
        }
        await _save();
      }
      for (final a in pend.where((a) => a.type == 'ROLL_CALL' && a.state == 'PENDING')) {
        try {
          final r = Map<String, dynamic>.from(await api.post('/v1/attendance/roll-call', a.payload, true, a.id));
          a.state = (r['rejected'] ?? 0) == 0 ? 'ACCEPTED' : 'FLAGGED';
          a.message = '${r['marked']} marked${(r['rejected'] ?? 0) > 0 ? ', ${r['rejected']} rejected' : ''}';
        } on ApiException catch (e) {
          if (e.isNetwork) rethrow;
          a.state = 'REJECTED';
          a.message = e.toString();
        }
        n++;
        await _save();
      }
      return n;
    } finally {
      syncing = false;
      notifyListeners();
    }
  }

  Future<void> clearDone() async {
    items.removeWhere((e) => e.state != 'PENDING');
    await _save();
  }
}
