import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// App language. Strings are looked up by key with [tr]; a missing translation falls back to English.
///
/// To add a language (Kannada, Tamil, Marathi…): add its name to [L10n.languages] and a map with the same
/// keys to [L10n.strings]. Nothing else changes.
class L10n extends ChangeNotifier {
  L10n._();
  static final L10n instance = L10n._();

  /// Language code -> name shown in its own script, so a worker can recognise it.
  static const languages = {'en': 'English', 'hi': 'हिन्दी'};

  /// English unless the user picked another language with the language button (the choice survives sign-out).
  static const defaultCode = 'en';
  String code = defaultCode;

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    final c = p.getString('lang');
    code = c != null && languages.containsKey(c) ? c : defaultCode;
    notifyListeners();
  }

  Future<void> set(String c) async {
    if (!languages.containsKey(c)) return;
    code = c;
    notifyListeners();
    final p = await SharedPreferences.getInstance();
    await p.setString('lang', c);
  }

  String t(String key, [Map<String, Object?>? args]) {
    var s = strings[code]?[key] ?? strings['en']![key] ?? key;
    args?.forEach((k, v) => s = s.replaceAll('{$k}', '$v'));
    return s;
  }

  static const Map<String, Map<String, String>> strings = {
    'en': {
      // login
      'sign_in': 'Sign in',
      'login_id': 'Mobile number or e-mail',
      'password': 'Password',
      'server': 'Server address',
      'server_help': 'Ask your office for this once; it is remembered.',
      'server_needed': 'Enter your agency server address',
      'server_label': 'Server: {url}',
      'language': 'Language',
      // mobile + SMS code sign-in
      'phone_label': 'Mobile number',
      'send_code': 'Send code',
      'code_label': '6-digit code',
      'code_sent_to': 'We sent a code by SMS to {phone}',
      'resend_in': 'Resend code in {s} s',
      'resend': 'Resend code',
      'change_number': 'Change number',
      'use_password': 'Sign in with e-mail / password',
      'use_otp': 'Sign in with mobile number (SMS code)',
      'forgot_password': 'Forgot password?',
      'reset_title': 'Set a new password',
      'new_password': 'New password (at least 8 characters)',
      'new_password_again': 'New password again',
      'passwords_differ': 'The two passwords are not the same',
      'password_short': 'Password must have at least 8 characters',
      'save_and_sign_in': 'Save and sign in',
      'phone_invalid': 'Enter your 10-digit mobile number',
      'otp_wrong': 'Wrong code. Check the SMS and try again.',
      'otp_expired': 'This code has expired. Tap "Resend code".',
      'otp_too_many_tries': 'Too many wrong tries. Ask for a new code.',
      'otp_limit': 'Too many codes asked. Try again after 15 minutes.',
      'otp_wait': 'Please wait 30 seconds before asking again.',
      'otp_no_account': 'This number is not registered with any agency. Ask your office.',
      'no_network': 'No internet. Check the network and try again.',
      'code_sent': 'Code sent',
      'kiosk_send_code': 'Send code by SMS instead',
      'kiosk_use_password': 'Use password instead',
      'kiosk_code_hint': 'Code sent to the phone of {name}',
      // tabs
      'tab_duty': 'Duty',
      'tab_attendance': 'Attendance',
      'tab_payslips': 'Pay',
      'tab_leave': 'Leave',
      'tab_incidents': 'Problem',
      'tab_today': 'Today',
      'tab_rollcall': 'Roll-call',
      'tab_approve': 'Approve',
      'tab_my_duty': 'My duty',
      'not_for_role': 'This app is for guards and supervisors.\nPlease use the web portal for your account.',
      // guard duty
      'start_duty': 'START DUTY',
      'end_duty': 'END DUTY',
      'done_today': 'Done for today ✓',
      'next_duty': 'Next duty: {when}, {site}',
      'no_duty': 'No duty in the next 7 days',
      'on_duty_since': 'On duty since {time}',
      'go_to_site': 'Go to the site gate, then press the button',
      'upcoming': 'Coming duties',
      'please_wait': 'Please wait…',
      // results
      'result_started': 'Duty started',
      'result_ended': 'Duty ended',
      'result_in': 'Arrival recorded',
      'result_out': 'Leaving recorded',
      'result_flagged': 'Recorded. Supervisor will check it.',
      'result_queued': 'No network. Saved on phone, will send later.',
      'result_failed': 'Not recorded',
      'tap_continue': 'Tap to continue',
      // common
      'ok': 'OK',
      'cancel': 'Cancel',
      'retry': 'Try again',
      'done': 'Done',
      'sign_out': 'Sign out',
      'stay': 'Stay',
      'unsynced_title': 'Not sent yet',
      'unsynced_body': '{n} attendance entries are still on this phone. Sign out anyway? They will be sent after the next sign-in.',
      'saved_waiting': 'Saved on phone: {n} waiting to send',
      'send_now': 'Send now',
      'sending': 'Sending…',
      'clear': 'Clear',
      'sent_n': 'Sent {n}',
      'nothing_here': 'Nothing here yet',
      'not_in': 'Not in yet',
      'in_at': 'In {time}',
      'out_at': 'Out {time}',
      'no_phone': 'No phone',
      // kiosk
      'kiosk_title': 'Attendance',
      'kiosk_enter_code': 'Type my employee code',
      'kiosk_tap_name': 'Or tap your name',
      'kiosk_no_duties': 'No duties at this site right now',
      'kiosk_offline': 'No network: attendance is saved on this device and sent later.',
      'kiosk_hold_exit': 'Hold the lock to exit',
      'kiosk_leave': 'Leave kiosk mode',
      'kiosk_signout_device': 'Sign out this device',
      'kiosk_password_of': 'Password of {name}',
      'kiosk_mode': 'Kiosk mode (for workers without a phone)',
      'kiosk_to_send': '{n} to send',
      'unlock': 'Unlock',
      'wrong_password': 'Wrong password',
      'no_pin': 'No PIN, ask the office',
      'in': 'IN',
      'out': 'OUT',
      'check_in': 'Coming IN',
      'check_out': 'Going OUT',
      'enter_pin': 'Enter your PIN',
      'your_code': 'Your employee code',
      'photo_needed': 'A photo is needed',
      // camera
      'look_camera': 'Look at the camera',
      'team_in_frame': 'Get the whole team in the photo',
      'take_photo': 'Take photo now',
      'camera_failed': 'Camera not available',
      // roll-call
      'rc_mark_all_in': 'Mark all present ({n})',
      'rc_mark_all_out': 'Mark all leaving ({n})',
      'rc_mark_in': 'Mark {n} present',
      'rc_mark_out': 'Mark {n} leaving',
      'rc_coming': 'Coming in',
      'rc_going': 'Going out',
      'rc_site': 'Site',
      'rc_none': 'No duties at your sites on this day',
      'rc_can_mark': '{n} of {total} can be marked',
      'rc_select_all': 'Select all',
      'rc_note': 'Marked by you = another person approves. The team photo and your location are saved.',
      'rc_marked': '{n} marked',
      'rc_rejected': '{n} not marked',
      'rc_select_first': 'Select people first',
      'rc_waiting': '{n} item(s) waiting for network',
      'rc_all_sent': 'All saved items sent',
      // other tabs
      'report_incident': 'Report a problem',
      'apply_leave': 'Ask for leave',
      'apply_for_leave': 'Ask for leave',
      'submit': 'Submit',
      'send': 'Send',
      'site': 'Site',
      'leave_type': 'Type',
      'reason': 'Reason',
      'what_happened': 'What happened?',
      'details': 'Details',
      'no_attendance': 'No attendance this month',
      'no_payslips': 'No payslips yet',
      'no_leave': 'No leave requests',
      'no_incidents': 'No problems reported',
      'payslip': 'Payslip',
      'net_pay': 'Net pay',
      'paid_duties': 'Paid duties: {n}',
      'find_guard': 'Find guard',
      'approve': 'Approve',
      'reject': 'Reject',
      'nothing_to_approve': 'Nothing waiting for approval',
      'no_shifts': 'No shifts',
      'acknowledge': 'Seen',
      'start': 'Start',
      'resolve': 'Solved',
      'close': 'Close',
      'sev_low': 'Low',
      'sev_medium': 'Medium',
      'sev_high': 'High',
      'sev_critical': 'Danger',
    },
    'hi': {
      'sign_in': 'लॉग इन करें',
      'login_id': 'मोबाइल नंबर या ई-मेल',
      'password': 'पासवर्ड',
      'server': 'सर्वर का पता',
      'server_help': 'यह एक बार ऑफिस से पूछें, फ़ोन याद रखेगा।',
      'server_needed': 'अपनी एजेंसी का सर्वर पता डालें',
      'server_label': 'सर्वर: {url}',
      'language': 'भाषा',
      // mobile + SMS code sign-in
      'phone_label': 'मोबाइल नंबर',
      'send_code': 'कोड भेजें',
      'code_label': '6 अंकों का कोड',
      'code_sent_to': '{phone} पर SMS से कोड भेजा गया है',
      'resend_in': '{s} सेकंड में दोबारा भेजें',
      'resend': 'कोड दोबारा भेजें',
      'change_number': 'नंबर बदलें',
      'use_password': 'ई-मेल / पासवर्ड से लॉग इन करें',
      'use_otp': 'मोबाइल नंबर (SMS कोड) से लॉग इन करें',
      'forgot_password': 'पासवर्ड भूल गए?',
      'reset_title': 'नया पासवर्ड बनाएं',
      'new_password': 'नया पासवर्ड (कम से कम 8 अक्षर)',
      'new_password_again': 'नया पासवर्ड दोबारा',
      'passwords_differ': 'दोनों पासवर्ड एक जैसे नहीं हैं',
      'password_short': 'पासवर्ड में कम से कम 8 अक्षर होने चाहिए',
      'save_and_sign_in': 'सेव करें और लॉग इन करें',
      'phone_invalid': 'अपना 10 अंकों का मोबाइल नंबर डालें',
      'otp_wrong': 'गलत कोड। SMS देखकर दोबारा डालें।',
      'otp_expired': 'कोड का समय खत्म हो गया। "कोड दोबारा भेजें" दबाएं।',
      'otp_too_many_tries': 'बहुत बार गलत कोड डाला। नया कोड मंगाएं।',
      'otp_limit': 'बहुत सारे कोड मांगे गए। 15 मिनट बाद कोशिश करें।',
      'otp_wait': 'दोबारा मांगने से पहले 30 सेकंड रुकें।',
      'otp_no_account': 'यह नंबर किसी एजेंसी में दर्ज नहीं है। अपने ऑफिस से पूछें।',
      'no_network': 'इंटरनेट नहीं है। नेटवर्क देखकर दोबारा कोशिश करें।',
      'code_sent': 'कोड भेज दिया',
      'kiosk_send_code': 'इसकी जगह SMS से कोड भेजें',
      'kiosk_use_password': 'इसकी जगह पासवर्ड डालें',
      'kiosk_code_hint': '{name} के फ़ोन पर भेजा गया कोड',
      'tab_duty': 'ड्यूटी',
      'tab_attendance': 'हाज़िरी',
      'tab_payslips': 'तनख्वाह',
      'tab_leave': 'छुट्टी',
      'tab_incidents': 'समस्या',
      'tab_today': 'आज',
      'tab_rollcall': 'हाज़िरी लगाएँ',
      'tab_approve': 'मंज़ूरी',
      'tab_my_duty': 'मेरी ड्यूटी',
      'not_for_role': 'यह ऐप गार्ड और सुपरवाइज़र के लिए है।\nअपने खाते के लिए वेब पोर्टल खोलें।',
      'start_duty': 'ड्यूटी शुरू करें',
      'end_duty': 'ड्यूटी खत्म करें',
      'done_today': 'आज की ड्यूटी पूरी ✓',
      'next_duty': 'अगली ड्यूटी: {when}, {site}',
      'no_duty': 'अगले 7 दिन कोई ड्यूटी नहीं',
      'on_duty_since': '{time} से ड्यूटी पर',
      'go_to_site': 'साइट के गेट पर जाकर बटन दबाएँ',
      'upcoming': 'आने वाली ड्यूटी',
      'please_wait': 'कृपया रुकें…',
      'result_started': 'ड्यूटी शुरू हो गई',
      'result_ended': 'ड्यूटी खत्म हो गई',
      'result_in': 'आना दर्ज हो गया',
      'result_out': 'जाना दर्ज हो गया',
      'result_flagged': 'दर्ज हो गया। सुपरवाइज़र जाँच करेंगे।',
      'result_queued': 'नेटवर्क नहीं है। फ़ोन में सेव है, बाद में भेजेंगे।',
      'result_failed': 'दर्ज नहीं हुआ',
      'tap_continue': 'आगे बढ़ने के लिए छुएँ',
      'ok': 'ठीक है',
      'cancel': 'रद्द करें',
      'retry': 'दोबारा कोशिश करें',
      'done': 'हो गया',
      'sign_out': 'लॉग आउट',
      'stay': 'रुकें',
      'unsynced_title': 'अभी भेजा नहीं गया',
      'unsynced_body': '{n} हाज़िरी अभी इस फ़ोन में हैं। फिर भी लॉग आउट करें? अगली बार लॉग इन करने पर भेज दी जाएँगी।',
      'saved_waiting': 'फ़ोन में सेव: {n} भेजना बाकी',
      'send_now': 'अभी भेजें',
      'sending': 'भेज रहे हैं…',
      'clear': 'हटाएँ',
      'sent_n': '{n} भेज दिए',
      'nothing_here': 'अभी कुछ नहीं है',
      'not_in': 'अभी नहीं आए',
      'in_at': 'आए {time}',
      'out_at': 'गए {time}',
      'no_phone': 'फ़ोन नहीं',
      'kiosk_title': 'हाज़िरी',
      'kiosk_enter_code': 'मेरा कर्मचारी कोड डालें',
      'kiosk_tap_name': 'या अपने नाम पर दबाएँ',
      'kiosk_no_duties': 'अभी इस साइट पर कोई ड्यूटी नहीं',
      'kiosk_offline': 'नेटवर्क नहीं: हाज़िरी इस फ़ोन में सेव होगी और बाद में भेजी जाएगी।',
      'kiosk_hold_exit': 'बाहर जाने के लिए ताला दबाकर रखें',
      'kiosk_leave': 'कियोस्क मोड बंद करें',
      'kiosk_signout_device': 'इस डिवाइस से लॉग आउट',
      'kiosk_password_of': '{name} का पासवर्ड',
      'kiosk_mode': 'कियोस्क मोड (बिना फ़ोन वालों के लिए)',
      'kiosk_to_send': '{n} भेजना बाकी',
      'unlock': 'खोलें',
      'wrong_password': 'गलत पासवर्ड',
      'no_pin': 'PIN नहीं है, ऑफिस से पूछें',
      'in': 'आना',
      'out': 'जाना',
      'check_in': 'आने की हाज़िरी',
      'check_out': 'जाने की हाज़िरी',
      'enter_pin': 'अपना PIN डालें',
      'your_code': 'आपका कर्मचारी कोड',
      'photo_needed': 'फ़ोटो ज़रूरी है',
      'look_camera': 'कैमरे की तरफ़ देखें',
      'team_in_frame': 'पूरी टीम को फ़ोटो में लें',
      'take_photo': 'अभी फ़ोटो लें',
      'camera_failed': 'कैमरा नहीं खुला',
      'rc_mark_all_in': 'सबकी हाज़िरी लगाएँ ({n})',
      'rc_mark_all_out': 'सबका जाना दर्ज करें ({n})',
      'rc_mark_in': '{n} की हाज़िरी लगाएँ',
      'rc_mark_out': '{n} का जाना दर्ज करें',
      'rc_coming': 'आना',
      'rc_going': 'जाना',
      'rc_site': 'साइट',
      'rc_none': 'इस दिन आपकी साइट पर कोई ड्यूटी नहीं',
      'rc_can_mark': '{total} में से {n} की हाज़िरी लग सकती है',
      'rc_select_all': 'सब चुनें',
      'rc_note': 'आपकी लगाई हाज़िरी कोई दूसरा व्यक्ति मंज़ूर करेगा। टीम की फ़ोटो और आपकी लोकेशन सेव होती है।',
      'rc_marked': '{n} की हाज़िरी लगी',
      'rc_rejected': '{n} की नहीं लगी',
      'rc_select_first': 'पहले लोगों को चुनें',
      'rc_waiting': '{n} नेटवर्क का इंतज़ार कर रहे हैं',
      'rc_all_sent': 'सब भेज दिए गए',
      'report_incident': 'समस्या बताएँ',
      'apply_leave': 'छुट्टी माँगें',
      'apply_for_leave': 'छुट्टी की अर्ज़ी',
      'submit': 'भेजें',
      'send': 'भेजें',
      'site': 'साइट',
      'leave_type': 'प्रकार',
      'reason': 'कारण',
      'what_happened': 'क्या हुआ?',
      'details': 'पूरी बात',
      'no_attendance': 'इस महीने कोई हाज़िरी नहीं',
      'no_payslips': 'अभी कोई तनख्वाह पर्ची नहीं',
      'no_leave': 'कोई छुट्टी की अर्ज़ी नहीं',
      'no_incidents': 'कोई समस्या नहीं बताई गई',
      'payslip': 'तनख्वाह पर्ची',
      'net_pay': 'हाथ में तनख्वाह',
      'paid_duties': 'भुगतान वाली ड्यूटी: {n}',
      'find_guard': 'गार्ड ढूँढें',
      'approve': 'मंज़ूर',
      'reject': 'नामंज़ूर',
      'nothing_to_approve': 'मंज़ूरी के लिए कुछ नहीं',
      'no_shifts': 'कोई शिफ्ट नहीं',
      'acknowledge': 'देख लिया',
      'start': 'काम शुरू',
      'resolve': 'सुलझ गया',
      'close': 'बंद करें',
      'sev_low': 'कम',
      'sev_medium': 'मध्यम',
      'sev_high': 'ज़्यादा',
      'sev_critical': 'खतरा',
    },
  };
}

/// Translated string for [key], with `{name}` placeholders filled from [args].
String tr(String key, [Map<String, Object?>? args]) => L10n.instance.t(key, args);

/// "English | हिन्दी" switch: every language as a big tappable label, the current one highlighted.
class LanguageSwitch extends StatelessWidget {
  final Color? color;
  const LanguageSwitch({super.key, this.color});
  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: L10n.instance,
      builder: (context, _) => Wrap(
        alignment: WrapAlignment.center,
        spacing: 4,
        children: [
          for (final e in L10n.languages.entries)
            ChoiceChip(
              avatar: e.key == L10n.languages.keys.first ? const Icon(Icons.translate, size: 18) : null,
              label: Text(e.value, style: const TextStyle(fontSize: 18)),
              selected: L10n.instance.code == e.key,
              onSelected: (_) => L10n.instance.set(e.key),
            ),
        ],
      ),
    );
  }
}

/// App-bar button that opens the language list.
class LanguageButton extends StatelessWidget {
  const LanguageButton({super.key});
  @override
  Widget build(BuildContext context) => IconButton(
        tooltip: tr('language'),
        icon: const Icon(Icons.translate),
        onPressed: () => showModalBottomSheet(
          context: context,
          builder: (c) => SafeArea(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Padding(padding: const EdgeInsets.all(16), child: Text(tr('language'), style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold))),
              for (final e in L10n.languages.entries)
                ListTile(
                  minTileHeight: 64,
                  leading: Icon(L10n.instance.code == e.key ? Icons.radio_button_checked : Icons.radio_button_off),
                  title: Text(e.value, style: const TextStyle(fontSize: 22)),
                  onTap: () {
                    L10n.instance.set(e.key);
                    Navigator.pop(c);
                  },
                ),
              const SizedBox(height: 12),
            ]),
          ),
        ),
      );
}
