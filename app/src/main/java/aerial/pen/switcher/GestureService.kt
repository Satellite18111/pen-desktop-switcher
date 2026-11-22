package aerial.pen.switcher

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs

class GestureService : Service() {

    private lateinit var wm: WindowManager
    private val strips = ArrayList<View>()
    private var screenW = 0
    private var screenH = 0
    private var lastActionAt = 0L

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        rebuildStrips()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildStrips()
    }

    override fun onDestroy() {
        strips.forEach {
            try {
                wm.removeView(it)
            } catch (t: Throwable) {
            }
        }
        strips.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun rebuildStrips() {
        strips.forEach {
            try {
                wm.removeView(it)
            } catch (t: Throwable) {
            }
        }
        strips.clear()

        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val edge = dp(9)
        val bottom = dp(10)

        addStrip(0, 0, edge, screenH, Direction.RIGHT)
        addStrip(screenW - edge, 0, edge, screenH, Direction.LEFT)
        addStrip(0, screenH - bottom, screenW, bottom, Direction.UP)
    }

    private fun addStrip(x: Int, y: Int, w: Int, h: Int, direction: Direction) {
        val view = View(this)
        view.setBackgroundColor(0x01000000)
        view.isClickable = true
        view.setOnTouchListener(EdgeTouch(direction))
        val params = WindowManager.LayoutParams(
            w,
            h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        try {
            wm.addView(view, params)
            strips.add(view)
            Log.d("GestureNav", "strip added dir=$direction at $x,$y ${w}x${h}")
        } catch (t: Throwable) {
            Log.e("GestureNav", "add strip failed", t)
        }
    }

    private enum class Direction { LEFT, RIGHT, UP }

    private inner class EdgeTouch(private val direction: Direction) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var fired = false
        private val threshold = dp(34)

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (paused) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    fired = false
                    Log.d("GestureNav", "down $direction ${downX},${downY}")
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (fired) return true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val horizontal = abs(dx) > threshold && abs(dx) > abs(dy)
                    when (direction) {
                        Direction.RIGHT -> if (horizontal && dx > 0) fireBack()
                        Direction.LEFT -> if (horizontal && dx < 0) fireBack()
                        Direction.UP -> if (dy < -threshold && abs(dy) > abs(dx)) fireRecents()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }

        private fun fireBack() {
            fired = true
            if (!throttle()) return
            Log.d("GestureNav", "fire back, a11y=${GestureAccessibilityService.ready()}")
            if (!GestureAccessibilityService.performBack()) {
                Toast.makeText(this@GestureService, "请先开启切换器的无障碍服务", Toast.LENGTH_SHORT).show()
            }
        }

        private fun fireRecents() {
            fired = true
            if (!throttle()) return
            Log.d("GestureNav", "fire recents, a11y=${GestureAccessibilityService.ready()}")
            if (!GestureAccessibilityService.performRecents()) {
                Toast.makeText(this@GestureService, "请先开启切换器的无障碍服务", Toast.LENGTH_SHORT).show()
            }
        }

        private fun throttle(): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now - lastActionAt < 450L) return false
            lastActionAt = now
            return true
        }
    }

    private fun startForegroundCompat() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL,
                    "手势导航",
                    android.app.NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("手势导航已开启")
            .setContentText("左右边缘返回，底部上滑任务")
            .setOngoing(true)
            .build()
        startForeground(2, notification)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "gesture_nav"

        @Volatile
        var paused = false

        fun start(context: Context) {
            context.startService(Intent(context, GestureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureService::class.java))
        }
    }
}
