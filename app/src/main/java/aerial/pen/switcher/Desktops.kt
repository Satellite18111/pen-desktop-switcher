package aerial.pen.switcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent

data class DesktopTarget(
    val pkg: String,
    val activity: String,
    val label: String,
) {
    val key: String get() = "$pkg/$activity"
}

object Desktops {

    fun load(context: Context): List<DesktopTarget> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = pm.queryIntentActivities(intent, 0)
        val out = ArrayList<DesktopTarget>()
        val seen = HashSet<String>()

        for (info in resolved) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) continue
            val name = info.activityInfo.name
            if (name.contains("FallbackHome")) continue
            val key = "$pkg/$name"
            if (!seen.add(key)) continue
            val label = try {
                info.loadLabel(pm).toString()
            } catch (t: Throwable) {
                pkg
            }
            out.add(DesktopTarget(pkg, name, label))
        }

        // 非 HOME 桌面（例如 PenDesk）也允许在切换器里出现
        val extras = listOf(
            "aerial.pen" to "aerial.pen.MainActivity",
        )
        for ((pkg, activity) in extras) {
            if (pkg == context.packageName) continue
            val key = "$pkg/$activity"
            if (seen.contains(key)) continue
            try {
                val info = context.packageManager.getActivityInfo(ComponentName(pkg, activity), 0)
                if (context.packageManager.getLaunchIntentForPackage(pkg) != null) {
                    val label = try {
                        info.loadLabel(pm).toString()
                    } catch (t: Throwable) {
                        pkg
                    }
                    out.add(DesktopTarget(pkg, activity, label))
                    seen.add(key)
                }
            } catch (t: Throwable) {
            }
        }

        // 手动添加的桌面
        val extraPrefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val extra = extraPrefs.getStringSet(Prefs.KEY_EXTRA_TARGETS, null) ?: emptySet()
        for (raw in extra) {
            val parts = raw.split("|", limit = 2)
            val component = parts.getOrNull(0) ?: continue
            val label = parts.getOrNull(1) ?: component
            val slash = component.indexOf('/')
            if (slash <= 0 || slash >= component.length - 1) continue
            val pkg = component.substring(0, slash)
            val activity = component.substring(slash + 1)
            if (pkg == context.packageName) continue
            if (seen.contains(component)) continue
            try {
                val info = context.packageManager.getActivityInfo(ComponentName(pkg, activity), 0)
                val resolvedLabel = try {
                    info.loadLabel(pm).toString()
                } catch (t: Throwable) {
                    label
                }
                out.add(DesktopTarget(pkg, activity, resolvedLabel))
                seen.add(component)
            } catch (t: Throwable) {
            }
        }

        return out.sortedBy { it.label }
    }
}

object Prefs {
    const val NAME = "switcher"
    const val KEY_ENABLED = "enabled"
    const val KEY_CORNER = "corner"
    const val KEY_X = "x"
    const val KEY_Y = "y"
    const val KEY_TARGETS = "targets"
    const val KEY_KILL = "kill_bg"
    const val KEY_KILL_MODE = "kill_mode"
    const val KEY_SCALE = "ui_scale"
    const val KEY_SCALE_BALL = "ball_scale"
    const val KEY_SCALE_PANEL = "panel_scale"
    const val KEY_EXTRA_TARGETS = "extra_targets"
    const val KEY_GESTURE = "gesture_nav"

    const val KILL_MODE_DESKTOP = 0
    const val KILL_MODE_ALL = 1

    const val CORNER_CUSTOM = -1
    const val CORNER_TOP_LEFT = 0
    const val CORNER_TOP_RIGHT = 1
    const val CORNER_BOTTOM_LEFT = 2
    const val CORNER_BOTTOM_RIGHT = 3
}
