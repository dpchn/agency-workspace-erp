import 'dart:convert';

import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api.dart';

/// Check-in/out punches recorded while offline. Each carries a device-generated UUID, so
/// uploading twice is harmless (the server de-duplicates). States: PENDING -> ACCEPTED/FLAGGED/REJECTED.
class QueuedPunch {
  final Map<String, dynamic> event;
  String state;
  String? message;
  QueuedPunch(this.event, {this.state = 'PENDING', this.message});

  String get id => '${event['clientEventId']}';
  String get kind => '${event['kind']}';
  DateTime get at => DateTime.parse('${event['occurredAt']}').toLocal();

  Map<String, dynamic> toJson() => {'event': event, 'state': state, 'message': message};
  static QueuedPunch fromJson(Map<String, dynamic> j) =>
      QueuedPunch(Map<String, dynamic>.from(j['event']), state: j['state'] ?? 'PENDING', message: j['message']);
}

class OfflineQueue extends ChangeNotifier {
  final Api api;
  final List<QueuedPunch> items = [];
  bool syncing = false;
  OfflineQueue(this.api);

  int get pending => items.where((e) => e.state == 'PENDING').length;

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString('punch_queue');
    if (raw == null) return;
    items
      ..clear()
      ..addAll((jsonDecode(raw) as List).map((e) => QueuedPunch.fromJson(Map<String, dynamic>.from(e))));
  }

  Future<void> _save() async {
    final p = await SharedPreferences.getInstance();
    final keep = items.length > 50 ? items.sublist(items.length - 50) : items;
    await p.setString('punch_queue', jsonEncode(keep.map((e) => e.toJson()).toList()));
    notifyListeners();
  }

  Future<void> add(Map<String, dynamic> event) async {
    items.add(QueuedPunch(event));
    await _save();
  }

  /// Uploads all pending punches in one batch. Returns number synced; throws on network failure.
  Future<int> sync() async {
    final pend = items.where((e) => e.state == 'PENDING').toList();
    if (pend.isEmpty || syncing) return 0;
    syncing = true;
    notifyListeners();
    try {
      final res = await api.post('/v1/attendance/events/batch', pend.map((e) => e.event).toList()) as List;
      for (final r in res) {
        final m = Map<String, dynamic>.from(r);
        for (final q in pend.where((q) => q.id == '${m['clientEventId']}')) {
          q.state = '${m['status']}';
          final flags = (m['flags'] as List?)?.join(', ');
          q.message = m['message'] ?? (flags == null || flags.isEmpty ? null : flags);
        }
      }
      await _save();
      return res.length;
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
