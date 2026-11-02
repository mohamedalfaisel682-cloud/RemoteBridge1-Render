# B26 — تقرير الدمج الشامل: ApkFull v1.5.0 (versionCode 10)

> التاريخ: 2026-11-03 · الأساس: المشروع الحالي (مخدوم B1–B25) · المُعطي: المشروع المتقدم (`opencodeGLM5.3FLASHAAdvanced`)

## 1) قرار الدمج
اعُتمد المشروع الحالي **أساساً** (خدمةه أعمق: 1851 سطراً مع بريغات B1–B25، وبيانه أقوى)،
ونُقلت من المشروع المتقدم **الوحدات الفريدة حصراً** (-13 وحدة + مورد XML + خُزنة توقيع):

| الوحدة المنقولة | الموضع في الشجرة | الدور |
|---|---|---|
| `RemoteCaptureModule.kt` | `host/capture/` | لقطات متعددة/فيديو/صوت في `MediaStore` |
| `RemoteCommandsModule.kt` | `host/capture/` | رسائل/مكالمات/عرض عائم/أسماء/وسائط/إطفاء |
| `CommandSchedulerStore.kt` | `host/capture/` | أوامر مجدولة صامدة عبر إعادة التشغيل (تنفيذ كل 10 ثوان) |
| `LiveRelayServer.kt` | `host/capture/` | بث كاميرا/شاشة MJPEG عبر قناة الإشارات بلا CAR/TURN |
| `HostRestartScheduler.kt` | `host/service/` | استمرارية `WorkManager` (مراقبة دورية 15 دقيقة + إعادة سريعة) |
| `ComponentStateManager.kt` | `host/service/` | إخفاء/إظهار أيقونة القاعدة + فتح من كود الاتصال |
| `DialCodeAccessibilityService.kt` | `host/service/` | رصد `*82473#` في لوحة الاتصال |
| `SecretCodeReceiver.kt` | `host/service/` | كود النظام السري `*#*#82473#*#*` |
| `SettingsSyncManager.kt` | `core/data/` | مزامنة إعدادات المضيف/المتحكم (الأحدث يفوز) |
| `DownloadsSaver.kt` | `core/data/` | حفظ SAF في مجلد المختارات أو احتياط التطبيق |
| `NativeFilesSaver.kt` | `ui/screens/web/` | جسر JS لحفظ ملفات لوحة الويب |
| `SessionRecorder.kt` | `controller/` | تسجيل جلسات المتحكم |
| `SavedSessionsStore.kt` | `controller/` | محفظة الجلسات (SAF + احتياط) |
| `dialcode_accessibility_config.xml` | `res/xml/` | تهيئة خدمة كود الاتصال |
| `my-upload-key.jks` | جذر المشروع | خُزنة توقيع الإصدار (كلمة السر من `build-small-apk.ps1`: قيمة افتراضية موثقة في التقرير الداخلي) |

## 2) أنواع الإشارات الجديدة (`Signal.kt`) — قناة الخادم يُعيد توجيهها فعلاً (فرع `default` في `main.go`)
- `settings_sync` — { from,to=null,ts,settings } — `ts=0` سحب، و`ts>0` دفعِ (الأحدث يفوز)، وعند تغيّر الخادم → `HostRestartScheduler.scheduleQuickRestart(delaySeconds=2)`.
- `live_frame` — إطار JPEG/base64 { from,to,stream,seq,w,h,ts,b64 } — توقف تلقائي بعد 5 فشل إرسال متتالٍ.

## 3) أوامر جديدة (15) أُضيفت إلى `Commands` ونُفِّذت في `processRequest`
`sms_send`, `call_make`, `screen_show`, `contact_add`, `media_add`, `capture_photo`, `capture_video`, `record_audio`, `screen_shot`, `screen_record`, `device_shutdown`, `device_reset`, `command_schedule`, `schedule_list`, `schedule_cancel`.
- الالتقاط عبر الشاشة يستخدم **إذن `MediaProjection` المخزّن مرة واحدة** (نشاط `ScreenCapturePermissionActivity` الموجود) مع `pendingScreenAction` يُنفَّذ فور المنح ويرد إجابته على الطالب الأصلي بمعرّفه.
- بث الكاميرا: `mode=="relay"` قسرَ عبر الترحيل؛ وإلا `webrtc` أولاً مع **سقوط بديل** إلى الترحيل عند فشل `ICE\TURN`؛ `switch_camera` يدعم وضع الترحيل بعكس العدسة.
- بث الشاشة: `mode=="relay"` قسرَ؛ وإلا `webrtc` مع سقوط بديل إلى الترحيل.

## 4) إصلاحات الصلاحيات (أندرويد 8+)
| النقطة | قبل | بعد |
|---|---|---|
| `meta-data` مدير الجهاز | `android.app.admin.DEVICE_ADMIN` (خطأ — لا يعمل مطلقاً) | **`android.app.device_admin`** الصحيح النظامي + `device_admin_desc` |
| بطاقة الرسائل | طلب `SEND_SMS` فقط | طلب `SEND_SMS + READ_SMS + RECEIVE_SMS` معاً |
| بطاقة المكالمات | طلب `CALL_PHONE (+ANSWER)` | أضيف `READ_PHONE_STATE` + `READ_PHONE_NUMBERS` (API 29+) |
| الاستيقاظ | `BOOT_COMPLETED` فقط | أضيف `QUICKBOOT_POWERON` + `MY_PACKAGE_REPLACED` |
| كود الدخول | غير موجود | `SECRET_CODE *#*#82473#*#*` + خدمة إمكانية وصول اختيارية |
| إذن خلفية إشعارات | موجود | بقي (تصاعداً على 33+) |

ملاحظة سلوكية: `RemoteCommandsModule` يلجأ إلى `DeviceAdminReceiver.lockNow()` كبديل بلا جذر لأمر الإطفاء،
ولذا دُمِج `HostDeviceAdminReceiver` بنسخة موحدة (UX الحالي + `isActive/lockScreenIfPossible` من الدونور).

## 5) البنية والاستمرارية
- `androidx.work` (2.10.0) أُضيف إلى `gradle/libs.versions.toml` + `app/build.gradle.kts` لخدمة `HostRestartScheduler`.
- حقول `SettingsRepository` الجديدة: `settingsSyncTimestamp`, `restartOnKill`, `restartDelayMinutes`, `downloadsDirUri`, `recordingsDirUri` (+مضبوطاتِها).
- تنظيف كامل: `stopHost` و`onDestroy` يبطلان `schedulerJob/liveRelay/liveTargetId` ويستدعيان `stopAll()`.

## 6) الناتج النهائي
- `applicationId = com.rbstudio` (يستبدل الفعلي المثبت) · `versionCode 10` · `versionName 1.5.0`.
- مخرج البناء: **`ApkFull-release.apk` → منسوخ إلى الجذر باسم `ApkFull.apk` (56.51 MB)** — موقَّع ومُتحقق منه (`CN=RemoteBridge`, SHA-256: `00cd05c8…`).
- نسخة تطوير ثانوية: `ApkFull-debug.apk` في الجذر لانتقال سلس فوق التثبيت القديم الموقَّع بتصحيحه.
