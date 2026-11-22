// Runs against a live server: flutter test test/live_backend_test.dart --dart-define=SERVER=http://localhost:8080
// (skipped when SERVER is not given). Uses the demo data from `docker compose --profile demo up demo-seed`.
import 'package:dutygrid_mobile/core/session.dart';
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
}
