package aerial.pen.switcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.KEY_ENABLED, false)) {
            try {
                context.startService(Intent(context, OverlayService::class.java))
            } catch (t: Throwable) {
            }
            // 开机阶段 appops 可能还没就绪，延迟再拉起一次
            try {
                val retry = Intent(context, StartReceiver::class.java)
                val pending = PendingIntent.getBroadcast(context, 0, retry, PendingIntent.FLAG_UPDATE_CURRENT)
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 10000L,
                    pending,
                )
            } catch (t: Throwable) {
            }
        }
        startGestureIfReady(context)
    }
}

class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.KEY_ENABLED, false)) {
            try {
                context.startService(Intent(context, OverlayService::class.java))
            } catch (t: Throwable) {
            }
        }
        startGestureIfReady(context)
    }
}

private fun startGestureIfReady(context: Context) {
    val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(Prefs.KEY_GESTURE, false)) return
    if (!Settings.canDrawOverlays(context)) return
    val services = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: ""
    val enabled = Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED,
        0,
    ) == 1
    val component = "${context.packageName}/${GestureAccessibilityService::class.java.name}"
    if (enabled && services.contains(component)) GestureService.start(context)
}
