package aerial.pen.switcher

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
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
    private lateinit var prefs: SharedPreferences
    private val strips = ArrayList<View>()
    private var screenW = 0
    private var screenH = 0
    private var lastActionAt = 0L

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        running = true
        startForegroundCompat()
        rebuildStrips()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) rebuildStrips()
        return START_STICKY
    }

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
        running = false
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
        val guard = dp(5)
        val ball = ballBounds()

        // 左右边缘：球所在的竖条区间留空，保证球优先接收触摸
        val leftBlocked = ball != null && ball.left < edge && ball.right > 0
        val rightBlocked = ball != null && ball.right > screenW - edge && ball.left < screenW
        val leftTop = if (leftBlocked) (ball!!.top - guard).coerceIn(0, screenH) else screenH
        val leftBottom = if (leftBlocked) (ball!!.bottom + guard).coerceIn(0, screenH) else 0
        addStrip(0, 0, edge, leftTop, Direction.RIGHT)
        if (leftBlocked && leftBottom < screenH) {
            addStrip(0, leftBottom, edge, screenH - leftBottom, Direction.RIGHT)
        }

        val rightTop = if (rightBlocked) (ball!!.top - guard).coerceIn(0, screenH) else screenH
        val rightBottom = if (rightBlocked) (ball!!.bottom + guard).coerceIn(0, screenH) else 0
        addStrip(screenW - edge, 0, edge, rightTop, Direction.LEFT)
        if (rightBlocked && rightBottom < screenH) {
            addStrip(screenW - edge, rightBottom, edge, screenH - rightBottom, Direction.LEFT)
        }

        // 底部边缘：球压到底部时，横向让出球所在区间
        val bottomBlocked = ball != null && ball.bottom > screenH - bottom && ball.top < screenH
        val bottomLeft = if (bottomBlocked) (ball!!.left - guard).coerceIn(0, screenW) else screenW
        val bottomRight = if (bottomBlocked) (ball!!.right + guard).coerceIn(0, screenW) else screenW
        addStrip(0, screenH - bottom, bottomLeft, bottom, Direction.UP)
        if (bottomBlocked && bottomRight < screenW) {
            addStrip(bottomRight, screenH - bottom, screenW - bottomRight, bottom, Direction.UP)
        }

        Log.d("GestureNav", "strips rebuilt, ball=$ball")
    }

    private fun ballBounds(): Rect? {
        val dm = resources.displayMetrics
        val density = dm.density
        val scale = prefs.getFloat(
            Prefs.KEY_SCALE_BALL,
            prefs.getFloat(Prefs.KEY_SCALE, 1.2f),
        ).coerceIn(0.8f, 2.0f)
        val w = (26 * density * scale).toInt()
        val h = (54 * density * scale).toInt()
        val corner = prefs.getInt(Prefs.KEY_CORNER, Prefs.CORNER_BOTTOM_RIGHT)
        val x: Int
        val y: Int
        when (corner) {
            Prefs.CORNER_TOP_LEFT -> {
                x = 0
                y = 0
            }
            Prefs.CORNER_TOP_RIGHT -> {
                x = screenW - w
                y = 0
            }
            Prefs.CORNER_BOTTOM_LEFT -> {
                x = 0
                y = screenH - h
            }
            Prefs.CORNER_BOTTOM_RIGHT -> {
                x = screenW - w
                y = screenH - h
            }
            else -> {
                x = prefs.getInt(Prefs.KEY_X, screenW - w)
                y = prefs.getInt(Prefs.KEY_Y, screenH - h)
            }
        }
        if (x < 0 || y < 0 || x > screenW || y > screenH) return null
        return Rect(x, y, x + w, y + h)
    }

    private fun addStrip(x: Int, y: Int, w: Int, h: Int, direction: Direction) {
        if (w <= 0 || h <= 0) return
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
        const val ACTION_REFRESH = "aerial.pen.switcher.GESTURE_REFRESH"

        @Volatile
        var paused = false

        @Volatile
        private var running = false

        fun start(context: Context) {
            context.startService(Intent(context, GestureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureService::class.java))
        }

        /** 悬浮球移动或缩放后调用，让边缘触控条重新避开球。 */
        fun refresh(context: Context) {
            if (!running) return
            try {
                context.startService(
                    Intent(context, GestureService::class.java).setAction(ACTION_REFRESH),
                )
            } catch (t: Throwable) {
            }
        }
    }
}
