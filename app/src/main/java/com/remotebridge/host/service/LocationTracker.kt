package com.remotebridge.host.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 📍 متتبّع سجل مواقع الهاتف المضيف (RemoteBridge):
 *  - GPS + شبكة عبر LocationManager (بدون مكتبات خارجية).
 *  - حلقة تخزين حلقية (آخر 400 نقطة) داخل ملف التطبيق الخاص.
 *  - نبضة كل 3 دقائق: تحديث النقطة + استدعاء onTick لتصل اللوحة عبر push_event
 *    ويزود الخادم بنقاط الحالة (snapshot) للوصول أثناء عدم الاتصال.
 */
class LocationTracker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTick: (JsonObject) -> Unit
) {
    companion object {
        private const val TAG = "LocationTracker"
        private const val PREFS = "rb_location"
        private const val KEY_POINTS = "points_json"
        private const val KEY_LAST = "last_json"
        private const val MAX_POINTS = 400
        const val HEARTBEAT_MS = 3L * 60L * 1000L   // كل 3 دقائق
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val points = mutableListOf<JsonObject>()
    private var started = false
    private var heartRunnable: Runnable? = null
    private var listenerRef: LocationListener? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isRunning(): Boolean = started

    fun start(): Boolean {
        if (started) return true
        if (!hasPermission()) {
            Log.w(TAG, "إذن الموقع غير ممنوح — تعذر بدء التتبع")
            return false
        }
        loadPrefs()
        started = true
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { addPoint(location) }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listenerRef = listener
        try {
            if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 15f, listener, Looper.getMainLooper())
            }
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 15f, listener, Looper.getMainLooper())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر تسجيل مستمعي الموقع: ${t.message}")
        }

        // إضافة نقطة أولية
        try {
            lastKnown()?.let { addPoint(it) }
        } catch (_: Throwable) {}

        // نبضة كل 3 دقائق
        scheduleHeart()
        return true
    }

    fun stop() {
        started = false
        try { heartRunnable?.let { mainHandler.removeCallbacks(it) } } catch (_: Throwable) {}
        try { listenerRef?.let { lm.removeUpdates(it) } } catch (_: Throwable) {}
        persist()
    }

    fun getPoints(limit: Int = 200): List<JsonObject> = points.take(limit.coerceIn(1, MAX_POINTS))

    fun lastPoint(): JsonObject? = points.firstOrNull()

    fun clear() {
        synchronized(points) { points.clear() }
        persist()
    }

    // ==========================================================
    //                    الأدوات الداخلية
    // ==========================================================
    private fun scheduleHeart() {
        heartRunnable?.let { mainHandler.removeCallbacks(it) }
        heartRunnable = Runnable {
            try {
                // نبضة: نقطة فورية إن أمكن، ثم دفع الحدسي للخدمة
                if (!started) return@Runnable
                if (points.isEmpty()) {
                    lastKnown()?.let { addPoint(it) }
                }
                val last = points.firstOrNull()
                if (last != null) {
                    try { onTick(last) } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.w(TAG, "خطأ نبضة الموقع: ${t.message}")
            } finally {
                if (started) mainHandler.postDelayed(heartRunnable!!, HEARTBEAT_MS)
            }
        }.also { mainHandler.postDelayed(it, 20_000L) }
    }

    private fun lastKnown(): Location? = try {
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        providers.firstOrNull { p ->
            try {
                p in lm.allProviders && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) { false }
        }?.let { lm.getLastKnownLocation(it) }
    } catch (_: Throwable) { null }

    private fun addPoint(loc: Location) {
        try {
            val p = buildJsonObject {
                put("lat", loc.latitude)
                put("lng", loc.longitude)
                put("ts", loc.time)
                put("acc", loc.accuracy.toDouble())
                put("speed", loc.speed.toDouble())
                put("provider", loc.provider ?: "")
                put("deviceTs", System.currentTimeMillis())
            }
            synchronized(points) {
                // تجاهل نقطة مطابقة تقريباً للأخيرة (لم يتحرك المستخدم بشكل ملحوظ)
                val prev = points.firstOrNull()
                val sameSpot = prev != null &&
                    pointValue(prev, "lat") == loc.latitude &&
                    pointValue(prev, "lng") == loc.longitude &&
                    (System.currentTimeMillis() - (pointLong(prev, "ts") ?: 0L)) < 90_000L
                if (!sameSpot) {
                    points.add(0, p)
                    while (points.size > MAX_POINTS) points.removeAt(points.size - 1)
                } else {
                    // فقط حدّث الطابع الزمني
                    points[0] = buildJsonObject {
                        put("lat", loc.latitude); put("lng", loc.longitude); put("ts", loc.time)
                        put("acc", loc.accuracy.toDouble()); put("speed", loc.speed.toDouble())
                        put("provider", loc.provider ?: ""); put("deviceTs", System.currentTimeMillis())
                    }
                }
            }
            persist()
        } catch (t: Throwable) {
            Log.w(TAG, "فشل إضافة نقطة موقع: ${t.message}")
        }
    }

    private fun pointValue(o: JsonObject, k: String): Double? =
        (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun pointLong(o: JsonObject, k: String): Long? =
        (o[k] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            try {
                val json = kotlinx.serialization.json.JsonArray(
                    synchronized(points) { points.toList() }.map { it as kotlinx.serialization.json.JsonElement }
                )
                val text = kotlinx.serialization.json.Json.encodeToString(
                    ListSerializer(kotlinx.serialization.json.JsonElement.serializer()), json
                )
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_POINTS, text)
                    .putString(KEY_LAST, lastPoint()?.let {
                        kotlinx.serialization.json.Json.encodeToString(
                            kotlinx.serialization.json.JsonObject.serializer(), it as kotlinx.serialization.json.JsonObject
                        )
                    })
                    .apply()
            } catch (t: Throwable) {
                Log.w(TAG, "فشل حفظ نقاط الموقع: ${t.message}")
            }
        }
    }

    private fun loadPrefs() {
        try {
            val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POINTS, null)
            if (!text.isNullOrBlank()) {
                val arr = kotlinx.serialization.json.Json.decodeFromString(
                    ListSerializer(kotlinx.serialization.json.JsonElement.serializer()), text
                )
                synchronized(points) {
                    points.clear()
                    arr.forEach { e -> if (e is JsonObject) points.add(e) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "فشل تحميل نقاط الموقع: ${t.message}")
        }
    }
}
