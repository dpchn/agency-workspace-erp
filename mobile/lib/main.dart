import 'package:material_ui/material_ui.dart';

import 'config/brand.dart';
import 'core/l10n.dart';
import 'core/nav.dart';
import 'core/session.dart';
import 'screens/guard/duty_tab.dart';
import 'screens/guard/self_tabs.dart';
import 'screens/incidents_tab.dart';
import 'screens/kiosk/kiosk_screen.dart';
import 'screens/login_screen.dart';
import 'screens/supervisor/roll_call_tab.dart';
import 'screens/supervisor/team_tabs.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await L10n.instance.load();
  final session = Session();
  session.init();
  runApp(ErpApp(session: session));
}

/// Large, high-contrast look for people who don't use apps much: bigger text (+15 %), tall buttons
/// (56 px or more), bold labels under the bottom-bar icons.
ThemeData appTheme(Color seed) {
  final scheme = ColorScheme.fromSeed(
    seedColor: seed,
    // 'fidelity' keeps the exact brand colour instead of a muted tonal variant
    dynamicSchemeVariant: DynamicSchemeVariant.fidelity,
    contrastLevel: 0.5,
  );
  const tall = Size(64, 56);
  const label = TextStyle(fontSize: 17, fontWeight: FontWeight.w600);
  final base = ThemeData(useMaterial3: true, colorScheme: scheme);
  return base.copyWith(
    filledButtonTheme: FilledButtonThemeData(style: FilledButton.styleFrom(minimumSize: tall, textStyle: label)),
    outlinedButtonTheme: OutlinedButtonThemeData(style: OutlinedButton.styleFrom(minimumSize: tall, textStyle: label)),
    elevatedButtonTheme: ElevatedButtonThemeData(style: ElevatedButton.styleFrom(minimumSize: tall, textStyle: label)),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(minimumSize: const Size(48, 48), textStyle: label)),
    segmentedButtonTheme: SegmentedButtonThemeData(style: SegmentedButton.styleFrom(minimumSize: tall, textStyle: label)),
    navigationBarTheme: NavigationBarThemeData(
      height: 76,
      // labels must fit on one line under the icon ("Attendance" wrapped at the larger text size)
      labelTextStyle: WidgetStateProperty.all(const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700, overflow: TextOverflow.ellipsis)),
      iconTheme: WidgetStateProperty.all(const IconThemeData(size: 30)),
    ),
    listTileTheme: const ListTileThemeData(minTileHeight: 64),
    inputDecorationTheme: const InputDecorationTheme(border: OutlineInputBorder(), contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 18)),
  );
}

class ErpApp extends StatelessWidget {
  final Session session;
  const ErpApp({super.key, required this.session});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([session, L10n.instance]),
      builder: (context, _) => MaterialApp(
        title: Brand.name,
        debugShowCheckedModeBanner: false,
        navigatorKey: appNavigator,
        theme: appTheme(session.me == null ? Brand.primary : session.primary),
        // ~15% larger text everywhere, on top of the phone's own text-size setting. (Scaling the theme's text
        // styles instead crashes: their sizes are only filled in later.)
        builder: (context, child) {
          final mq = MediaQuery.of(context);
          return MediaQuery(data: mq.copyWith(textScaler: TextScaler.linear(mq.textScaler.scale(1) * 1.15)), child: child!);
        },
        // keyed by language so every screen is rebuilt with the new words when it changes
        home: KeyedSubtree(
          key: ValueKey('lang-${L10n.instance.code}'),
          child: !session.ready
              ? const Scaffold(body: Center(child: CircularProgressIndicator()))
              : session.me == null
                  ? LoginScreen(session: session)
                  // a shared site device opens straight into kiosk mode
                  : session.isSiteKiosk
                      ? KioskScreen(key: ValueKey(session.userName), session: session)
                      : HomeScreen(session: session),
        ),
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
        _TabDef(tr('tab_today'), Icons.today, TodayTab(session: s)),
        if (s.can('ATTENDANCE_MARK')) _TabDef(tr('tab_rollcall'), Icons.groups, RollCallTab(session: s)),
        if (s.can('ATTENDANCE_APPROVE')) _TabDef(tr('tab_approve'), Icons.fact_check, ApprovalsTab(session: s)),
        if (s.employeeId != null) _TabDef(tr('tab_my_duty'), Icons.badge, DutyTab(session: s)),
        _TabDef(tr('tab_incidents'), Icons.warning_amber, IncidentsTab(session: s)),
      ];
    }
    if (s.employeeId != null) {
      return [
        _TabDef(tr('tab_duty'), Icons.badge, DutyTab(session: s)),
        _TabDef(tr('tab_attendance'), Icons.calendar_month, MyAttendanceTab(session: s)),
        _TabDef(tr('tab_payslips'), Icons.currency_rupee, PayslipsTab(session: s)),
        _TabDef(tr('tab_leave'), Icons.beach_access, LeaveTab(session: s)),
        _TabDef(tr('tab_incidents'), Icons.warning_amber, IncidentsTab(session: s)),
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
          if (s.can('ATTENDANCE_KIOSK'))
            IconButton(
              tooltip: tr('kiosk_mode'),
              icon: const Icon(Icons.tablet_android_outlined),
              onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => KioskScreen(session: s, handedOver: true))),
            ),
          const LanguageButton(),
          IconButton(
            tooltip: tr('sign_out'),
            icon: const Icon(Icons.logout),
            onPressed: () async {
              if (s.queue.pending > 0 || s.actions.pending > 0) {
                final ok = await showDialog<bool>(
                  context: context,
                  builder: (c) => AlertDialog(
                    title: Text(tr('unsynced_title')),
                    content: Text(tr('unsynced_body', {'n': s.queue.pending + s.actions.pending})),
                    actions: [
                      TextButton(onPressed: () => Navigator.pop(c, false), child: Text(tr('stay'))),
                      TextButton(onPressed: () => Navigator.pop(c, true), child: Text(tr('sign_out'))),
                    ],
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
          ? Padding(
              padding: const EdgeInsets.all(32),
              child: Center(child: Text(tr('not_for_role'), textAlign: TextAlign.center)),
            )
          : IndexedStack(index: _index.clamp(0, tabs.length - 1), children: tabs.map((t) => t.body).toList()),
      bottomNavigationBar: tabs.length < 2
          ? null
          : NavigationBar(
              selectedIndex: _index.clamp(0, tabs.length - 1),
              onDestinationSelected: (i) => setState(() => _index = i),
              labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
              destinations: tabs.map((t) => NavigationDestination(icon: Icon(t.icon), label: t.label)).toList(),
            ),
    );
  }
}
