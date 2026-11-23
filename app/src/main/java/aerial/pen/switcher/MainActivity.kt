package aerial.pen.switcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var permissionButton: TextView
    private lateinit var toggle: TextView
    private val scaleLabels = ArrayList<Pair<String, TextView>>()
    private lateinit var gestureCheck: CheckBox
    private lateinit var gestureStatus: TextView
    private lateinit var gestureButton: TextView
    private var targetsColumn: LinearLayout? = null
    private val cornerButtons = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean(Prefs.KEY_ENABLED, false) &&
            Settings.canDrawOverlays(this) &&
            !OverlayService.alive
        ) {
            startService(Intent(this, OverlayService::class.java))
        }
        refreshUi()
        refreshGestureUi()
        syncGestureService()
    }

    private fun buildUi() {
        val scroller = ScrollView(this)
        scroller.setBackgroundColor(0xFF0B141C.toInt())
        scroller.isVerticalScrollBarEnabled = false

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(14), dp(14), dp(14), dp(20))
        scroller.addView(column, ViewGroup.LayoutParams(-1, -2))

        column.addView(text("pendesktool", 26f, 0xFFFFFFFF.toInt(), true))
        column.addView(text("悬浮按钮 · 桌面互切 · 控制中心", 14f, 0xFF93A6B8.toInt(), false))

        statusView = text("", 14f, 0xFFB9C8D6.toInt(), false)
        statusView.setPadding(0, dp(10), 0, dp(4))
        column.addView(statusView)

        permissionButton = pill("授予悬浮窗权限", 0xFF2F6FE0.toInt(), dp(54), 17f)
        permissionButton.setOnClickListener { openOverlaySettings() }
        column.addView(permissionButton, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(6)
        })

        toggle = pill("开启悬浮按钮", 0xFF2F6FE0.toInt(), dp(62), 19f)
        toggle.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        toggle.setOnClickListener { toggleService() }
        column.addView(toggle, LinearLayout.LayoutParams(-1, dp(58)).apply {
            topMargin = dp(12)
        })

        column.addView(section("跳转时清理后台"))
        val killCheck = CheckBox(this)
        killCheck.text = "跳转后清理后台进程"
        killCheck.setTextColor(0xFFE7EEF7.toInt())
        killCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        killCheck.isChecked = prefs.getBoolean(Prefs.KEY_KILL, false)
        killCheck.buttonTintList = ColorStateList.valueOf(0xFF2F6FE0.toInt())
        killCheck.setPadding(0, 0, 0, 0)

        val killGroup = RadioGroup(this)
        killGroup.orientation = LinearLayout.HORIZONTAL
        val radioDesktop = RadioButton(this).apply {
            id = View.generateViewId()
            text = "仅桌面"
            setTextColor(0xFFE7EEF7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            buttonTintList = ColorStateList.valueOf(0xFF2F6FE0.toInt())
            setPadding(0, 0, dp(16), 0)
        }
        val radioAll = RadioButton(this).apply {
            id = View.generateViewId()
            text = "全部后台"
            setTextColor(0xFFE7EEF7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            buttonTintList = ColorStateList.valueOf(0xFF2F6FE0.toInt())
        }
        killGroup.addView(radioDesktop, LinearLayout.LayoutParams(-2, dp(48)))
        killGroup.addView(radioAll, LinearLayout.LayoutParams(-2, dp(48)))

        val mode = prefs.getInt(Prefs.KEY_KILL_MODE, Prefs.KILL_MODE_DESKTOP)
        killGroup.check(if (mode == Prefs.KILL_MODE_ALL) radioAll.id else radioDesktop.id)
        killGroup.visibility = if (killCheck.isChecked) View.VISIBLE else View.GONE

        killCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_KILL, checked).apply()
            killGroup.visibility = if (checked) View.VISIBLE else View.GONE
        }
        killGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit()
                .putInt(
                    Prefs.KEY_KILL_MODE,
                    if (checkedId == radioAll.id) Prefs.KILL_MODE_ALL else Prefs.KILL_MODE_DESKTOP,
                )
                .apply()
        }

        column.addView(killCheck, LinearLayout.LayoutParams(-1, dp(52)))
        column.addView(killGroup, LinearLayout.LayoutParams(-1, dp(48)))
        column.addView(section("悬浮按钮位置"))

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.HORIZONTAL
        val corners = arrayOf(
            "左上" to Prefs.CORNER_TOP_LEFT,
            "右上" to Prefs.CORNER_TOP_RIGHT,
            "左下" to Prefs.CORNER_BOTTOM_LEFT,
            "右下" to Prefs.CORNER_BOTTOM_RIGHT,
        )
        corners.forEachIndexed { index, item ->
            val button = cornerButton(item.first, item.second)
            grid.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (index > 0) leftMargin = dp(6)
            })
        }
        column.addView(grid)

        column.addView(section("悬浮球与面板大小"))
        column.addView(addScaleRow("悬浮球", Prefs.KEY_SCALE_BALL))
        column.addView(addScaleRow("面板", Prefs.KEY_SCALE_PANEL))
        updateScaleLabels()

        column.addView(section("手势导航"))
        gestureCheck = CheckBox(this)
        gestureCheck.text = "开启手势导航"
        gestureCheck.setTextColor(0xFFE7EEF7.toInt())
        gestureCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gestureCheck.isChecked = prefs.getBoolean(Prefs.KEY_GESTURE, false)
        gestureCheck.buttonTintList = ColorStateList.valueOf(0xFF2F6FE0.toInt())
        gestureCheck.setPadding(0, 0, 0, 0)
        gestureCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_GESTURE, checked).apply()
            if (checked && !accessibilityEnabled()) {
                Toast.makeText(this, "请先在无障碍里开启“pendesktool”", Toast.LENGTH_SHORT).show()
                openAccessibilitySettings()
            }
            syncGestureService()
            refreshGestureUi()
        }
        column.addView(gestureCheck, LinearLayout.LayoutParams(-1, dp(52)))

        gestureStatus = text("", 14f, 0xFF93A6B8.toInt(), false)
        gestureStatus.setPadding(0, dp(4), 0, 0)
        column.addView(gestureStatus)

        gestureButton = pill("开启无障碍服务", 0xFF2F6FE0.toInt(), dp(54), 17f)
        gestureButton.setOnClickListener { openAccessibilitySettings() }
        column.addView(gestureButton, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(8)
        })

        column.addView(
            text(
                "左右边缘向内滑动返回；底部边缘向上滑动打开任务页面。",
                12.5f,
                0xFF8FA2B4.toInt(),
                false,
            ).apply { setPadding(0, dp(8), 0, 0) },
        )

        column.addView(section("可切换的桌面"))
        val targets = LinearLayout(this)
        targets.orientation = LinearLayout.VERTICAL
        targetsColumn = targets
        column.addView(targets)

        val all = Desktops.load(this)
        val chosen = prefs.getStringSet(Prefs.KEY_TARGETS, null)
        if (all.isEmpty()) {
            targets.addView(text("没有找到其他桌面", 14f, 0xFF93A6B8.toInt(), false))
        }
        all.forEach { target ->
            val check = CheckBox(this)
            check.text = target.label
            check.setTextColor(0xFFE7EEF7.toInt())
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            check.isChecked = chosen == null || chosen.contains(target.key)
            check.tag = target.key
            check.buttonTintList = ColorStateList.valueOf(0xFF2F6FE0.toInt())
            check.setPadding(0, 0, 0, 0)
            check.setOnCheckedChangeListener { _, _ -> saveTargets() }
            check.setOnLongClickListener {
                val custom = prefs.getStringSet(Prefs.KEY_EXTRA_TARGETS, emptySet()) ?: emptySet()
                val match = custom.firstOrNull { it.startsWith(target.key + "|") }
                if (match != null) {
                    val next = custom.toMutableSet()
                    next.remove(match)
                    prefs.edit().putStringSet(Prefs.KEY_EXTRA_TARGETS, next).apply()
                    Toast.makeText(this, "已移除「${target.label}」", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                true
            }
            targets.addView(check, LinearLayout.LayoutParams(-1, dp(52)))
        }

        val addTarget = pill("＋ 添加桌面应用", 0xFF16212C.toInt(), dp(54), 16f)
        addTarget.setOnClickListener { showAddTargetDialog() }
        targets.addView(addTarget, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(8)
        })



        column.addView(
            text(
                "点小球 → 点桌面图标选中 → 再点一次跳转；按住小球可拖动，松手自动贴边。长按小球或点面板右上角「设」可回到本页。屏幕上沿向下滑呼出控制中心，可调亮度、音量，开关 WiFi、蓝牙；面板标题栏「中」也可打开。",
                12.5f,
                0xFF8FA2B4.toInt(),
                false,
            ).apply { setPadding(0, dp(12), 0, 0) },
        )

        setContentView(scroller)
    }

    private fun cornerButton(label: String, corner: Int): TextView {
        val button = pill(label, 0xFF16212C.toInt(), dp(52), 16f)
        button.tag = corner
        cornerButtons.add(button)
        button.setOnClickListener {
            prefs.edit().putInt(Prefs.KEY_CORNER, corner).apply()
            updateCornerStyles()
            if (prefs.getBoolean(Prefs.KEY_ENABLED, false)) {
                val intent = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_REPOSITION)
                startService(intent)
            }
        }
        return button
    }

    private fun addScaleRow(title: String, key: String): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(3), 0, dp(3))

        val name = text(title, 16f, 0xFFB9C8D6.toInt(), false)
        name.gravity = Gravity.CENTER_VERTICAL

        val minus = pill("－", 0xFF16212C.toInt(), dp(56), 22f)
        minus.setOnClickListener { changeScale(key, -0.1f) }

        val percent = text("", 17.5f, 0xFFE7EEF7.toInt(), true)
        percent.gravity = Gravity.CENTER
        scaleLabels.add(key to percent)

        val plus = pill("＋", 0xFF16212C.toInt(), dp(56), 22f)
        plus.setOnClickListener { changeScale(key, 0.1f) }

        row.addView(name, LinearLayout.LayoutParams(dp(84), dp(56)))
        row.addView(minus, LinearLayout.LayoutParams(dp(56), dp(56)))
        row.addView(percent, LinearLayout.LayoutParams(0, dp(56), 1f))
        row.addView(plus, LinearLayout.LayoutParams(dp(56), dp(56)))
        return row
    }

    private fun scaleValue(key: String): Float = prefs.getFloat(
        key,
        prefs.getFloat(Prefs.KEY_SCALE, 1.2f),
    ).coerceIn(0.8f, 2.0f)

    private fun changeScale(key: String, delta: Float) {
        val current = scaleValue(key)
        val next = (((current + delta) * 10).roundToInt() / 10f).coerceIn(0.8f, 2.0f)
        if (next == current) return
        prefs.edit().putFloat(key, next).apply()
        updateScaleLabels()
        restartOverlay()
    }

    private fun updateScaleLabels() {
        scaleLabels.forEach { (key, view) ->
            view.text = "${(scaleValue(key) * 100).toInt()}%"
        }
    }

    private fun restartOverlay() {
        if (!prefs.getBoolean(Prefs.KEY_ENABLED, false) || !Settings.canDrawOverlays(this)) return
        stopService(Intent(this, OverlayService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            if (prefs.getBoolean(Prefs.KEY_ENABLED, false)) {
                startService(Intent(this, OverlayService::class.java))
            }
        }, 500)
    }

    private fun updateCornerStyles() {
        val current = prefs.getInt(Prefs.KEY_CORNER, Prefs.CORNER_BOTTOM_RIGHT)
        cornerButtons.forEach { button ->
            val corner = button.tag as Int
            val active = corner == current
            button.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (active) 0xFF2F6FE0.toInt() else 0xFF16212C.toInt())
                setStroke(dp(1), if (active) 0xFF5E9BFF.toInt() else 0x33FFFFFF)
            }
        }
    }

    private fun refreshUi() {
        val canDraw = Settings.canDrawOverlays(this)
        statusView.text = if (canDraw) "悬浮窗权限：已授予" else "悬浮窗权限：未授予"
        permissionButton.visibility = if (canDraw) View.GONE else View.VISIBLE

        val enabled = prefs.getBoolean(Prefs.KEY_ENABLED, false)
        toggle.text = if (enabled) "关闭悬浮按钮" else "开启悬浮按钮"
        toggle.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (enabled) 0xFF8A3B3B.toInt() else 0xFF2F6FE0.toInt())
        }
        updateCornerStyles()
    }

    private fun toggleService() {
        val enabled = prefs.getBoolean(Prefs.KEY_ENABLED, false)
        if (enabled) {
            stopService(Intent(this, OverlayService::class.java))
            prefs.edit().putBoolean(Prefs.KEY_ENABLED, false).apply()
            Toast.makeText(this, "悬浮按钮已关闭", Toast.LENGTH_SHORT).show()
            refreshUi()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }

        saveTargets()
        val chosen = prefs.getStringSet(Prefs.KEY_TARGETS, emptySet()) ?: emptySet()
        if (chosen.isEmpty()) {
            Toast.makeText(this, "请先勾选至少一个桌面", Toast.LENGTH_SHORT).show()
            return
        }

        startService(Intent(this, OverlayService::class.java))
        prefs.edit().putBoolean(Prefs.KEY_ENABLED, true).apply()
        Toast.makeText(this, "悬浮按钮已开启，贴在屏幕边角", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun showAddTargetDialog() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        data class Option(val pkg: String, val activity: String, val label: String)

        val existing = Desktops.load(this).map { it.key }.toHashSet()
        val options = resolved
            .filter { it.activityInfo.packageName != packageName }
            .map {
                Option(
                    it.activityInfo.packageName,
                    it.activityInfo.name,
                    try {
                        it.loadLabel(pm).toString()
                    } catch (t: Throwable) {
                        it.activityInfo.packageName
                    },
                )
            }
            .filter { "${it.pkg}/${it.activity}" !in existing }
            .distinctBy { "${it.pkg}/${it.activity}" }
            .sortedBy { it.label }

        if (options.isEmpty()) {
            Toast.makeText(this, "没有可添加的应用", Toast.LENGTH_SHORT).show()
            return
        }

        val names = options.map { "${it.label}\n${it.pkg}" }
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            names,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(0xFFE7EEF7.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(58)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }
                return view
            }
        }
        AlertDialog.Builder(this)
            .setTitle("添加为可切换桌面")
            .setAdapter(adapter) { _, which ->
                val option = options[which]
                val set = prefs.getStringSet(Prefs.KEY_EXTRA_TARGETS, emptySet())?.toMutableSet()
                    ?: mutableSetOf()
                set.add("${option.pkg}/${option.activity}|${option.label}")
                val chosen = prefs.getStringSet(Prefs.KEY_TARGETS, null)?.toMutableSet()
                    ?: mutableSetOf()
                chosen.add("${option.pkg}/${option.activity}")
                prefs.edit()
                    .putStringSet(Prefs.KEY_EXTRA_TARGETS, set)
                    .putStringSet(Prefs.KEY_TARGETS, chosen)
                    .apply()
                Toast.makeText(this, "已添加「${option.label}」", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun accessibilityEnabled(): Boolean {
        val services = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: ""
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        return enabled && services.contains(
            "$packageName/${GestureAccessibilityService::class.java.name}",
        )
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "在列表里找到“pendesktool”并开启", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "打不开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshGestureUi() {
        val want = prefs.getBoolean(Prefs.KEY_GESTURE, false)
        gestureCheck.isChecked = want
        val ready = accessibilityEnabled()
        gestureStatus.text = if (ready) {
            "无障碍服务：已开启"
        } else {
            "无障碍服务：未开启，手势需要它才能返回和打开任务"
        }
        gestureButton.visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun syncGestureService() {
        val want = prefs.getBoolean(Prefs.KEY_GESTURE, false)
        if (want && Settings.canDrawOverlays(this) && accessibilityEnabled()) {
            GestureService.start(this)
        } else {
            GestureService.stop(this)
        }
    }

    private fun saveTargets() {
        val column = targetsColumn ?: return
        val set = HashSet<String>()
        for (i in 0 until column.childCount) {
            val view = column.getChildAt(i)
            if (view is CheckBox) {
                val key = view.tag as? String
                if (view.isChecked && key != null) set.add(key)
            }
        }
        prefs.edit().putStringSet(Prefs.KEY_TARGETS, set).apply()
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.data = Uri.parse("package:$packageName")
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "请在系统设置中授予悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun section(title: String): TextView {
        val view = text(title, 15f, 0xFFFFFFFF.toInt(), true)
        view.setPadding(0, dp(16), 0, dp(6))
        return view
    }

    private fun text(text: String, size: Float, color: Int, bold: Boolean): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        view.setTextColor(color)
        if (bold) view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        return view
    }

    private fun pill(label: String, color: Int, height: Int, textSize: Float = 16f): TextView {
        val view = TextView(this)
        view.text = label
        view.setTextColor(Color.WHITE)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        view.gravity = Gravity.CENTER
        view.isClickable = true
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(color)
        }
        view.layoutParams = LinearLayout.LayoutParams(-1, height)
        return view
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}



