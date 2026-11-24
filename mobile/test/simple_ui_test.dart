import 'package:dutygrid_mobile/core/duty_logic.dart';
import 'package:dutygrid_mobile/core/l10n.dart';
import 'package:dutygrid_mobile/screens/supervisor/roll_call_tab.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

Map<String, dynamic> duty(String id, DateTime start, DateTime end, {DateTime? inAt, DateTime? outAt}) => {
      'assignment_id': id,
      'start_at': start.toUtc().toIso8601String(),
      'end_at': end.toUtc().toIso8601String(),
      'check_in_at': inAt?.toUtc().toIso8601String(),
      'check_out_at': outAt?.toUtc().toIso8601String(),
      'site_name': 'Manyata',
    };

void main() {
  test('every language has every key, and placeholders match English', () {
    final en = L10n.strings['en']!;
    for (final code in L10n.languages.keys) {
      final m = L10n.strings[code]!;
      expect(m.keys.toSet(), en.keys.toSet(), reason: 'language $code has missing or extra keys');
      for (final k in en.keys) {
        final want = RegExp(r'\{(\w+)\}').allMatches(en[k]!).map((x) => x[1]).toSet();
        final got = RegExp(r'\{(\w+)\}').allMatches(m[k]!).map((x) => x[1]).toSet();
        expect(got, want, reason: '$code.$k placeholders');
      }
    }
  });

  test('default language is English on a fresh install, and stays English after sign-out', () async {
    expect(L10n.languages.keys.toList(), ['en', 'hi']);
    SharedPreferences.setMockInitialValues({});
    L10n.instance.code = 'hi'; // whatever was in memory before
    await L10n.instance.load();
    expect(L10n.instance.code, 'en');
    expect(tr('start_duty'), 'START DUTY');
    // sign-out clears the session, not the language choice; with no choice saved it is English
    SharedPreferences.setMockInitialValues({'token': 'x'});
    await L10n.instance.load();
    expect(L10n.instance.code, 'en');
    // only an explicit pick switches to Hindi, and that pick is remembered
    await L10n.instance.set('hi');
    await L10n.instance.load();
    expect(L10n.instance.code, 'hi');
    await L10n.instance.set('en');
  });

  test('translation fills placeholders and switches language', () async {
    SharedPreferences.setMockInitialValues({});
    await L10n.instance.set('en');
    expect(tr('rc_mark_all_in', {'n': 5}), 'Mark all present (5)');
    await L10n.instance.set('hi');
    expect(tr('start_duty'), 'ड्यूटी शुरू करें');
    expect(tr('rc_mark_all_in', {'n': 5}), 'सबकी हाज़िरी लगाएँ (5)');
    expect(tr('no_such_key'), 'no_such_key');
    await L10n.instance.set('xx'); // unknown language is ignored
    expect(L10n.instance.code, 'hi');
    final p = await SharedPreferences.getInstance();
    expect(p.getString('lang'), 'hi');
    await L10n.instance.set('en');
  });

  group('the one big duty button', () {
    final now = DateTime(2026, 11, 23, 20, 30);
    final tonight = duty('n', DateTime(2026, 11, 23, 20), DateTime(2026, 11, 24, 8));
    final tomorrow = duty('t', DateTime(2026, 11, 24, 20), DateTime(2026, 11, 25, 8));

    test('START when a duty is due and not yet started', () {
      final s = heroState([tomorrow, tonight], now);
      expect(s.action, HeroAction.start);
      expect(s.duty!['assignment_id'], 'n');
    });

    test('START up to 2 hours before the shift', () {
      expect(heroState([tonight], DateTime(2026, 11, 23, 18, 15)).action, HeroAction.start);
      expect(heroState([tonight], DateTime(2026, 11, 23, 17, 30)).action, HeroAction.next);
    });

    test('END while on duty', () {
      final on = duty('n', DateTime(2026, 11, 23, 20), DateTime(2026, 11, 24, 8), inAt: DateTime(2026, 11, 23, 20, 2));
      expect(heroState([on, tomorrow], now).action, HeroAction.end);
    });

    test('DONE after checking out, with the next duty shown', () {
      final done = duty('d', DateTime(2026, 11, 23, 8), DateTime(2026, 11, 23, 20),
          inAt: DateTime(2026, 11, 23, 8), outAt: DateTime(2026, 11, 23, 20));
      final s = heroState([done, tomorrow], now);
      expect(s.action, HeroAction.done);
      expect(s.next!['assignment_id'], 't');
    });

    test('NEXT when nothing is due; NONE when nothing at all', () {
      expect(heroState([tomorrow], now).action, HeroAction.next);
      expect(heroState([], now).action, HeroAction.none);
    });

    test('a missed duty (ended, never started) does not ask to START', () {
      final missed = duty('m', DateTime(2026, 11, 23, 8), DateTime(2026, 11, 23, 20));
      expect(heroState([missed], now).action, HeroAction.none);
    });

    test('double duty: END the first, then START the second', () {
      final first = duty('a', DateTime(2026, 11, 23, 14), DateTime(2026, 11, 23, 22), inAt: DateTime(2026, 11, 23, 14));
      final second = duty('b', DateTime(2026, 11, 23, 22), DateTime(2026, 11, 24, 6));
      expect(heroState([second, first], now).duty!['assignment_id'], 'a');
      final firstDone = {...first, 'check_out_at': DateTime(2026, 11, 23, 22).toUtc().toIso8601String()};
      final s = heroState([second, firstDone], DateTime(2026, 11, 23, 22, 1));
      expect(s.action, HeroAction.start);
      expect(s.duty!['assignment_id'], 'b');
    });
  });

  test('roll-call opens with everyone who can be marked already selected', () {
    final rows = [
      {'assignmentId': 'a'},
      {'assignmentId': 'b', 'checkInAt': 'x'},
      {'assignmentId': 'c', 'pendingCorrection': true},
    ];
    expect(defaultRollCallSelection(rows, 'CHECK_IN'), {'a'});
    expect(defaultRollCallSelection(rows, 'CHECK_OUT'), {'b'});
  });
}
