package aerial.pen.switcher

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * 控制中心面板：亮度 / 音量 / WiFi / 蓝牙。
 * 由 OverlayService 持有，通过 WindowManager 独立挂载、关闭即移除。
 * 普通应用对 WiFi、蓝牙、亮度的静默开关可能被定制 ROM 拒绝，失败时给出提示并引导到系统设置。
 */
class ControlCenterPanel(
    private val context: Context,
    private val wm: WindowManager,
) {
    private var root: FrameLayout? = null
    private var brightnessBar: SeekBar? = null
    private var volumeBar: SeekBar? = null
    private var wifiBtn: TextView? = null
    private var btBtn: TextView? = null

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val btAdapter = BluetoothAdapter.getDefaultAdapter()

    fun show() {
        if (root != null) return
        val dm = context.resources.displayMetrics
        val w = (340 * dm.density).toInt()
        val h = (178 * dm.density).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        val root = FrameLayout(context)
        val scrim = View(context)
        scrim.setBackgroundColor(0x33000000)
        scrim.isClickable = true
        scrim.setOnClickListener { dismiss() }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val card = buildCard()
        val lp = FrameLayout.LayoutParams(w, h)
        lp.gravity = Gravity.CENTER
        root.addView(card, lp)

        this.root = root
        wm.addView(root, p)
        refresh()
    }

    fun dismiss() {
        root?.let {
            try {
                wm.removeView(it)
            } catch (t: Throwable) {
            }
        }
        root = null
    }

    fun isShowing(): Boolean = root != null

    private fun refresh() {
        // 音量
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeBar?.max = maxVol.coerceAtLeast(1)
        volumeBar?.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // 亮度
        if (canWriteSettings()) {
            val cur = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrDefault(128)
            brightnessBar?.progress = cur.coerceIn(0, 255)
        } else {
            brightnessBar?.progress = 128
        }
        renderWifi()
        renderBt()
    }

    private fun canWriteSettings(): Boolean = try {
        Settings.System.canWrite(context)
    } catch (t: Throwable) {
        false
    }

    private fun buildCard(): View {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(0xF0101A24.toInt())
            setStroke(dp(1), 0x66FFFFFF)
        }

        // 标题栏
        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(context)
        title.text = "控制中心"
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        header.addView(title, LinearLayout.LayoutParams(0, dp(28), 1f))
        val close = pillButton("×", 18f) { dismiss() }
        header.addView(close, LinearLayout.LayoutParams(dp(30), dp(28)))
        card.addView(header, LinearLayout.LayoutParams(-1, dp(28)))

        card.addView(brightnessRow(), LinearLayout.LayoutParams(-1, dp(40)))
        card.addView(volumeRow(), LinearLayout.LayoutParams(-1, dp(40)))
        card.addView(toggleRow(), LinearLayout.LayoutParams(-1, dp(46)))
        return card
    }

    private fun brightnessRow(): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val label = TextView(context)
        label.text = "亮度"
        label.setTextColor(0xFFE7EEF7.toInt())
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        row.addView(label, LinearLayout.LayoutParams(dp(44), -1))
        val bar = SeekBar(context)
        bar.max = 255
        bar.progress = 128
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (!canWriteSettings()) {
                    sb?.progress = 128
                    requestWriteSettings()
                    return
                }
                runCatching {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        progress,
                    )
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        brightnessBar = bar
        row.addView(bar, LinearLayout.LayoutParams(0, -1, 1f))
        return row
    }

    private fun volumeRow(): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val label = TextView(context)
        label.text = "音量"
        label.setTextColor(0xFFE7EEF7.toInt())
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        row.addView(label, LinearLayout.LayoutParams(dp(44), -1))
        val bar = SeekBar(context)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        volumeBar = bar
        row.addView(bar, LinearLayout.LayoutParams(0, -1, 1f))
        return row
    }

    private fun toggleRow(): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val wifi = pillButton("WiFi", 12f) { toggleWifi() }
        wifiBtn = wifi
        row.addView(wifi, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(8) })
        val bt = pillButton("蓝牙", 12f) { toggleBt() }
        btBtn = bt
        row.addView(bt, LinearLayout.LayoutParams(0, dp(38), 1f))
        return row
    }

    private fun toggleWifi() {
        val mgr = wifiMgr
        if (mgr == null) {
            Toast.makeText(context, "没有 WiFi 管理服务", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = runCatching { mgr.setWifiEnabled(!mgr.isWifiEnabled) }.getOrDefault(false)
        if (!ok) {
            Toast.makeText(context, "系统禁止静默切换 WiFi，请在设置里操作", Toast.LENGTH_SHORT).show()
            openWifiSettings()
            return
        }
        renderWifi()
    }

    private fun renderWifi() {
        val on = wifiMgr?.isWifiEnabled == true
        wifiBtn?.let { renderToggle(it, "WiFi", on) }
    }

    private fun toggleBt() {
        val adapter = btAdapter
        if (adapter == null) {
            Toast.makeText(context, "设备没有蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        val want = !adapter.isEnabled
        val ok = runCatching { if (want) adapter.enable() else adapter.disable() }.getOrDefault(false)
        if (!ok) {
            Toast.makeText(context, "系统禁止静默切换蓝牙，请在设置里操作", Toast.LENGTH_SHORT).show()
            openBtSettings()
            return
        }
        renderBt()
    }

    private fun renderBt() {
        val on = btAdapter?.isEnabled == true
        btBtn?.let { renderToggle(it, "蓝牙", on) }
    }

    private fun renderToggle(btn: TextView, name: String, on: Boolean) {
        btn.text = "$name ${if (on) "开" else "关"}"
        btn.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (on) 0xFF2F6FE0.toInt() else 0x1AFFFFFF)
            setStroke(dp(1), if (on) 0xFF5E9BFF.toInt() else 0x26FFFFFF)
        }
        btn.setTextColor(if (on) Color.WHITE else 0xFFB6C4D2.toInt())
    }

    private fun pillButton(label: String, size: Float, action: () -> Unit): TextView {
        val button = TextView(context)
        button.text = label
        button.setTextColor(0xFFE7EEF7.toInt())
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        button.gravity = Gravity.CENTER
        button.isClickable = true
        button.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x1AFFFFFF)
            setStroke(dp(1), 0x26FFFFFF)
        }
        button.setOnClickListener { action() }
        return button
    }

    private fun requestWriteSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            Toast.makeText(context, "请在系统设置里允许修改系统设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWifiSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
        }
    }

    private fun openBtSettings() {
        try {
            context.startActivity(
                Intent("android.settings.BLUETOOTH_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
