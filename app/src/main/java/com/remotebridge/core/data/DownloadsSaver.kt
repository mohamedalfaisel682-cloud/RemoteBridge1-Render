package com.remotebridge.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.content.ContentResolver
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * أداة حفظ الملفات المنزّلة محلياً على الجهاز المضيف:
 *
 * 1) إن اختار المستخدم مجلداً للتحميلات (إعدادات التطبيق → "حفظ البيانات")
 *    تُكتب الملفات فيه عبر نظام SAF الرسمي (بدون أذونات تخزين http).
 * 2) الاحتياط: مجلد خاص داخل التطبيق (app-specific)، وهو مخفي عن التطبيقات
 *    الأخرى تلقائياً ولا يحتاج أذونات (وفق مستوى الحماية على أندرويد).
 *
 * يُستخدم من نوافذ الجهاز (شاشة الملفات، لوحة الويب المدمجة عبر جسر
 * JavascriptInterface) ولتسجيلات البث.
 */
object DownloadsSaver {

    /** أسماء المجلدات الاحتياطية داخل مسار التطبيق الخاص. */
    private const val FALLBACK_DOWNLOADS = "Downloads"
    private const val FALLBACK_RECORDINGS = "Recordings"

    /**
     * يفتح ملفاً جديداً للكتابة في مجلد المستخدم (التحميلات أو التسجيلات).
     * يعيد ستريم الإخراج ووصفاً بسيطاً للملف الناتج واسم المجلد المستخدم.
     */
    data class OpenedOutput(
        val stream: OutputStream,
        /** مسار فعلي للملف عند الاحتياط (ملفات التطبيق الخاصة). */
        val file: File?,
        /** اسم المجلد الفعلي المستخدم (لعرضها للمستخدم). */
        val folderLabel: String
    )

    /** افتح مجلداً خاصاً ضمن مسار التطبيق (احتياط). */
    suspend fun openOutput(
        context: Context,
        fileName: String,
        useRecordingFolder: Boolean = false
    ): OpenedOutput {
        val repo = SettingsRepository(context)
        val dirUriStr =
            (if (useRecordingFolder) repo.recordingsDirUri.first() else repo.downloadsDirUri.first())
                .trim()

        val safeName = fileName.substringAfterLast('/')
            .ifBlank { "rb-${UUID.randomUUID().toString().take(8)}.bin" }

        if (dirUriStr.isNotBlank()) {
            try {
                val resolver = context.contentResolver
                val treeUri = Uri.parse(dirUriStr)
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
                val baseName = safeName.substringBeforeLast('.')
                    .ifBlank { "rb-file" }
                val ext = safeName.substringAfterLast('.', missingDelimiterValue = "")
                val mimeType = if (ext.isNotBlank()) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
                        ?: "application/octet-stream"
                } else {
                    "application/octet-stream"
                }
                val created = DocumentsContract.createDocument(
                    resolver,
                    rootDoc,
                    mimeType,
                    sanitizeName(baseName) + if (ext.isNotBlank()) ".${ext.lowercase()}" else ""
                )
                if (created != null) {
                    val stream = resolver.openOutputStream(created, "w")
                    if (stream != null) {
                        return OpenedOutput(
                            stream = stream,
                            file = null,
                            folderLabel = labelForUri(context, treeUri) ?: "المجلد المحدّد"
                        )
                    }
                }
            } catch (_: Throwable) {
                // نوقع على الاحتياط أدناه في حالة فشل SAF
            }
        }

        val fallbackDir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            if (useRecordingFolder) FALLBACK_RECORDINGS else FALLBACK_DOWNLOADS
        ).apply { mkdirs() }
        val unique = uniquify(fallbackDir, safeName)
        return OpenedOutput(
            stream = FileOutputStream(unique),
            file = unique,
            folderLabel = "ملفات التطبيق الخاصة (${unique.parentFile?.name ?: FALLBACK_DOWNLOADS})"
        )
    }

    /** مساراً وصفاً جميلاً بشرياً للمجلد الذي ستُكتب البيانات فيه. */
    suspend fun describe(context: Context, useRecordingFolder: Boolean = false): String {
        val repo = SettingsRepository(context)
        val uriStr = (if (useRecordingFolder) repo.recordingsDirUri.first() else repo.downloadsDirUri.first())
            .trim()
        if (uriStr.isNotBlank()) {
            val label = labelForUri(context, Uri.parse(uriStr))
            if (label != null) return label
        }
        val dirName = if (useRecordingFolder) FALLBACK_RECORDINGS else FALLBACK_DOWNLOADS
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return "مجلد خاص داخل التطبيق: Android/data/${context.packageName}/files/$dirName"
    }

    /** الاسم الجميل البشري لمجلد SAF المختار. */
    fun labelForUri(context: Context, treeUri: Uri): String? {
        return try {
            val resolver: ContentResolver = context.contentResolver
            resolver.query(treeUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: treeUri.lastPathSegment
        } catch (_: Throwable) {
            null
        }
    }

    /** يزيل الأحرف الضارة من الاسم قبل الكتابة. */
    fun sanitizeName(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return clean.ifBlank { "rb-file" }
    }

    /** يضيف لاحقة رقمية في حال وجود ملف بنفس الاسم داخل مسار يمثّله File. */
    private fun uniquify(dir: File, fileName: String): File {
        var candidate = File(dir, sanitizeName(fileName))
        if (!candidate.exists()) return candidate
        val base = candidate.nameWithoutExtension
        val ext = candidate.extension
        var index = 1
        while (candidate.exists()) {
            val name = if (ext.isBlank()) "$base($index)" else "$base($index).$ext"
            candidate = File(dir, name)
            index++
        }
        return candidate
    }

    /** يكتب بايتات كاملة في مجلد الحفظ المحدد (دالة مريحة تحفت مسار جاهز). */
    suspend fun saveAll(
        context: Context,
        fileName: String,
        data: ByteArray,
        useRecordingFolder: Boolean = false
    ): OpenedOutput {
        val opened = openOutput(context, fileName, useRecordingFolder)
        opened.stream.use { it.write(data) }
        return opened
    }
}
