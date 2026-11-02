package com.remotebridge.host.capture

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مخزن الأوامر المجدولة: يُحفظ في SharedPreferences بصيغة JSON ليصمد عبر
 * إعادة تشغيل الخدمة، وكل「دورة فحص (Tick)」في الخادم يستدعي dueCommands()
 * لجلب المستحقات وتنفيذها ثم إزالتها.
 */
@Serializable
data class ScheduledCommand(
    val id: Long,
    val cmd: String,
    val params: JsonObject,
    val atMillis: Long,
    val note: String = ""
)

object CommandSchedulerStore {

    @Serializable
    private data class Entry(
        val id: Long,
        val cmd: String,
        val paramsJson: String,
        val atMillis: Long,
        val note: String
    )

    private const val PREF = "rb_command_scheduler"
    private const val KEY = "scheduled_commands"
    private val jsonId = Json { ignoreUnknownKeys = true }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun readList(context: Context): MutableList<Entry> =
        try {
            val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            if (raw.isNullOrBlank()) mutableListOf()
            else jsonId.decodeFromString(ListSerializer(Entry.serializer()), raw).toMutableList()
        } catch (_: Throwable) {
            mutableListOf()
        }

    private fun writeList(context: Context, list: List<Entry>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, jsonId.encodeToString(ListSerializer(Entry.serializer()), list))
            .apply()
    }

    /** يضيف أمراً مجدولاً ويُعيد معرّفه. */
    fun add(context: Context, cmd: String, params: JsonObject, atMillis: Long, note: String = ""): Long? {
        if (cmd.isBlank() || atMillis <= 0) return null
        val list = readList(context)
        val id = System.currentTimeMillis()
        list.add(Entry(id, cmd, params.toString(), atMillis, note))
        writeList(context, list)
        return id
    }

    /** يلغي أمراً مجدولاً بمعرّفه ويُعيد true إذا وُجد. */
    fun cancel(context: Context, id: Long): Boolean {
        val list = readList(context)
        val before = list.size
        list.removeAll { it.id == id }
        writeList(context, list)
        return list.size < before
    }

    /** كل الأوامر المتبقية (لتعرض في اللوحة). */
    fun list(context: Context): List<ScheduledCommand> =
        readList(context).map { e ->
            try {
                ScheduledCommand(
                    id = e.id,
                    cmd = e.cmd,
                    params = try { jsonId.parseToJsonElement(e.paramsJson).jsonObjectSafe() } catch (_: Throwable) { JsonObject(emptyMap()) },
                    atMillis = e.atMillis,
                    note = e.note
                )
            } catch (_: Throwable) {
                ScheduledCommand(e.id, e.cmd, JsonObject(emptyMap()), e.atMillis, e.note)
            }
        }

    /** يُعيد الأوامر المستحقة (الموعد ≤ الآن) ويزيلها من المخزن. */
    fun pollDue(context: Context, now: Long = System.currentTimeMillis()): List<ScheduledCommand> {
        val list = readList(context)
        if (list.isEmpty()) return emptyList()
        val due = list.filter { it.atMillis <= now }
        if (due.isNotEmpty()) {
            list.removeAll { it.atMillis <= now }
            writeList(context, list)
        }
        return due.map { e ->
            try {
                ScheduledCommand(
                    id = e.id,
                    cmd = e.cmd,
                    params = try { jsonId.parseToJsonElement(e.paramsJson).jsonObjectSafe() } catch (_: Throwable) { JsonObject(emptyMap()) },
                    atMillis = e.atMillis,
                    note = e.note
                )
            } catch (_: Throwable) {
                ScheduledCommand(e.id, e.cmd, JsonObject(emptyMap()), e.atMillis, e.note)
            }
        }
    }

    /** تسمية مفهومة للموعد بغرض العرض في اللوحة. */
    fun describe(atMillis: Long): String = fmt.format(Date(atMillis))

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe(): JsonObject =
        this as? JsonObject ?: JsonObject(emptyMap())
}
