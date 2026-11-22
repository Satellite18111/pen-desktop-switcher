package aerial.pen.switcher

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenW = 0
    private var screenH = 0
    private var mode = MODE_COLLAPSED
    private var selected: DesktopTarget? = null
    private var tileViews = ArrayList<Pair<LinearLayout, DesktopTarget>>()
    private var hintView: TextView? = null
    private var volLabel: TextView? = null
    private var panelCard: DragCardLayout? = null
    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0
    private var uiScale = 1.2f
    private var ballScale = 1.2f
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        uiScale = prefs.getFloat(
            Prefs.KEY_SCALE_PANEL,
            prefs.getFloat(Prefs.KEY_SCALE, 1.2f),
        ).coerceIn(0.8f, 2.0f)
        ballScale = prefs.getFloat(
            Prefs.KEY_SCALE_BALL,
            prefs.getFloat(Prefs.KEY_SCALE, 1.2f),
        ).coerceIn(0.8f, 2.0f)
        refreshMetrics()
        startForegroundCompat()
        showCollapsed()
        alive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_REPOSITION -> showCollapsed()
            ACTION_COLLAPSE -> showCollapsed()
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshMetrics()
        if (mode == MODE_PANEL) showPanel() else showCollapsed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        root?.let {
            try {
                wm.removeView(it)
            } catch (t: Throwable) {
            }
        }
        root = null
        alive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshMetrics() {
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "switcher",
                "桌面切换器",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
            Notification.Builder(this, "switcher")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val launch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT)
        builder
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setContentTitle("桌面切换器")
            .setContentText("悬浮按钮运行中")
            .setOngoing(true)
            .setContentIntent(pending)
        startForeground(1, builder.build())
    }

    private fun showCollapsed() {
        GestureService.paused = false
        mode = MODE_COLLAPSED
        selected = null
        panelCard = null
        val p = createParams(ballWidth(), ballHeight())
        positionCollapsed(p)
        attach(buildHandle(), p)
    }

    private fun showPanel() {
        GestureService.paused = true
        mode = MODE_PANEL
        selected = null
        val anchorX = params?.x ?: (screenW - ballWidth())
        val anchorY = params?.y ?: (screenH - ballHeight())
        val items = loadTargets()
        val columns = min(3, max(1, items.size))
        val rows = max(1, (items.size + columns - 1) / columns)
        panelW = min(dp(188), screenW - dp(8))
        panelH = min(dp(62) + rows * dp(56) + dp(28), screenH - dp(8))
        panelX = if (anchorX + ballWidth() / 2 >= screenW / 2) screenW - panelW - dp(2) else dp(2)
        panelY = clampY(anchorY + ballHeight() / 2 - panelH / 2, panelH)
        val p = createParams(screenW, screenH, modal = true)
        attach(buildPanelOverlay(items, columns), p)
    }

    private fun movePanel(left: Int, top: Int) {
        panelX = left.coerceIn(0, max(0, screenW - panelW))
        panelY = top.coerceIn(0, max(0, screenH - panelH))
        panelCard?.let { card ->
            val lp = card.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = panelX
            lp.topMargin = panelY
            card.layoutParams = lp
        }
    }

    private fun persistPanelPosition() {
        val ballW = ballWidth()
        val ballH = ballHeight()
        val x = if (panelX + panelW / 2 < screenW / 2) 0 else screenW - ballW
        val y = (panelY + panelH / 2 - ballH / 2).coerceIn(0, max(0, screenH - ballH))
        prefs.edit()
            .putInt(Prefs.KEY_CORNER, Prefs.CORNER_CUSTOM)
            .putInt(Prefs.KEY_X, x)
            .putInt(Prefs.KEY_Y, y)
            .apply()
    }

    private fun loadTargets(): List<DesktopTarget> {
        val all = Desktops.load(this)
        val chosen = prefs.getStringSet(Prefs.KEY_TARGETS, null) ?: return all
        return all.filter { chosen.contains(it.key) }
    }

    private fun createParams(
        width: Int,
        height: Int,
        modal: Boolean = false,
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!modal) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun attach(view: View, p: WindowManager.LayoutParams) {
        val current = root
        if (current == null) {
            val container = FrameLayout(this)
            container.addView(view, FrameLayout.LayoutParams(-1, -1))
            root = container
            params = p
            wm.addView(container, p)
        } else {
            current.removeAllViews()
            current.addView(view, FrameLayout.LayoutParams(-1, -1))
            params = p
            wm.updateViewLayout(current, p)
        }
    }

    private fun positionCollapsed(p: WindowManager.LayoutParams) {
        val corner = prefs.getInt(Prefs.KEY_CORNER, Prefs.CORNER_BOTTOM_RIGHT)
        val hw = p.width
        val hh = p.height
        when (corner) {
            Prefs.CORNER_TOP_LEFT -> {
                p.x = 0
                p.y = 0
            }
            Prefs.CORNER_TOP_RIGHT -> {
                p.x = screenW - hw
                p.y = 0
            }
            Prefs.CORNER_BOTTOM_LEFT -> {
                p.x = 0
                p.y = screenH - hh
            }
            Prefs.CORNER_BOTTOM_RIGHT -> {
                p.x = screenW - hw
                p.y = screenH - hh
            }
            else -> {
                p.x = prefs.getInt(Prefs.KEY_X, screenW - hw)
                p.y = prefs.getInt(Prefs.KEY_Y, screenH - hh)
            }
        }
        p.x = p.x.coerceIn(0, max(0, screenW - hw))
        p.y = p.y.coerceIn(0, max(0, screenH - hh))
    }

    private fun clampY(value: Int, height: Int): Int = value.coerceIn(0, max(0, screenH - height))

    private fun buildHandle(): View {
        val handle = TextView(this)
        handle.text = "桌"
        handle.setTextColor(0xD9FFFFFF.toInt())
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, spBall(10.5f))
        handle.gravity = Gravity.CENTER
        handle.background = GradientDrawable().apply {
            cornerRadius = dpBall(13).toFloat()
            setColor(0x99121B26.toInt())
            setStroke(dpBall(1), 0x4DFFFFFF)
        }
        handle.setOnTouchListener(DragTouch())
        return handle
    }

    private fun openSwitcherSettings() {
        if (mode == MODE_PANEL) showCollapsed()
        val intent = Intent()
            .setClassName(packageName, "aerial.pen.switcher.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "打不开设置页", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class DragTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longPressed = false
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private val longPress = Runnable {
            if (!moved) {
                longPressed = true
                openSwitcherSettings()
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPress, 550L)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        p.x = (startX + dx).toInt().coerceIn(0, max(0, screenW - p.width))
                        p.y = (startY + dy).toInt().coerceIn(0, max(0, screenH - p.height))
                        root?.let { wm.updateViewLayout(it, p) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longPressed) {
                        showPanel()
                    } else if (moved) {
                        snapAndSave(p)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) snapAndSave(p)
                    return true
                }
            }
            return false
        }
    }

    private fun snapAndSave(p: WindowManager.LayoutParams) {
        p.x = if (p.x + p.width / 2 < screenW / 2) 0 else screenW - p.width
        p.y = p.y.coerceIn(0, max(0, screenH - p.height))
        root?.let { wm.updateViewLayout(it, p) }
        prefs.edit()
            .putInt(Prefs.KEY_CORNER, Prefs.CORNER_CUSTOM)
            .putInt(Prefs.KEY_X, p.x)
            .putInt(Prefs.KEY_Y, p.y)
            .apply()
    }

    private fun buildPanelOverlay(items: List<DesktopTarget>, columns: Int): View {
        val container = FrameLayout(this)
        val scrim = View(this)
        scrim.isClickable = true
        scrim.setBackgroundColor(0x26000000)
        scrim.setOnClickListener { showCollapsed() }
        container.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val card = DragCardLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(8), dp(7), dp(8), dp(7))
        card.background = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(0xFF101A24.toInt())
            setStroke(dp(1), 0x66FFFFFF)
        }
        card.onDragMove = { left, top -> movePanel(left, top) }
        card.onDragEnd = { persistPanelPosition() }
        panelCard = card

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this)
        title.text = "切换桌面"
        title.setTextColor(0xFFFFFFFF.toInt())
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(10.5f))
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        header.addView(title, LinearLayout.LayoutParams(0, dp(22), 1f))

        val settings = TextView(this)
        settings.text = "设"
        settings.setTextColor(0xFF9FB0C0.toInt())
        settings.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(9.5f))
        settings.gravity = Gravity.CENTER
        settings.isClickable = true
        settings.setOnClickListener { openSwitcherSettings() }
        header.addView(settings, LinearLayout.LayoutParams(dp(22), dp(22)))

        val close = TextView(this)
        close.text = "×"
        close.setTextColor(0xFF9FB0C0.toInt())
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14f))
        close.gravity = Gravity.CENTER
        close.isClickable = true
        close.setOnClickListener { showCollapsed() }
        header.addView(close, LinearLayout.LayoutParams(dp(22), dp(22)))
        card.addView(header, LinearLayout.LayoutParams(-1, dp(22)))

        tileViews.clear()
        if (items.isEmpty()) {
            val empty = TextView(this)
            empty.text = "先去桌面切换器勾选桌面"
            empty.setTextColor(0xFF9FB0C0.toInt())
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(9.5f))
            empty.gravity = Gravity.CENTER
            card.addView(empty, LinearLayout.LayoutParams(-1, dp(46)))
        } else {
            val grid = LinearLayout(this)
            grid.orientation = LinearLayout.VERTICAL
            items.chunked(columns).forEach { rowItems ->
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, dp(2), 0, dp(2))
                rowItems.forEach { target ->
                    val tile = buildTile(target)
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                        leftMargin = dp(2)
                        rightMargin = dp(2)
                    })
                    tileViews.add(tile to target)
                }
                for (i in rowItems.size until columns) {
                    val spacer = View(this)
                    row.addView(spacer, LinearLayout.LayoutParams(0, dp(52), 1f))
                }
                grid.addView(row, LinearLayout.LayoutParams(-1, dp(56)))
            }
            card.addView(grid, LinearLayout.LayoutParams(-1, -2))
        }

        card.addView(buildVolumeRow(), LinearLayout.LayoutParams(-1, dp(26)))

        val hint = TextView(this)
        hint.text = "点图标选中，再点一次跳转"
        hint.setTextColor(0xFF9FB0C0.toInt())
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(9.5f))
        hint.gravity = Gravity.CENTER
        hint.isClickable = true
        hint.setBackgroundColor(Color.TRANSPARENT)
        hint.setOnClickListener { selected?.let { jump(it) } }
        hintView = hint
        card.addView(hint, LinearLayout.LayoutParams(-1, dp(26)))

        val lp = FrameLayout.LayoutParams(panelW, panelH)
        lp.leftMargin = panelX
        lp.topMargin = panelY
        container.addView(card, lp)
        return container
    }

    private inner class DragCardLayout(context: Context) : LinearLayout(context) {
        var onDragMove: ((Int, Int) -> Unit)? = null
        var onDragEnd: (() -> Unit)? = null

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startLeft = 0
        private var startTop = 0
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    val lp = layoutParams as? MarginLayoutParams
                    startLeft = lp?.leftMargin ?: left
                    startTop = lp?.topMargin ?: top
                    dragging = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)
                    ) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    return dragging
                }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        onDragMove?.invoke(
                            startLeft + (ev.rawX - downX).toInt(),
                            startTop + (ev.rawY - downY).toInt(),
                        )
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        onDragEnd?.invoke()
                    }
                }
                else -> return super.onTouchEvent(ev)
            }
            return true
        }
    }

    private fun buildVolumeRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(1), 0, dp(1))

        val down = volumeButton("音量－") { adjustVolume(-1) }
        val label = TextView(this)
        label.setTextColor(0xFFE7EEF7.toInt())
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(9.5f))
        label.gravity = Gravity.CENTER
        label.text = currentVolumeText()
        volLabel = label

        val up = volumeButton("音量＋") { adjustVolume(1) }

        row.addView(down, LinearLayout.LayoutParams(0, dp(24), 1f).apply { rightMargin = dp(3) })
        row.addView(label, LinearLayout.LayoutParams(dp(40), dp(24)))
        row.addView(up, LinearLayout.LayoutParams(0, dp(24), 1f).apply { leftMargin = dp(3) })
        return row
    }

    private fun volumeButton(label: String, action: () -> Unit): TextView {
        val button = TextView(this)
        button.text = label
        button.setTextColor(0xFFE7EEF7.toInt())
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(9.5f))
        button.gravity = Gravity.CENTER
        button.isClickable = true
        button.background = GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(0x1AFFFFFF)
            setStroke(dp(1), 0x26FFFFFF)
        }
        button.setOnClickListener { action() }
        return button
    }

    private fun adjustVolume(direction: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
        volLabel?.text = currentVolumeText()
    }

    private fun currentVolumeText(): String {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) cur * 100 / max else 0
        return "$percent%"
    }

    private fun buildTile(target: DesktopTarget): LinearLayout {
        val tile = LinearLayout(this)
        tile.orientation = LinearLayout.VERTICAL
        tile.gravity = Gravity.CENTER
        tile.isClickable = true
        tile.background = tileBackground(false)

        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(target.pkg))
        } catch (t: Throwable) {
        }
        tile.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))

        val label = TextView(this)
        label.text = target.label
        label.setTextColor(0xFFE7EEF7.toInt())
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(9f))
        label.gravity = Gravity.CENTER
        label.isSingleLine = true
        label.ellipsize = TextUtils.TruncateAt.END
        label.setPadding(dp(2), dp(3), dp(2), 0)
        tile.addView(label, LinearLayout.LayoutParams(-1, -2))

        tile.setOnClickListener {
            if (selected == target) {
                jump(target)
            } else {
                select(target)
            }
        }
        return tile
    }

    private fun tileBackground(selectedTile: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        setColor(if (selectedTile) 0xFF2F6FE0.toInt() else 0x1AFFFFFF)
        setStroke(dp(1), if (selectedTile) 0xFF5E9BFF.toInt() else 0x26FFFFFF)
    }

    private fun select(target: DesktopTarget) {
        selected = target
        tileViews.forEach { (tile, item) ->
            tile.background = tileBackground(item == target)
        }
        hintView?.apply {
            text = "确认跳转「${target.label}」"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(0xFF2F6FE0.toInt())
            }
        }
        Toast.makeText(this, "再点一次跳到「${target.label}」", Toast.LENGTH_SHORT).show()
    }

    private fun jump(target: DesktopTarget) {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.setClassName(target.pkg, target.activity)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "打不开「${target.label}」", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed({
            applyKillPolicy(target)
            showCollapsed()
        }, 600)
    }

    private fun applyKillPolicy(target: DesktopTarget) {
        if (!prefs.getBoolean(Prefs.KEY_KILL, false)) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mode = prefs.getInt(Prefs.KEY_KILL_MODE, Prefs.KILL_MODE_DESKTOP)
        try {
            if (mode == Prefs.KILL_MODE_ALL) {
                packageManager.getInstalledApplications(0).forEach { app ->
                    val pkg = app.packageName
                    if (pkg != packageName && pkg != target.pkg &&
                        (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    ) {
                        am.killBackgroundProcesses(pkg)
                    }
                }
            } else {
                Desktops.load(this).forEach { item ->
                    if (item.pkg != target.pkg) am.killBackgroundProcesses(item.pkg)
                }
            }
        } catch (t: Throwable) {
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density * uiScale).toInt()

    private fun sp(value: Float): Float = value * uiScale

    private fun dpBall(value: Int): Int = (value * resources.displayMetrics.density * ballScale).toInt()

    private fun spBall(value: Float): Float = value * ballScale

    private fun ballWidth(): Int = dpBall(26)

    private fun ballHeight(): Int = dpBall(54)

    companion object {
        @Volatile
        var alive = false
        const val ACTION_REPOSITION = "aerial.pen.switcher.REPOSITION"
        const val ACTION_COLLAPSE = "aerial.pen.switcher.COLLAPSE"
        private const val MODE_COLLAPSED = 0
        private const val MODE_PANEL = 1
    }
}





