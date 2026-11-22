import 'package:material_ui/material_ui.dart';

import 'config/brand.dart';
import 'core/session.dart';
import 'screens/guard/duty_tab.dart';
import 'screens/guard/self_tabs.dart';
import 'screens/incidents_tab.dart';
import 'screens/login_screen.dart';
import 'screens/supervisor/team_tabs.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final session = Session();
  session.init();
  runApp(ErpApp(session: session));
}

class ErpApp extends StatelessWidget {
  final Session session;
  const ErpApp({super.key, required this.session});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: session,
      builder: (context, _) => MaterialApp(
        title: Brand.name,
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          useMaterial3: true,
          // 'fidelity' keeps the exact brand colour instead of a muted tonal variant
          colorScheme: ColorScheme.fromSeed(
            seedColor: session.me == null ? Brand.primary : session.primary,
            dynamicSchemeVariant: DynamicSchemeVariant.fidelity,
          ),
        ),
        home: !session.ready
            ? const Scaffold(body: Center(child: CircularProgressIndicator()))
            : session.me == null
                ? LoginScreen(session: session)
                : HomeScreen(session: session),
      ),
    );
  }
}

class _TabDef {
  final String label;
  final IconData icon;
  final Widget body;
  const _TabDef(this.label, this.icon, this.body);
}

/// One app, role-aware: guards see self-service; supervisors/operations also get team screens.
class HomeScreen extends StatefulWidget {
  final Session session;
  const HomeScreen({super.key, required this.session});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _index = 0;

  List<_TabDef> _tabs() {
    final s = widget.session;
    if (s.isSupervisor) {
      return [
        _TabDef('Today', Icons.today, TodayTab(session: s)),
        if (s.can('ATTENDANCE_APPROVE')) _TabDef('Approve', Icons.fact_check_outlined, ApprovalsTab(session: s)),
        if (s.employeeId != null) _TabDef('My duty', Icons.badge_outlined, DutyTab(session: s)),
        _TabDef('Incidents', Icons.report_outlined, IncidentsTab(session: s)),
      ];
    }
    if (s.employeeId != null) {
      return [
        _TabDef('Duty', Icons.badge_outlined, DutyTab(session: s)),
        _TabDef('Attendance', Icons.calendar_month_outlined, MyAttendanceTab(session: s)),
        _TabDef('Payslips', Icons.receipt_long_outlined, PayslipsTab(session: s)),
        _TabDef('Leave', Icons.beach_access_outlined, LeaveTab(session: s)),
        _TabDef('Incidents', Icons.report_outlined, IncidentsTab(session: s)),
      ];
    }
    return const [];
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.session;
    final tabs = _tabs();
    final logo = s.logoUrl;
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 12,
        title: Row(children: [
          if (logo != null && !logo.endsWith('.svg'))
            Padding(padding: const EdgeInsets.only(right: 8), child: Image.network(logo, height: 28, errorBuilder: (_, _, _) => const Icon(Icons.shield)))
          else
            Padding(padding: const EdgeInsets.only(right: 8), child: Icon(Icons.shield, color: s.primary)),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(s.orgName, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold), overflow: TextOverflow.ellipsis),
              Text('${s.userName} · ${s.role.toLowerCase().replaceAll('_', ' ')}', style: const TextStyle(fontSize: 11, color: Colors.grey)),
            ]),
          ),
        ]),
        actions: [
          IconButton(
            tooltip: 'Sign out',
            icon: const Icon(Icons.logout),
            onPressed: () async {
              if (s.queue.pending > 0) {
                final ok = await showDialog<bool>(
                  context: context,
                  builder: (c) => AlertDialog(
                    title: const Text('Unsynced punches'),
                    content: Text('${s.queue.pending} check-in/out(s) are still on this phone. Sign out anyway? They will sync after the next sign-in.'),
                    actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Stay')), TextButton(onPressed: () => Navigator.pop(c, true), child: const Text('Sign out'))],
                  ),
                );
                if (ok != true) return;
              }
              await s.logout();
            },
          ),
        ],
      ),
      body: tabs.isEmpty
          ? const Padding(
              padding: EdgeInsets.all(32),
              child: Center(child: Text('This app is for guards and supervisors.\nPlease use the web portal for your account.', textAlign: TextAlign.center)),
            )
          : IndexedStack(index: _index.clamp(0, tabs.length - 1), children: tabs.map((t) => t.body).toList()),
      bottomNavigationBar: tabs.length < 2
          ? null
          : NavigationBar(
              selectedIndex: _index.clamp(0, tabs.length - 1),
              onDestinationSelected: (i) => setState(() => _index = i),
              destinations: tabs.map((t) => NavigationDestination(icon: Icon(t.icon), label: t.label)).toList(),
            ),
    );
  }
}
