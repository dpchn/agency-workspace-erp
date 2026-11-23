// Runs against a live server: flutter test test/live_backend_test.dart --dart-define=SERVER=http://localhost:3000
// (skipped when SERVER is not given). Uses the demo data from `docker compose --profile demo up demo-seed`.
import 'dart:convert';

import 'package:dutygrid_mobile/core/api.dart';
import 'package:dutygrid_mobile/core/kiosk.dart';
import 'package:dutygrid_mobile/core/photos.dart';
import 'package:dutygrid_mobile/core/session.dart';
import 'package:dutygrid_mobile/screens/supervisor/roll_call_tab.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

const server = String.fromEnvironment('SERVER');

void main() {
  test('guard can sign in and load duties, attendance and payslips', () async {
    SharedPreferences.setMockInitialValues({});
    final s = Session();
    await s.init();
    await s.login(server, '8dem333333', 'guard123');
    expect(s.role, 'GUARD');
    expect(s.employeeId, isNotNull);
    expect(s.isSupervisor, isFalse);
    expect(await s.api.get('/v1/me/duties?from=2026-10-01&to=2026-10-31'), isNotEmpty);
    expect(await s.api.get('/v1/me/attendance?month=2026-10'), isNotEmpty);
    final slips = await s.api.get('/v1/me/payslips') as List;
    expect(slips, isNotEmpty);
    final slip = await s.api.get('/v1/payroll/payslips/${slips.first['id']}') as Map;
    expect((slip['lines'] as List).any((l) => l['component_code'] == 'DUTY_WAGE'), isTrue);
  }, skip: server.isEmpty ? 'set --dart-define=SERVER=...' : false);

  test('supervisor (operations) sees team screens data', () async {
    SharedPreferences.setMockInitialValues({});
    final s = Session();
    await s.init();
    await s.login(server, 'ops.demo@shield.test', 'Passw0rd!');
    expect(s.isSupervisor, isTrue);
    final roster = await s.api.get('/v1/roster?from=2026-10-10&to=2026-10-10') as List;
    expect(roster, isNotEmpty);
    final night = roster.firstWhere((v) => (v['assignments'] as List).any((a) => a['role'] == 'REPLACEMENT'));
    expect(night, isNotNull);
    final eligible = await s.api.get('/v1/shifts/${roster.first['shift']['id']}/eligible-employees');
    expect(eligible, isA<List>());
  }, skip: server.isEmpty ? 'set --dart-define=SERVER=...' : false);

  test('offline punches are queued then synced idempotently', () async {
    SharedPreferences.setMockInitialValues({});
    final s = Session();
    await s.init();
    await s.login(server, '8dem333333', 'guard123');
    final event = {
      'clientEventId': '11111111-2222-4333-8444-${DateTime.now().millisecondsSinceEpoch.toString().padLeft(12, '0').substring(0, 12)}',
      'kind': 'CHECK_OUT',
      'occurredAt': DateTime.now().toUtc().toIso8601String(),
      'offline': true,
    };
    await s.queue.add(event);
    expect(s.queue.pending, 1);
    await s.queue.sync();
    expect(s.queue.pending, 0);
    expect(s.queue.items.single.state, isNot('PENDING'));
    // replay is de-duplicated by the server
    final again = await s.api.post('/v1/attendance/events/batch', [event]) as List;
    expect(again.single['duplicate'], isTrue);
  }, skip: server.isEmpty ? 'set --dart-define=SERVER=...' : false);

  test('supervisor roll-call sheet and kiosk duty list load', () async {
    SharedPreferences.setMockInitialValues({});
    final s = Session();
    await s.init();
    await s.login(server, 'ops.demo@shield.test', 'Passw0rd!');
    expect(s.can('ATTENDANCE_KIOSK'), isTrue);
    final today = DateTime.now().toIso8601String().substring(0, 10);
    final kiosk = await s.api.get('/v1/attendance/kiosk/duties') as Map;
    expect(kiosk['duties'], isA<List>());
    final roster = await s.api.get('/v1/roster?from=$today&to=$today') as List;
    if (roster.isNotEmpty) {
      final sheet = await s.api.get('/v1/attendance/sheet?siteId=${roster.first['shift']['siteId']}&date=$today') as List;
      expect(sheet, isNotEmpty);
      expect(sheet.first.containsKey('assignmentId'), isTrue);
    }
  }, skip: server.isEmpty ? 'set --dart-define=SERVER=...' : false);

  test('kiosk: wrong PIN is refused, right PIN + photo punches; roll-call marks the team', () async {
    SharedPreferences.setMockInitialValues({});
    final s = Session();
    await s.init();
    await s.login(server, 'ops.demo@shield.test', 'Passw0rd!');
    final duties = ((await s.api.get('/v1/attendance/kiosk/duties') as Map)['duties'] as List).map((e) => Map<String, dynamic>.from(e)).toList();
    final open = duties.where((d) => Kiosk.nextKind(d) != null).toList();
    if (open.isEmpty) return markTestSkipped('no open duty at the moment');
    final d = open.first;
    await s.api.post('/v1/employees/${d['employeeId']}/kiosk-pin', {'pin': '4826'});
    final photo = Photo(base64Decode(_jpeg), DateTime.now());
    final kind = Kiosk.nextKind(d)!;
    await expectLater(
        Kiosk.punch(s, kind: kind, pin: '1357', employeeId: d['employeeId'], siteId: d['siteId'], photo: photo),
        throwsA(isA<ApiException>().having((e) => e.toString(), 'message', contains('Wrong PIN'))));
    final ok = await Kiosk.punch(s, kind: kind, pin: '4826', employeeId: d['employeeId'], siteId: d['siteId'], photo: photo);
    expect(ok.status, isNot('REJECTED'));

    // roll-call for whoever is still not checked in at that site (if anyone)
    final today = DateTime.now().toIso8601String().substring(0, 10);
    final sheet = (await s.api.get('/v1/attendance/sheet?siteId=${d['siteId']}&date=$today') as List).map((e) => Map<String, dynamic>.from(e)).toList();
    final todo = sheet.where((r) => rollCallEligible(r, 'CHECK_IN')).map((r) => r['assignmentId']).toList();
    if (todo.isEmpty) return;
    final photoId = await Photos.upload(s.api, Photos.uploadBody(_jpeg, 'TEAM', DateTime.now(), siteId: d['siteId']));
    final batch = Api.newId();
    final r = await s.api.post('/v1/attendance/roll-call', {
      'batchId': batch, 'siteId': d['siteId'], 'kind': 'CHECK_IN', 'occurredAt': DateTime.now().toUtc().toIso8601String(),
      'assignmentIds': todo, 'photoId': photoId,
    }, true, batch) as Map;
    expect(r['marked'], todo.length);
  }, skip: server.isEmpty ? 'set --dart-define=SERVER=...' : false);
}

// 1x1 white JPEG
const _jpeg = '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=';
