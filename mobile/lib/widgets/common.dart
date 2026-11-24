import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

import '../core/l10n.dart';
import '../core/api.dart';

final _time = DateFormat('hh:mm a');
final _date = DateFormat('EEE, dd MMM');
final _money = NumberFormat.currency(locale: 'en_IN', symbol: '₹', decimalDigits: 2);

DateTime? parseTs(Object? v) => v == null ? null : DateTime.tryParse('$v')?.toLocal();
String fmtTime(Object? v) => parseTs(v) == null ? '--' : _time.format(parseTs(v)!);
String fmtDate(Object? v) => parseTs(v) == null ? '$v' : _date.format(parseTs(v)!);
String fmtMoney(Object? v) => _money.format(num.tryParse('$v') ?? 0);
String ymd(DateTime d) => DateFormat('yyyy-MM-dd').format(d);
String ym(DateTime d) => DateFormat('yyyy-MM').format(d);

void toast(BuildContext context, String msg, {bool error = false}) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(msg), backgroundColor: error ? Colors.red.shade700 : null));
}

String errorText(Object e) => e is ApiException ? e.toString() : 'Something went wrong: $e';

Color statusColor(String s) {
  switch (s) {
    case 'ON_DUTY':
    case 'PRESENT':
    case 'APPROVED':
    case 'ACCEPTED':
    case 'FILLED':
    case 'COMPLETED':
    case 'PAID':
    case 'CLOSED':
    case 'RESOLVED':
      return Colors.green.shade700;
    case 'HALF_DAY':
    case 'FLAGGED':
    case 'PARTIAL':
    case 'PENDING':
    case 'QUEUED':
    case 'ACKNOWLEDGED':
    case 'IN_PROGRESS':
    case 'CONFIRMED':
      return Colors.orange.shade800;
    case 'ABSENT':
    case 'REJECTED':
    case 'OPEN':
    case 'NO_SHOW':
    case 'CRITICAL':
    case 'HIGH':
      return Colors.red.shade700;
    default:
      return Colors.blueGrey;
  }
}

class StatusChip extends StatelessWidget {
  final String label;
  const StatusChip(this.label, {super.key});
  @override
  Widget build(BuildContext context) {
    final c = statusColor(label);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: c.withValues(alpha: 0.1), border: Border.all(color: c.withValues(alpha: 0.4)), borderRadius: BorderRadius.circular(6)),
      child: Text(label.replaceAll('_', ' '), style: TextStyle(color: c, fontSize: 11, fontWeight: FontWeight.w600)),
    );
  }
}

/// Loads data with pull-to-refresh, loading and error/empty states.
class AsyncList<T> extends StatefulWidget {
  final Future<List<T>> Function() load;
  final Widget Function(BuildContext, T, VoidCallback reload) item;
  final String empty;
  final Widget? header;
  const AsyncList({super.key, required this.load, required this.item, this.empty = '', this.header});
  @override
  State<AsyncList<T>> createState() => AsyncListState<T>();
}

class AsyncListState<T> extends State<AsyncList<T>> {
  late Future<List<T>> _f;
  @override
  void initState() {
    super.initState();
    _f = widget.load();
  }

  // block body: an arrow would return the new Future from the callback, which setState rejects
  void reload() => setState(() {
        _f = widget.load();
      });

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: () async {
        reload();
        await _f.catchError((_) => <T>[]);
      },
      child: FutureBuilder<List<T>>(
        future: _f,
        builder: (context, snap) {
          final children = <Widget>[if (widget.header != null) widget.header!];
          if (snap.connectionState != ConnectionState.done) {
            children.add(const Padding(padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator())));
          } else if (snap.hasError) {
            children.add(_Message(icon: Icons.cloud_off, text: errorText(snap.error!), action: TextButton(onPressed: reload, child: Text(tr('retry')))));
          } else if (snap.data!.isEmpty) {
            children.add(_Message(icon: Icons.inbox_outlined, text: widget.empty.isEmpty ? tr('nothing_here') : widget.empty));
          } else {
            children.addAll(snap.data!.map((e) => widget.item(context, e, reload)));
          }
          return ListView(physics: const AlwaysScrollableScrollPhysics(), padding: const EdgeInsets.fromLTRB(12, 8, 12, 96), children: children);
        },
      ),
    );
  }
}

class _Message extends StatelessWidget {
  final IconData icon;
  final String text;
  final Widget? action;
  const _Message({required this.icon, required this.text, this.action});
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 48, horizontal: 16),
        child: Column(children: [
          Icon(icon, size: 40, color: Colors.grey),
          const SizedBox(height: 8),
          Text(text, textAlign: TextAlign.center, style: const TextStyle(color: Colors.grey)),
          ?action,
        ]),
      );
}

/// Runs an async action with a spinner on the button and a toast on error.
class BusyButton extends StatefulWidget {
  final Future<void> Function() onPressed;
  final Widget child;
  final bool filled;
  final IconData? icon;
  const BusyButton({super.key, required this.onPressed, required this.child, this.filled = true, this.icon});
  @override
  State<BusyButton> createState() => _BusyButtonState();
}

class _BusyButtonState extends State<BusyButton> {
  bool _busy = false;
  Future<void> _run() async {
    setState(() => _busy = true);
    try {
      await widget.onPressed();
    } catch (e) {
      if (mounted) toast(context, errorText(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final label = _busy ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : widget.child;
    final onTap = _busy ? null : _run;
    if (widget.icon != null && !_busy) {
      return widget.filled
          ? FilledButton.icon(onPressed: onTap, icon: Icon(widget.icon), label: widget.child)
          : OutlinedButton.icon(onPressed: onTap, icon: Icon(widget.icon), label: widget.child);
    }
    return widget.filled ? FilledButton(onPressed: onTap, child: label) : OutlinedButton(onPressed: onTap, child: label);
  }
}

Future<String?> askText(BuildContext context, String title, {String hint = '', bool required = true}) async {
  final c = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: TextField(controller: c, autofocus: true, maxLines: 3, decoration: InputDecoration(hintText: hint)),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: Text(tr('cancel'))),
        FilledButton(
            onPressed: () {
              if (required && c.text.trim().isEmpty) return;
              Navigator.pop(ctx, c.text.trim());
            },
            child: Text(tr('ok'))),
      ],
    ),
  );
}
