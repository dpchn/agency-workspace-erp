/// What the guard's single big button should do right now.
enum HeroAction { start, end, done, next, none }

class HeroState {
  final HeroAction action;
  final Map<String, dynamic>? duty; // the duty the button acts on (or the next one)
  final Map<String, dynamic>? next; // next duty after a finished one
  const HeroState(this.action, [this.duty, this.next]);
}

DateTime? _ts(Object? v) => v == null ? null : DateTime.tryParse('$v')?.toLocal();

/// A duty can be punched from 2 hours before its start to 2 hours after its end (the server's matching window).
bool inPunchWindow(Map<String, dynamic> d, DateTime now) {
  final s = _ts(d['start_at']), e = _ts(d['end_at']);
  return s != null && e != null && now.isAfter(s.subtract(const Duration(hours: 2))) && now.isBefore(e.add(const Duration(hours: 2)));
}

/// Decides the one button: end the duty you are on, else start the duty that is due, else "done for today",
/// else show when the next duty is.
HeroState heroState(List<Map<String, dynamic>> duties, DateTime now) {
  final sorted = [...duties]..sort((a, b) => (_ts(a['start_at']) ?? now).compareTo(_ts(b['start_at']) ?? now));
  final current = sorted.where((d) => inPunchWindow(d, now)).toList();
  final onDuty = current.where((d) => d['check_in_at'] != null && d['check_out_at'] == null);
  if (onDuty.isNotEmpty) return HeroState(HeroAction.end, onDuty.first);
  // not yet started and not already over (a duty whose end has passed without check-in is a no-show)
  final due = current.where((d) => d['check_in_at'] == null && (_ts(d['end_at'])?.isAfter(now) ?? false));
  if (due.isNotEmpty) return HeroState(HeroAction.start, due.first);
  final upcoming = sorted.where((d) => (_ts(d['start_at'])?.isAfter(now) ?? false) && d['check_in_at'] == null).toList();
  final finished = current.where((d) => d['check_out_at'] != null);
  if (finished.isNotEmpty) return HeroState(HeroAction.done, finished.last, upcoming.isEmpty ? null : upcoming.first);
  if (upcoming.isNotEmpty) return HeroState(HeroAction.next, upcoming.first);
  return const HeroState(HeroAction.none);
}
