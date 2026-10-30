package com.remotebridge.host.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.remotebridge.core.model.LocationPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File

/**
 * متتبع الموقع على جانب المضيف:
 * - يجمع إصلاحات GPS/الشبكة في مسار محدود (~250 نقطة) أثناء عمل الخدمة.
 * - يحفظ المسار في ملف مؤقت (cacheDir) كل ~30 ثانية حتى لا تضيع الحركة
 *   بين إعادة تشغيل الخدمة، ويحمل الملف القديم عند البدء (أقل من 24 ساعة).
 * - يجيب على طلبات "location_snapshot" بلقطة الإصلاح الأخير + المسار.
 * لا يعمل أبداً بدون إذن ACCESS_FINE_LOCATION الممنوح للمستخدم يدوياً.
 */
class LocationTracker(private val context: Context) {

    /** نسخة عابرة لتخزين/تحميل المسار من الملف المؤقت. */
    @Serializable
    private data class CachedTrail(val points: List<LocationPoint> = emptyList())

    companion object {
        private const val TAG = "LocationTracker"
        private const val MAX_POINTS = 250
        private const val MIN_TIME_MS = 3000L
        private const val MIN_DISTANCE_M = 5f
        private const val CACHE_FILE = "rb_location_trail.json"
        private const val SAVE_INTERVAL_MS = 30_000L
        private const val MAX_CACHE_AGE_MS = 24L * 60 * 60 * 1000

        /** الإذن الدقيق ممنوح لهذا التطبيق؟ */
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val trail = ArrayList<LocationPoint>(MAX_POINTS)
    private var lastFix: LocationPoint? = null
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            record(location)
        }
    }

    private val saver = object : Runnable {
        override fun run() {
            try {
                saveTrail()
            } catch (_: Throwable) {
            }
            main.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    /** يبدأ الجمع إذا الإذن ممنوح. يعيد true إذا بدأ فعلاً. */
    fun start(): Boolean {
        val lm = manager ?: return false
        if (!hasPermission(context)) {
            Log.w(TAG, "بدون إذن الموقع الدقيق — لا يبدأ التتبع")
            return false
        }
        if (started) return true
        started = true
        loadTrail()

        // إصلاح فوري من آخر موقع معروف قبل انتظار التحديثات
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            try {
                val loc: Location = lm.getLastKnownLocation(provider) ?: continue
                record(loc)
                break
            } catch (_: Throwable) {
            }
        }

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.requestLocationUpdates(
                    provider, MIN_TIME_MS, MIN_DISTANCE_M, listener, main.looper
                )
            } catch (t: Throwable) {
                Log.d(TAG, "تعذر استخدام مزود $provider: ${t.message}")
            }
        }
        main.postDelayed(saver, SAVE_INTERVAL_MS)
        Log.i(TAG, "بدأ تتبع الموقع")
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            manager?.removeUpdates(listener)
        } catch (_: Throwable) {
        }
        main.removeCallbacks(saver)
        saveTrail()
        Log.i(TAG, "توقف تتبع الموقع")
    }

    /** حمولة رد RPC كاملة: snapshot الإصلاح الأخير + trail المسار. */
    fun snapshotPayload(): JsonObject = synchronized(lock) {
        buildJsonObject {
            put(
                "snapshot",
                lastFix?.let { p ->
                    buildJsonObject {
                        put("lat", p.lat)
                        put("lng", p.lng)
                        put("ts", p.ts)
                        p.src?.let { put("src", it) }
                    }
                } ?: JsonNull
            )
            put(
                "trail",
                try {
                    Json.encodeToJsonElement(
                        ListSerializer(LocationPoint.serializer()),
                        trail.toList()
                    )
                } catch (_: Throwable) {
                    JsonArray(emptyList())
                }
            )
        }
    }

    private fun record(location: Location) {
        val point = LocationPoint(
            lat = location.latitude,
            lng = location.longitude,
            ts = if (location.time > 0) location.time else System.currentTimeMillis(),
            src = location.provider
        )
        synchronized(lock) {
            lastFix = point
            trail.add(point)
            while (trail.size > MAX_POINTS) trail.removeAt(0)
        }
    }

    private fun cacheFile(): File = File(context.cacheDir, CACHE_FILE)

    private fun loadTrail() {
        val file = cacheFile()
        if (!file.exists()) return
        try {
            if (System.currentTimeMillis() - file.lastModified() > MAX_CACHE_AGE_MS) {
                file.delete()
                return
            }
            val cached = json.decodeFromString(CachedTrail.serializer(), file.readText())
            synchronized(lock) {
                trail.clear()
                trail.addAll(cached.points.takeLast(MAX_POINTS))
                lastFix = cached.points.lastOrNull()
            }
        } catch (t: Throwable) {
            Log.d(TAG, "تعذر تحميل المسار القديم: ${t.message}")
        }
    }

    private fun saveTrail() {
        try {
            val points = synchronized(lock) { trail.toList() }
            if (points.isEmpty()) return
            cacheFile().writeText(json.encodeToString(CachedTrail.serializer(), CachedTrail(points)))
        } catch (t: Throwable) {
            Log.d(TAG, "تعذر حفظ المسار: ${t.message}")
        }
    }
}
