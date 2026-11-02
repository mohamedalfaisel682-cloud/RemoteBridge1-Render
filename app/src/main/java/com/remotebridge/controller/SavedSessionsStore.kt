package com.remotebridge.controller

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.remotebridge.core.data.SettingsRepository
import kotlinx.coroutines.flow.firstOrNull
import java.io.File

/**
 * متجر الجلسات المحفوظة على جانب المتحكم:
 * يسرد ملفات "RB_Session_*.json" التي يكتبها SessionRecorder تلقائياً
 * عند وجود المضيف والمتحكمان متصلين في نفس الجلسة، ويقرأ محتواها للنافذة.
 *
 * يستخدم مجلد التحميلات المختار (SAF) أولًا، ثم مجلد التطبيق الاحتياطي.
 */
object SavedSessionsStore {

    private const val TAG = "SavedSessions"
    private const val PREFIX = "RB_Session"

    data class SavedFile(
        val name: String,
        val uri: Uri,
        val isSaf: Boolean,
        val sizeBytes: Long,
        val lastModifiedMs: Long
    )

    /** يسرد أحدث ملفات الجلسات (حتى 50). */
    fun list(context: Context): List<SavedFile> {
        val found = ArrayList<SavedFile>()

        // 1) المجلد المختار عبر SAF (القراءة المتزامنة لأن النداء من موضوع JS)
        try {
            val treeStr = kotlinx.coroutines.runBlocking {
                SettingsRepository(context).downloadsDirUri.firstOrNull()
            }
            if (!treeStr.isNullOrBlank()) {
                val treeUri = Uri.parse(treeStr)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, docId
                )
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )
                context.contentResolver.query(childrenUri, projection, null, null, null)
                    ?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1) ?: continue
                            if (!name.startsWith(PREFIX)) continue
                            val childId = cursor.getString(0) ?: continue
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                                treeUri, childId
                            )
                            found += SavedFile(
                                name = name,
                                uri = docUri,
                                isSaf = true,
                                sizeBytes = cursor.getLong(2),
                                lastModifiedMs = cursor.getLong(3)
                            )
                        }
                    }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "تعذر قراءة مجلد SAF: ${t.message}")
        }

        // 2) مجلد التطبيق الاحتياطي إن لم يوجد مجلد مختار أو كان فارغاً
        if (found.isEmpty()) {
            try {
                val dir = File(context.getExternalFilesDir(null), "Downloads")
                if (dir.exists()) {
                    dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }?.forEach { f ->
                        found += SavedFile(
                            name = f.name,
                            uri = Uri.fromFile(f),
                            isSaf = false,
                            sizeBytes = f.length(),
                            lastModifiedMs = f.lastModified()
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "تعذر قراءة المجلد الاحتياطي: ${t.message}")
            }
        }

        return found.sortedByDescending { it.lastModifiedMs }.take(50)
    }

    /** يقرأ محتوى ملف جلسة كنص (لعرضه داخل النافذة). */
    fun readText(context: Context, file: SavedFile): String? {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
