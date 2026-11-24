package app.erp.dutygrid_mobile

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Base64
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.security.MessageDigest

/**
 * "dutygrid/sms": self-SMS sign-in until an SMS gateway is connected. The app sends a code by SMS from this
 * phone's own SIM to the typed number and reads it back with the SMS Retriever API (no READ_SMS / RECEIVE_SMS
 * permission; Retriever only hands over messages ending with this app's hash).
 */
class MainActivity : FlutterActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var permResult: MethodChannel.Result? = null
    private var retriever: BroadcastReceiver? = null
    private var smsDone = false
    private var smsText: String? = null
    private var smsWaiter: MethodChannel.Result? = null
    private var waiterTimeout: Runnable? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dutygrid/sms").setMethodCallHandler { call, result ->
            when (call.method) {
                "appHash" -> result.success(appHash())
                "canSendSms" -> result.success(packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
                "hasSendPermission" -> result.success(hasSendPermission())
                "requestSendPermission" -> requestSendPermission(result)
                "startListening" -> startListening(result)
                "awaitSms" -> awaitSms(call.argument<Int>("timeoutSeconds") ?: 60, result)
                "stopListening" -> { stopListening(); result.success(null) }
                "send" -> send(call.argument<String>("to") ?: "", call.argument<String>("text") ?: "", result)
                else -> result.notImplemented()
            }
        }
    }

    /** 11-character SMS Retriever hash of this app (package name + signing certificate). */
    private fun appHash(): String? = try {
        val sig: String? = if (Build.VERSION.SDK_INT >= 28) {
            val si = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            val sigs = if (si == null) null else if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            sigs?.firstOrNull()?.toCharsString()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toCharsString()
        }
        if (sig == null) null else {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("$packageName $sig".toByteArray(Charsets.UTF_8))
            Base64.encodeToString(md.digest().copyOfRange(0, 9), Base64.NO_PADDING or Base64.NO_WRAP).substring(0, 11)
        }
    } catch (e: Exception) {
        null
    }

    private fun hasSendPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun requestSendPermission(result: MethodChannel.Result) {
        if (hasSendPermission()) return result.success(true)
        permResult?.success(false)
        permResult = result
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), REQ_SEND_SMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SEND_SMS) {
            permResult?.success(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            permResult = null
        }
    }

    /** Starts the SMS Retriever (5 minutes, Play services) and keeps the first matching message. */
    private fun startListening(result: MethodChannel.Result) {
        stopListening()
        smsDone = false
        smsText = null
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                val status = if (Build.VERSION.SDK_INT >= 33) extras.getParcelable(SmsRetriever.EXTRA_STATUS, Status::class.java)
                else @Suppress("DEPRECATION") (extras.get(SmsRetriever.EXTRA_STATUS) as? Status)
                when (status?.statusCode) {
                    CommonStatusCodes.SUCCESS -> deliver(extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE))
                    CommonStatusCodes.TIMEOUT -> deliver(null)
                }
            }
        }
        val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        // the broadcast comes from Play services, guarded by its permission (as the SMS Retriever docs show)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, SmsRetriever.SEND_PERMISSION, null, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, filter, SmsRetriever.SEND_PERMISSION, null)
        retriever = r
        SmsRetriever.getClient(this).startSmsRetriever()
            .addOnSuccessListener { result.success(true) }
            .addOnFailureListener { e -> stopListening(); result.error("RETRIEVER", e.message, null) }
    }

    private fun deliver(text: String?) {
        if (smsDone) return
        smsDone = true
        smsText = text
        waiterTimeout?.let { handler.removeCallbacks(it) }
        smsWaiter?.success(text)
        smsWaiter = null
    }

    /** Message text once it arrives, or null after [timeoutSeconds]. */
    private fun awaitSms(timeoutSeconds: Int, result: MethodChannel.Result) {
        if (smsDone) return result.success(smsText)
        smsWaiter?.success(null)
        smsWaiter = result
        val t = Runnable { smsWaiter?.success(null); smsWaiter = null }
        waiterTimeout = t
        handler.postDelayed(t, timeoutSeconds * 1000L)
    }

    private fun stopListening() {
        retriever?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        retriever = null
        waiterTimeout?.let { handler.removeCallbacks(it) }
        smsWaiter?.success(null)
        smsWaiter = null
    }

    /** Sends one SMS from this phone's SIM; answers once the phone's SMS service reports sent / failed. */
    private fun send(to: String, text: String, result: MethodChannel.Result) {
        if (!hasSendPermission()) return result.error("PERMISSION", "SEND_SMS not granted", null)
        val action = "$packageName.SMS_SENT.${System.nanoTime()}"
        var done = false
        lateinit var rcv: BroadcastReceiver
        fun finish(code: String?, message: String?) {
            if (done) return
            done = true
            try { unregisterReceiver(rcv) } catch (_: Exception) {}
            if (code == null) result.success(true) else result.error(code, message, null)
        }
        rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (resultCode) {
                    Activity.RESULT_OK -> finish(null, null)
                    SmsManager.RESULT_ERROR_NO_SERVICE -> finish("NO_SERVICE", "No mobile network")
                    SmsManager.RESULT_ERROR_RADIO_OFF -> finish("RADIO_OFF", "Airplane mode or radio off")
                    else -> finish("SEND_FAILED", "SMS not sent ($resultCode)")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(rcv, IntentFilter(action))
        val sent = PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        try {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendTextMessage(to, null, text, sent, null)
        } catch (e: Exception) {
            finish("SEND_FAILED", e.message)
        }
        handler.postDelayed({ finish("SEND_TIMEOUT", "No answer from the phone's SMS service") }, 30_000)
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    companion object {
        private const val REQ_SEND_SMS = 4711
    }
}
