package com.choury.bridgemcp.caps

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.choury.bridgemcp.BridgeApp
import com.choury.bridgemcp.CapHandler
import com.choury.bridgemcp.Capability
import com.choury.bridgemcp.CapResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// device.status / device.info / device.permissions / device.health / device.apps
// 返回结构对齐 OpenClaw Android 的同名命令。
object DeviceCapability {

    private const val NO_ARGS_SCHEMA = """{"type": "object"}"""

    private val APPS_SCHEMA = """{
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "case-insensitive match on label or packageName"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 200, "default": 100},
            "includeSystem": {"type": "boolean", "default": false},
            "includeDisabled": {"type": "boolean", "default": false},
            "includeNonLaunchable": {"type": "boolean", "default": false}
        }
    }"""

    fun register(add: (Capability, CapHandler) -> Unit) {
        add(
            Capability(
                name = "device.status",
                description = "Battery level/state, thermal state, storage usage, network status and monotonic uptime of this phone.",
                inputSchema = NO_ARGS_SCHEMA,
            ),
            { _, _ -> CapResult.Json(status()) },
        )
        add(
            Capability(
                name = "device.info",
                description = "Static device identity: name, model, OS version, app version, locale.",
                inputSchema = NO_ARGS_SCHEMA,
            ),
            { _, _ -> CapResult.Json(info()) },
        )
        add(
            Capability(
                name = "device.permissions",
                description = "Current grant state of the permissions this provider needs (location, notifications, notificationListener, storage).",
                inputSchema = NO_ARGS_SCHEMA,
            ),
            { _, _ -> CapResult.Json(permissions()) },
        )
        add(
            Capability(
                name = "device.health",
                description = "Memory pressure, battery health/temperature, doze & power save state, security patch level.",
                inputSchema = NO_ARGS_SCHEMA,
            ),
            { _, _ -> CapResult.Json(health()) },
        )
        add(
            Capability(
                name = "device.apps",
                description = "Installed apps. Defaults to launcher-visible apps; includeNonLaunchable widens to every visible package (the app declares QUERY_ALL_PACKAGES).",
                inputSchema = APPS_SCHEMA,
            ),
            { args, _ -> CapResult.Json(apps(args)) },
        )
    }

    private fun batteryManager() =
        BridgeApp.instance.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private fun batteryIntent(): android.content.Intent? =
        BridgeApp.instance.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun status(): JSONObject {
        val ctx = BridgeApp.instance
        val intent = batteryIntent()

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val state = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "unplugged"
            else -> "unknown"
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = thermalState()

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val freeBytes = stat.availableBytes

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val interfaces = JSONArray()
        var isExpensive = false
        var satisfied = false
        if (caps != null) {
            satisfied = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            isExpensive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).not()
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> interfaces.put("wifi")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> interfaces.put("cellular")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> interfaces.put("wired")
                else -> interfaces.put("other")
            }
        }

        return JSONObject()
            .put("battery", JSONObject()
                .put("level", if (percent < 0) JSONObject.NULL else percent / 100.0)
                .put("levelPercent", if (percent < 0) JSONObject.NULL else percent)
                .put("state", state)
                .put("lowPowerModeEnabled", pm.isPowerSaveMode))
            .put("thermal", JSONObject().put("state", thermal))
            .put("storage", JSONObject()
                .put("totalBytes", totalBytes)
                .put("freeBytes", freeBytes)
                .put("usedBytes", totalBytes - freeBytes))
            .put("network", JSONObject()
                .put("status", if (caps == null) "unsatisfied" else if (satisfied) "satisfied" else "requiresConnection")
                .put("isExpensive", isExpensive)
                .put("isConstrained", false)
                .put("interfaces", interfaces))
            .put("uptimeSeconds", SystemClock.uptimeMillis() / 1000)
    }

    private fun thermalState(): String {
        if (Build.VERSION.SDK_INT < 29) return "unknown"
        val pm = BridgeApp.instance.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "nominal"
            PowerManager.THERMAL_STATUS_LIGHT -> "fair"
            PowerManager.THERMAL_STATUS_MODERATE -> "serious"
            else -> "critical"
        }
    }

    private fun info(): JSONObject {
        val ctx = BridgeApp.instance
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return JSONObject()
            .put("deviceName", Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL)
            .put("modelIdentifier", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("systemName", "Android")
            .put("systemVersion", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("appVersion", pkg.versionName ?: "")
            .put("appBuild", if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode)
            .put("locale", Locale.getDefault().toLanguageTag())
    }

    private fun permEntry(granted: Boolean): JSONObject =
        JSONObject().put("status", if (granted) "granted" else "denied").put("promptable", !granted)

    private fun permissions(): JSONObject {
        val ctx = BridgeApp.instance
        fun granted(p: String) =
            ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return JSONObject().put(
            "permissions",
            JSONObject()
                .put("location", permEntry(granted(Manifest.permission.ACCESS_FINE_LOCATION)))
                .put("notifications", permEntry(
                    Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)))
                .put("notificationListener", permEntry(BridgeNotificationListener.isEnabled(ctx)))
                .put("storage", permEntry(
                    Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()))
        )
    }

    private fun health(): JSONObject {
        val ctx = BridgeApp.instance
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ratio = if (mem.totalMem > 0) mem.availMem.toDouble() / mem.totalMem else 0.0
        val pressure = when {
            ratio <= 0.05 -> "critical"
            ratio <= 0.15 -> "high"
            ratio <= 0.30 -> "moderate"
            else -> "normal"
        }

        val intent = batteryIntent()
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargingType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
            else -> "none"
        }
        val tempTenth = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (tempTenth == Int.MIN_VALUE) JSONObject.NULL else tempTenth / 10.0
        val currentUa = batteryManager().getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = if (currentUa == Int.MIN_VALUE) JSONObject.NULL else currentUa / 1000.0

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("memory", JSONObject()
                .put("pressure", pressure)
                .put("totalRamBytes", mem.totalMem)
                .put("availableRamBytes", mem.availMem)
                .put("usedRamBytes", mem.totalMem - mem.availMem)
                .put("thresholdBytes", mem.threshold)
                .put("lowMemory", mem.lowMemory))
            .put("battery", JSONObject()
                .put("state", if (plugged != 0) "charging" else "unplugged")
                .put("chargingType", chargingType)
                .put("temperatureC", tempC)
                .put("currentMa", currentMa))
            .put("power", JSONObject()
                .put("dozeModeEnabled", pm.isDeviceIdleMode)
                .put("lowPowerModeEnabled", pm.isPowerSaveMode))
            .put("system", JSONObject().put("securityPatchLevel", Build.VERSION.SECURITY_PATCH))
    }

    private fun apps(args: JSONObject): JSONObject {
        val ctx = BridgeApp.instance
        val pm = ctx.packageManager
        val query = args.optString("query").lowercase()
        val limit = args.optInt("limit", 100).coerceIn(1, 200)
        val includeSystem = args.optBoolean("includeSystem", false)
        val includeDisabled = args.optBoolean("includeDisabled", false)
        val includeNonLaunchable = args.optBoolean("includeNonLaunchable", false)

        val launcherPackages = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapTo(mutableSetOf()) { it.activityInfo.packageName }

        val infos: List<ApplicationInfo> = if (includeNonLaunchable) {
            pm.getInstalledPackages(PackageManager.GET_META_DATA).mapNotNull { it.applicationInfo }
        } else {
            launcherPackages.mapNotNull { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
        }

        val rows = infos.asSequence()
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { includeDisabled || it.enabled }
            .mapNotNull { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull() ?: return@mapNotNull null
                info to label
            }
            .filter { (info, label) ->
                query.isBlank() || label.lowercase().contains(query) || info.packageName.contains(query)
            }
            .sortedWith(compareBy({ it.second.lowercase() }, { it.first.packageName }))
            .toList()

        val totalMatched = rows.size
        val apps = JSONArray()
        for ((info, label) in rows.take(limit)) {
            apps.put(
                JSONObject()
                    .put("label", label)
                    .put("packageName", info.packageName)
                    .put("system", (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                    .put("enabled", info.enabled)
                    .put("launchable", info.packageName in launcherPackages)
            )
        }
        return JSONObject()
            .put("count", apps.length())
            .put("totalMatched", totalMatched)
            .put("truncated", totalMatched > apps.length())
            .put("visibility", if (includeNonLaunchable) "android-visible" else "launcher")
            .put("includeSystem", includeSystem)
            .put("includeDisabled", includeDisabled)
            .put("apps", apps)
    }
}
