package com.choury.bridgemcp.caps

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import com.choury.bridgemcp.BridgeApp
import com.choury.bridgemcp.CapHandler
import com.choury.bridgemcp.Capability
import com.choury.bridgemcp.CapResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// location.get：单次定位。max_age_ms 内的新鲜缓存位置直接返回，否则等下一个 fix（30s 超时）；
// 超时（室内无 GPS 常态）回退到 lastKnown 里最新的一条，age_ms 供调用方判断新鲜度。
object LocationCapability {

    private const val SCHEMA = """{
        "type": "object",
        "properties": {
            "max_age_ms": {"type": "integer", "minimum": 0, "default": 0, "description": "reuse the last known location if it is newer than this; 0 always takes a fresh fix"}
        }
    }"""

    fun register(add: (Capability, CapHandler) -> Unit) {
        val handler: CapHandler = { args, _ -> get(args) }
        add(
            Capability(
                name = "location.get",
                description = "Get the current location of this phone. Returns latitude, longitude, accuracy_m, altitude_m, speed_mps, bearing_deg, time_ms, age_ms, cached. If no live fix arrives within 30s (e.g. indoors), the last known location is returned instead (cached=true); check age_ms for staleness.",
                inputSchema = SCHEMA,
            ),
            handler,
        )
    }

    private suspend fun get(args: JSONObject): CapResult {
        val context = BridgeApp.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("location permission not granted; grant it in the app first")
        }
        // agent 调用发生在 app 后台；无「始终允许」时拿不到 fix，直接报错而不是等 30s 超时
        val backgroundAllowed = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!backgroundAllowed && !inForeground()) {
            throw IOException(
                "background location permission not granted; the app is in background — " +
                    "enable「始终允许」in the app"
            )
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        if (providers.isEmpty()) throw IOException("no enabled location provider")

        val maxAge = args.optLong("max_age_ms", 0)
        val now = System.currentTimeMillis()
        if (maxAge > 0) {
            val fresh = bestLastKnown(lm, providers, now, maxAge)
            if (fresh != null) return CapResult.Json(locationJson(fresh, cached = true))
        }

        // 各已启用 provider 上请求单次定位，第一个 fix 胜出
        val fix = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Location> { cont ->
                val done = AtomicBoolean(false)
                val listeners = mutableListOf<LocationListener>()
                fun detach(): Boolean = done.compareAndSet(false, true).also { won ->
                    if (won) for (l in listeners) lm.removeUpdates(l)
                }
                for (provider in providers) {
                    val listener = LocationListener { location -> if (detach()) cont.resume(location) }
                    listeners.add(listener)
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
                cont.invokeOnCancellation { detach() }
            }
        }
        if (fix != null) return CapResult.Json(locationJson(fix, cached = false))

        val stale = bestLastKnown(lm, providers, now, Long.MAX_VALUE)
            ?: throw IOException("no location fix within 30s and no last known location")
        return CapResult.Json(locationJson(stale, cached = true))
    }

    // 各 provider（含 passive——其他 app 的定位常使它最新）的 lastKnown 中取最新一条
    private fun bestLastKnown(lm: LocationManager, providers: List<String>, now: Long, maxAge: Long): Location? =
        (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .filter { now - it.time <= maxAge }
            .maxByOrNull { it.time }

    private fun inForeground(): Boolean {
        val am = BridgeApp.instance.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses.orEmpty().any {
            it.pid == Process.myPid() && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun locationJson(l: Location, cached: Boolean): JSONObject = JSONObject()
        .put("latitude", l.latitude)
        .put("longitude", l.longitude)
        .put("accuracy_m", l.accuracy.toDouble())
        .put("altitude_m", l.altitude)
        .put("speed_mps", l.speed.toDouble())
        .put("bearing_deg", l.bearing.toDouble())
        .put("time_ms", l.time)
        .put("age_ms", System.currentTimeMillis() - l.time)
        .put("cached", cached)
}
