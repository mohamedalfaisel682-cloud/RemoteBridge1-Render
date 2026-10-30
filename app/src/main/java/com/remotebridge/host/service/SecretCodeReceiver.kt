package com.remotebridge.host.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * مستقبِل الأكواد السرّية في لوحة مفاتيح الاتصال.
 *
 * الصيغة النظامية المدعومة أصلاً في تطبيق الهاتف:
 *     *#*#82473#*#*
 * يقوم نظام الاتصال ببث
 *     android.provider.Telephony.SECRET_CODE
 * عنوانه android_secret_code://82473 — فنستقبلها ونوجه فتح التطبيق مع
 * إظهار أيقونته من جديد.
 *
 * ملاحظة تقنية: الكود الحرفي *82473# (دون صف #*#* المحيط) يمر كطلب USSD
 * إلى الشبكة ولا يصل إلى أي تطبيق عادي عبر البثّ — لذلك تُغطي صيغته
 * الحرفية خدمة [DialCodeAccessibilityService] المنفصلة عند تفعيلها.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.data ?: return
        // قد يأتي الكود في host أو schemeSpecificPart حسب إصدار النظام
        val code = data.host ?: data.schemeSpecificPart ?: ""
        if (code != ComponentStateManager.DIAL_CODE) return
        Log.i("SecretCodeReceiver", "تلقينا الكود السري: $code — فتح التطبيق")
        ComponentStateManager.launchFromDialCode(context)
    }
}
