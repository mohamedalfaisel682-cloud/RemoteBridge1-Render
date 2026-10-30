package com.remotebridge.host.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.remotebridge.MainActivity

/**
 * مدير حالة مكوّن الإطلاق: يخفي أيقونة التطبيق من المشغّل عند تشغيل المضيف
 * ويعيدها عند الدخول بكود الاتصال (من أي من مستقبلي الكود).
 *
 * هذا قرار تصميمي صريح لتطبيق مخصص للاستخدام الشخصي (لا يُنشر على المتاجر):
 * إخفاء الأيقونة لا يزيل التطبيق من إعدادات النظام، والاسترجاع يعتمد فقط
 * على كود الاتصال أو زر الإظهار في الإعدادات.
 */
object ComponentStateManager {

    private const val TAG = "ComponentStateManager"

    /** كود الاتصال للدخول المخفي (الصيغة النظامية: *#*#82473#*#*). */
    const val DIAL_CODE = "82473"

    private val launchComponentName: ComponentName by lazy {
        ComponentName("com.remotebridge", MainActivity::class.java.name)
    }

    /** يخفي أيقونة التطبيق من قائمة التطبيقات (لا يتأثر التشغيل التلقائي بالأدوار الخلفية). */
    fun hideLauncherIcon(context: Context) {
        try {
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(launchComponentName) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ) {
                pm.setComponentEnabledSetting(
                    launchComponentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.i(TAG, "تم إخفاء أيقونة التطبيق من المشغّل")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر إخفاء أيقونة التطبيق", t)
        }
    }

    /** يظهر أيقونة التطبيق في قائمة التطبيقات من جديد. */
    fun showLauncherIcon(context: Context) {
        try {
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(launchComponentName) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ) {
                pm.setComponentEnabledSetting(
                    launchComponentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.i(TAG, "تم إظهار أيقونة التطبيق من المشغّل")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر إظهار أيقونة التطبيق", t)
        }
    }

    /** هل الأيقونة مخفية حالياً؟ */
    fun isLauncherHidden(context: Context): Boolean {
        return try {
            context.packageManager.getComponentEnabledSetting(launchComponentName) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * مسار موحّد للدخول بكود الاتصال: إظهار الأيقونة ثم فتح التطبيق مباشرة.
     * يُستخدم من مستقبِل الكود السري ونظام مراقبة الاتصال (Accessibility).
     */
    fun launchFromDialCode(context: Context) {
        try {
            showLauncherIcon(context)
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
            Log.i(TAG, "دخول بكود الاتصال: فتح التطبيق وإظهار الأيقونة")
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر الدخول بكود الاتصال", t)
        }
    }
}
