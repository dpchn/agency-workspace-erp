import 'package:dutygrid_mobile/core/action_queue.dart';
import 'package:dutygrid_mobile/core/kiosk.dart';
import 'package:dutygrid_mobile/core/photos.dart';
import 'package:dutygrid_mobile/core/session.dart';
import 'package:dutygrid_mobile/screens/supervisor/roll_call_tab.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('kiosk picks check-in, then check-out, then nothing', () {
    expect(Kiosk.nextKind({}), 'CHECK_IN');
    expect(Kiosk.nextKind({'checkInAt': '2026-11-22T02:30:00Z'}), 'CHECK_OUT');
    expect(Kiosk.nextKind({'checkInAt': '2026-11-22T02:30:00Z', 'checkOutAt': '2026-11-22T14:30:00Z'}), isNull);
  });

  test('kiosk PIN is 4 to 6 digits', () {
    expect(Kiosk.validPin('4826'), isTrue);
    expect(Kiosk.validPin('482619'), isTrue);
    expect(Kiosk.validPin('482'), isFalse);
    expect(Kiosk.validPin('4826190'), isFalse);
    expect(Kiosk.validPin('48a6'), isFalse);
  });

  test('kiosk punch body uses employee id when known, else the typed code', () {
    final at = DateTime.utc(2026, 11, 22, 2, 30);
    final byId = Kiosk.punchBody(clientEventId: 'e1', kind: 'CHECK_IN', pin: '4826', at: at, employeeId: 'emp', employeeCode: 'G1');
    expect(byId['employeeId'], 'emp');
    expect(byId.containsKey('employeeCode'), isFalse);
    expect(byId.containsKey('photoId'), isFalse);
    final byCode = Kiosk.punchBody(
        clientEventId: 'e2', kind: 'CHECK_OUT', pin: '4826', at: at, employeeCode: 'G1', photoId: 'p', offline: true,
        location: {'latitude': 19.0, 'longitude': 72.8, 'accuracyM': 12.0});
    expect(byCode['employeeCode'], 'G1');
    expect(byCode['photoId'], 'p');
    expect(byCode['offline'], isTrue);
    expect(byCode['latitude'], 19.0);
    expect(byCode['occurredAt'], '2026-11-22T02:30:00.000Z');
  });

  test('roll-call only offers people who can be marked', () {
    expect(rollCallEligible({}, 'CHECK_IN'), isTrue);
    expect(rollCallEligible({}, 'CHECK_OUT'), isFalse);
    // not marked present for a duty that only starts tonight
    final night = {'startAt': '2026-11-24T14:30:00Z', 'endAt': '2026-11-25T02:30:00Z'};
    expect(rollCallEligible(night, 'CHECK_IN', now: DateTime.utc(2026, 11, 24, 8, 21)), isFalse);
    expect(rollCallEligible(night, 'CHECK_IN', now: DateTime.utc(2026, 11, 24, 13, 0)), isTrue);
    expect(rollCallEligible({'checkInAt': 'x'}, 'CHECK_IN'), isFalse);
    expect(rollCallEligible({'checkInAt': 'x'}, 'CHECK_OUT'), isTrue);
    expect(rollCallEligible({'checkInAt': 'x', 'checkOutAt': 'y'}, 'CHECK_OUT'), isFalse);
    expect(rollCallEligible({'pendingCorrection': true}, 'CHECK_IN'), isFalse);
    expect(rollCallEligible({'assignmentStatus': 'CANCELLED'}, 'CHECK_IN'), isFalse);
  });

  test('photo upload body carries kind, site and employee', () {
    final b = Photos.uploadBody('AAAA', 'TEAM', DateTime.utc(2026, 11, 22), siteId: 's1');
    expect(b['kind'], 'TEAM');
    expect(b['contentType'], 'image/jpeg');
    expect(b['siteId'], 's1');
    expect(b.containsKey('employeeId'), isFalse);
  });

  test('queued kiosk punches and roll-calls survive an app restart', () async {
    SharedPreferences.setMockInitialValues({});
    final s = Session();
    await s.init();
    await s.actions.add(QueuedAction('KIOSK', {'clientEventId': 'k1', 'occurredAt': '2026-11-22T02:30:00Z'}, photo: {'dataBase64': 'AAAA'}, label: 'G1'));
    await s.actions.add(QueuedAction('ROLL_CALL', {'batchId': 'b1', 'occurredAt': '2026-11-22T02:31:00Z'}, label: 'IN · Site'));
    final again = Session();
    await again.init();
    expect(again.actions.pending, 2);
    expect(again.actions.items.first.id, 'k1');
    expect(again.actions.items.first.photo?['dataBase64'], 'AAAA');
    expect(again.actions.items.last.id, 'b1');
  });

  test('kiosk exit password is checked locally against the signed-in login', () async {
    SharedPreferences.setMockInitialValues({
      'unlock': Session.unlockHash('ops.demo@shield.test', 'Passw0rd!'),
      'unlock_login': 'ops.demo@shield.test',
    });
    final s = Session();
    await s.init();
    expect(await s.checkOwnPassword('Passw0rd!'), isTrue);
    expect(await s.checkOwnPassword('wrong'), isFalse);
  });
}
