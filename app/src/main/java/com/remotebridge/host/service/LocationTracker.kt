package com.remotebridge.host.service

import android.Manifest
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 📍 B12 — متتبّع مسار الهاتف المضيف بمقاسات مدفوعة:
 *  - محرك فاصل زمني قابل للضبط من المتحكم عبر locations_interval_set:
 *    10ث / 30ث / 1د / 10د / 30د / 1س / 6س / 12س / يوم / أسبوع / شهر.
 *  - سجل نقاط دائم داخل ملفات التطبيق (حلقة FIFO بحد 6000 نقطة).
 *  - أرشيف مسارات دائم: حفظ نقطة/مسار (بداية ← نهاية) باسم قابل للتعديل,
 *    مع أرشفة يومية تلقائية "يوم YYYY-MM-DD" عند تغيّر اليوم.
 *  - كل نقطة تحمل الإحداثيات/الوقت/الدقة/المزوّد، وأحدث نقطة يُدفع
 *    بها حدث مباشر "location" أثناء أي تسجيل جديد.
 */
class LocationTracker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTick: (JsonObject) -> Unit
) {
    companion object {
        private const val TAG = "LocationTracker"
        private const val PREFS = "rb_location"
        private const val KEY_POINTS = "points_json"     // توافق مع الإصدارات القديمة
        private const val KEY_LAST = "last_json"
        private const val KEY_INTERVAL = "interval_sec"
        private const val KEY_LAST_AUTO = "last_auto_ts"
        private const val LOG_FILE = "rb_locations_log.json"
        private const val ARCHIVE_DIR = "rb_locations"
        private const val META_FILE = "archives_meta.json"
        const val MAX_POINTS = 6000
        const val MAX_ARCHIVES = 64
        const val HEARTBEAT_MS = 3L * 60L * 1000L
        /** سلّم الفواصل المسموح به (ثواني): 10ث، 30ث، 1د، 10د، 30د، 1س، 6س، 12س، يوم، أسبوع، شهر */
        val LADDER = longArrayOf(10L, 30L, 60L, 600L, 1800L, 3600L, 21600L, 43200L, 86400L, 604800L, 2592000L)
        const val MONTH_SECONDS = 2592000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val points = mutableListOf<JsonObject>()     // الأحدث أولاً
    @Volatile private var intervalSec: Long = 30L
    private var started = false
    private var heartRunnable: Runnable? = null
    private var tickRunnable: Runnable? = null
    private var listenerRef: LocationListener? = null
    @Volatile private var lastCommitTs: Long = 0L
    @Volatile private var lastAutoTs: Long = 0L
    @Volatile private var lastCandidate: Location? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isRunning(): Boolean = started

    // ==================== الفاصل الزمني ====================

    fun getIntervalSec(): Long = intervalSec

    /**
     * ضبط فاصل تسجيل النقاط بالثواني (يُقيَّد إلى [10 ثانية .. شهر]).
     * دعم كل قيمة داخل المدى وليس سلّم LADDER فقط، ليعمل مع أي مطابقة مستقبلية.
     */
    fun setInterval(secondsRF: Long): Long {
        val sec = secondsRF.coerceIn(10L, MONTH_SECONDS)
        intervalSec = sec
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_INTERVAL, sec).apply()
        } catch (_: Throwable) {}
        if (started) {
            registerUpdatesCompat()
            scheduleTick()
        }
        Log.i(TAG, "فاصل تسجيل النقاط: $sec ثانية")
        return sec
    }

    // ==================== دورة الحياة ====================

    fun start(): Boolean {
        if (started) return true
        if (!hasPermission()) {
            Log.w(TAG, "إذن الموقع غير ممنوح — تعذر بدء التتبع")
            return false
        }
        loadInterval()
        loadLog()
        started = true
        registerUpdatesCompat()

        // نقطة أولية فورية
        try {
            lastKnown()?.let { commit(it, System.currentTimeMillis()) }
        } catch (_: Throwable) {}

        scheduleHeart()
        scheduleTick()
        return true
    }

    fun stop() {
        started = false
        try { heartRunnable?.let { mainHandler.removeCallbacks(it) } } catch (_: Throwable) {}
        try { tickRunnable?.let { mainHandler.removeCallbacks(it) } } catch (_: Throwable) {}
        try { listenerRef?.let { lm.removeUpdates(it) } } catch (_: Throwable) {}
        persist()
    }

    // ==================== استعلامات السجل ====================

    fun getPoints(limit: Int = 200): List<JsonObject> =
        synchronized(points) { points.take(limit.coerceIn(1, MAX_POINTS)) }

    fun queryPoints(sinceMs: Long?, untilMs: Long?, limit: Int = MAX_POINTS): List<JsonObject> {
        val from = sinceMs ?: Long.MIN_VALUE
        val to = untilMs ?: Long.MAX_VALUE
        return synchronized(points) {
            points.filter { p ->
                val ts = pointLong(p, "ts") ?: 0L
                ts >= from && ts <= to
            }.take(limit.coerceIn(1, MAX_POINTS))
        }
    }

    fun lastPoint(): JsonObject? = points.firstOrNull()

    fun clear() {
        synchronized(points) { points.clear() }
        lastCommitTs = 0L
        lastAutoTs = 0L
        try { File(context.filesDir, LOG_FILE).delete() } catch (_: Throwable) {}
        persist()
    }

    // ==================== الأرشيف الدائم ====================

    /**
     * حفظ أرشيف جديد من السجل الحالي. المدى [sinceMs, untilMs] اختياري؛
     * الافتراضي: كل نقاط السجل مرتّبة تصاعدياً من البداية إلى النهاية.
     */
    fun archiveSave(nameParam: String?, sinceMs: Long?, untilMs: Long?): JsonObject? {
        val pts = queryPoints(sinceMs, untilMs, MAX_POINTS).sortedBy { pointLong(it, "ts") ?: 0L }
        if (pts.isEmpty()) return null
        val id = "a" + System.currentTimeMillis()
        val dayName = "يوم " + dayString(pts.first().let { pointLong(it, "ts") ?: 0L })
        val name = nameParam?.takeIf { it.isNotBlank() } ?: dayName
        val meta = buildJsonObject {
            put("id", id)
            put("name", name)
            put("createdAt", System.currentTimeMillis())
            put("startTs", pointLong(pts.first(), "ts") ?: 0L)
            put("endTs", pointLong(pts.last(), "ts") ?: 0L)
            put("count", pts.size)
            put("auto", false)
        }
        return storeArchive(meta, pts)
    }

    fun archiveList(): List<JsonObject> = readMetas()

    fun archivePoints(id: String): List<JsonObject> = try {
        val f = archiveFile(id)
        if (f.exists()) {
            decodeJsonArray(f.readText())
        } else emptyList()
    } catch (t: Throwable) {
        Log.w(TAG, "فشل قراءة نقاط الأرشيف: ${t.message}")
        emptyList()
    }

    fun archiveRename(id: String, newName: String): Boolean {
        if (id.isBlank() || newName.isBlank()) return false
        val metas = readMetas()
        var found = false
        val updated = metas.map { m ->
            if ((pointString(m, "id")) == id) {
                found = true
                val merged = LinkedHashMap(m)
                merged["name"] = JsonPrimitive(newName)
                JsonObject(merged)
            } else m
        }
        return if (found) { writeMetas(updated); true } else false
    }

    fun archiveDelete(id: String): Boolean {
        if (id.isBlank()) return false
        val metas = readMetas()
        val remaining = metas.filter { pointString(it, "id") != id }
        if (remaining.size == metas.size) return false
        try { archiveFile(id).delete() } catch (_: Throwable) {}
        writeMetas(remaining)
        return true
    }

    // ==================== الأرشفة اليومية التلقائية ====================

    /**
     * عند تغيّر اليوم (بمحرك الفاصل أو النبضة): يحفظ كل نقاط اليوم المنقضي
     * في أرشيف تلقائي "يوم YYYY-MM-DD" مرتّباً من بدايته إلى نهايته.
     */
    fun autoArchiveDailyCheck() {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getLong(KEY_LAST_AUTO, 0L)
            val now = System.currentTimeMillis()
            if (stored == 0L) {
                lastAutoTs = now
                prefs.edit().putLong(KEY_LAST_AUTO, now).apply()
                return
            }
            if (dayString(stored) == dayString(now)) return
            val pts = queryPoints(stored, now, MAX_POINTS).sortedBy { pointLong(it, "ts") ?: 0L }
            if (pts.isEmpty()) {
                lastAutoTs = now
                prefs.edit().putLong(KEY_LAST_AUTO, now).apply()
                return
            }
            val name = "يوم " + dayString(pointLong(pts.first(), "ts") ?: stored)
            val id = "a" + System.currentTimeMillis()
            val meta = buildJsonObject {
                put("id", id)
                put("name", name)
                put("createdAt", now)
                put("startTs", pointLong(pts.first(), "ts") ?: stored)
                put("endTs", pointLong(pts.last(), "ts") ?: now)
                put("count", pts.size)
                put("auto", true)
            }
            storeArchive(meta, pts)
            lastAutoTs = now
            prefs.edit().putLong(KEY_LAST_AUTO, now).apply()
            Log.i(TAG, "أُرشيف يومي تلقائي: $name (${pts.size} نقطة)")
        } catch (t: Throwable) {
            Log.w(TAG, "فشل الأرشفة اليومية: ${t.message}")
        }
    }

    // ==========================================================
    //                    الأدوات الداخلية
    // ==========================================================

    private fun registerUpdatesCompat() {
        try { listenerRef?.let { lm.removeUpdates(it) } } catch (_: Throwable) {}
        if (!hasPermission()) return
        val periodMs = (intervalSec * 1000L).coerceAtMost(30_000L).coerceAtLeast(10_000L)
        val distF = if (intervalSec <= 60L) 10f else 100f
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { onFix(location) }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listenerRef = listener
        try {
            if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, periodMs, distF, listener, Looper.getMainLooper())
            }
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, periodMs, distF, listener, Looper.getMainLooper())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر تسجيل مستمعي الموقع: ${t.message}")
        }
    }

    private fun onFix(loc: Location) {
        try {
            lastCandidate = loc
            val now = System.currentTimeMillis()
            val intervalMs = intervalSec * 1000L
            // وعد كل نقطة الهاتف: بنقطة كل فاصل (تسجيل الموافقة المستمرة)
            if (now - lastCommitTs >= intervalMs - 300L) {
                commit(loc, now)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "استهلاك نقطة فشل: ${t.message}")
        }
    }

    /** نبضة الفاصل المستقر: تفرض النقطة في موعدها حتى لو جفت الإصلاحات. */
    private fun scheduleTick() {
        tickRunnable?.let { mainHandler.removeCallbacks(it) }
        val intervalMs = intervalSec * 1000L
        tickRunnable = object : Runnable {
            override fun run() {
                try {
                    if (!started) return
                    val now = System.currentTimeMillis()
                    val due = lastCommitTs == 0L || (now - lastCommitTs >= intervalMs - 300L)
                    if (due) {
                        val loc = lastCandidate ?: lastKnown()
                        if (loc != null) commit(loc, now)
                    }
                    autoArchiveDailyCheck()
                } catch (t: Throwable) {
                    Log.w(TAG, "خطأ نقطة الفاصل: ${t.message}")
                } finally {
                    if (started) mainHandler.postDelayed(this, intervalSec * 1000L)
                }
            }
        }.also { mainHandler.postDelayed(it, intervalSec * 1000L) }
    }

    private fun commit(loc: Location, now: Long) {
        try {
            val p = buildJsonObject {
                put("lat", loc.latitude)
                put("lng", loc.longitude)
                put("ts", loc.time)
                put("acc", loc.accuracy.toDouble())
                put("speed", loc.speed.toDouble())
                put("provider", loc.provider ?: "")
                put("deviceTs", now)
            }
            synchronized(points) {
                points.add(0, p)
                while (points.size > MAX_POINTS) points.removeAt(points.size - 1)
            }
            lastCommitTs = now
            lastCandidate = loc
            try { onTick(p) } catch (_: Throwable) {}
            persist()
        } catch (t: Throwable) {
            Log.w(TAG, "فشل إضافة نقطة موقع: ${t.message}")
        }
    }

    private fun scheduleHeart() {
        heartRunnable?.let { mainHandler.removeCallbacks(it) }
        heartRunnable = object : Runnable {
            override fun run() {
                try {
                    if (!started) return
                    val last = points.firstOrNull()
                        ?: lastKnown()?.let { commit(it, System.currentTimeMillis()); points.firstOrNull() }
                    if (last != null) {
                        try { onTick(last) } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "خطأ نبضة الموقع: ${t.message}")
                } finally {
                    if (started) mainHandler.postDelayed(this, HEARTBEAT_MS)
                }
            }
        }.also { mainHandler.postDelayed(it, 20_000L) }
    }

    private fun lastKnown(): Location? = try {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            if (p !in lm.allProviders) continue
            val ok = try {
                if (fine) true else (coarse && p == LocationManager.NETWORK_PROVIDER)
            } catch (_: Throwable) { false }
            if (!ok) continue
            val l = try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null } ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        best
    } catch (_: Throwable) { null }

    // ==================== التخزين (ملفات تطبيق خاصة) ====================

    private fun dir(): File = File(context.filesDir, ARCHIVE_DIR).apply { mkdirs() }
    private fun metaFile(): File = File(dir(), META_FILE)
    private fun logFile(): File = File(context.filesDir, LOG_FILE)
    private fun archiveFile(id: String): File = File(dir(), "points_$id.json")

    private fun storeArchive(meta: JsonObject, pts: List<JsonObject>): JsonObject {
        return try {
            val id = pointString(meta, "id")
            writeJsonArray(archiveFile(id), pts)
            val metas = readMetas()
            val updated = mutableListOf(meta)
            updated.addAll(metas)
            while (updated.size > MAX_ARCHIVES) {
                val oldest = updated.removeAt(updated.size - 1)
                try { archiveFile(pointString(oldest, "id") ?: "").delete() } catch (_: Throwable) {}
            }
            writeMetas(updated)
            meta
        } catch (t: Throwable) {
            Log.w(TAG, "فشل حفظ الأرشيف: ${t.message}")
            meta
        }
    }

    private fun readMetas(): List<JsonObject> = try {
        val f = metaFile()
        if (f.exists()) decodeJsonArray(f.readText()) else emptyList()
    } catch (_: Throwable) { emptyList() }

    private fun writeMetas(metas: List<JsonObject>) = try {
        writeJsonArray(metaFile(), metas)
    } catch (_: Throwable) {}

    private fun writeJsonArray(f: File, list: List<JsonObject>) {
        val arr = JsonArray(list.map { it as JsonElement })
        val text = kotlinx.serialization.json.Json.encodeToString(
            ListSerializer(JsonElement.serializer()), arr
        )
        f.writeText(text)
    }

    private fun decodeJsonArray(text: String): List<JsonObject> {
        val arr = kotlinx.serialization.json.Json.decodeFromString(
            ListSerializer(JsonElement.serializer()), text
        )
        return arr.filterIsInstance<JsonObject>()
    }

    private fun loadInterval() {
        try {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_INTERVAL, 30L)
            intervalSec = stored.coerceIn(10L, MONTH_SECONDS)
        } catch (_: Throwable) {}
    }

    private fun loadLog() {
        try {
            var pts: List<JsonObject> = emptyList()
            val f = logFile()
            if (f.exists()) {
                pts = decodeJsonArray(f.readText())
            } else {
                // توافق خلفي: اجلب السجل القديم من SharedPreferences
                try {
                    val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POINTS, null)
                    if (!text.isNullOrBlank()) pts = decodeJsonArray(text)
                } catch (_: Throwable) {}
            }
            synchronized(points) {
                points.clear()
                pts.forEach { points.add(it) }
                while (points.size > MAX_POINTS) points.removeAt(points.size - 1)
            }
            try {
                lastAutoTs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_AUTO, 0L)
            } catch (_: Throwable) { lastAutoTs = 0L }
        } catch (t: Throwable) {
            Log.w(TAG, "فشل تحميل نقاط الموقع: ${t.message}")
        }
    }

    private fun persist() {
        val snap = synchronized(points) { points.toList() }
        val last = snap.firstOrNull()
        val logF = logFile()
        scope.launch(Dispatchers.IO) {
            try {
                writeJsonArray(logF, snap)
            } catch (t: Throwable) {
                Log.w(TAG, "فشل حفظ نقاط الموقع: ${t.message}")
            }
            try {
                if (last != null) {
                    val lpText = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(), last
                    )
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_LAST, lpText).apply()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun dayString(tsMs: Long): String =
        try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(tsMs))
        } catch (_: Throwable) { "" }

    private fun pointLong(o: JsonObject, k: String): Long? =
        (o[k] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun pointString(o: JsonObject, k: String): String =
        (o[k] as? JsonPrimitive)?.content ?: ""
}
