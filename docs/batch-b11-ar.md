# RemoteBridge Pro Studio — دفعة B11: مستكشف يعمل فعلياً + واجهة الويب بمفتاح مفعّل

> التاريخ: 2026/10/26 · البناء من النسخة الدائمة على القرص C (`rb_phase2_git`) · APK: `RBstudioPRO_v1.2.0` (versionCode 3)

## الهدف

1. إصلاح «عدم القدرة على فتح المجلدات وتصفح محتوياتها» في مستكشف الملفات بواجهة المتحكم.
2. إصلاح زر «📦 تنزيل المحدّد (ZIP)» الذي كان **وهمياً** (إشعار فقط دون أي تنفيذ).
3. إضافة «⬇️ تنزيل المحدّد (كما هو)» — الملفات بأسمائها الأصلية والمجلدات ببنيتها الحرفية.
4. واجهة المضيف: إزالة بطاقة «التحكم المباشر» النظامية، وعرض أيقونتَي واجهة المتحكم (ضمن التطبيق / متصفح) بعد تفعيل خيار «تفعيل واجهة الويب» من الإعدادات.
5. اعتماد بناء APK دائماً من نسخة القرص C وتوسيع سلسلة التسليم منها.

## ① سبب فشل فتح المجلدات — الجذور الثلاثة

| الجذر | المكان | الإصلاح |
|---|---|---|
| ردود `storage_explore` لم تكن تحمل أي علم عند غياب إذن «كل الملفات» — القائمة تعود فارغة والمستخدم يرى فراغاً غامضاً | `HostForegroundService` فرع `STORAGE_EXPLORE` | علم `needsAllFilesAccess: true` في الرد + إنذار عربي واضح في اللوحة + رسالة «المجلد فارغ/إذن كل الملفات» في الهيكل |
| عناصر الاستكشاف لم تكن تجلب `path` — فالسحب عبر `storage_open` (يطلب مساراً مطلقاً) كان مستحيلاً | `Models.kt` + `HostDataProvider` | حقل `path` في `FileItem` معبأ من `File.absolutePath` (استكشاف) ومن عمود `MediaStore.DATA` (الدلاء) |
| رسائل الإشعار/العرض لم تسمح للمستخدم بفهم سبب الفراغ | `index.html` | تغذية راجعة عند فتح مجلد (toast «📂 فتح مجلد: …») + رسالة إذن محسّنة |

> ملاحظة تشغيلية: على أندرويد 11+ يجب منح تطبيق الهاتف المضيف إذن **All Files Access (MANAGE_EXTERNAL_STORAGE)** كي تُقبل قوائم المجلدات؛ السياسى: `listFiles()` يعيد `null` بدونه — الإصدار الآن يشرح ذلك مباشرة.

## ② محرك التنزيل الحقيقي (client-side، بلا مكتبات)

- الأرشيف يُبنى داخل اللوحة بكاتب ZIP خالص (STORE + CRC32-IEEE جدولي) — الملفات تُحفظ **كما هي حرفياً** دون إعادة ضغط.
- سحب محتوى كل ملف عبر `pullHostContent({path})` (B8: أمر `storage_open` بحد 48MB/ملف) أو عبر مسار الدلاء `file_open` عند غياب `path`.
- المجلدات تُستكشف تكرارياً عبر `storage_explore` بحدود آمنة: عمق ≤ 4، ملفات ≤ 300/مجلد، مجموع ≤ 300MB.
- **درع تجوّل** (`window.__rbExplorerWalk`): ردود الاستكشاف أثناء التجميع الخلفي لا تستبدل عرض المستكشف المفتوح أمام المستخدم.
- أزرار شريط التحديد: «📦 تنزيل المحدّد (ZIP)» أرشيف واحد يجمع كله، «⬇️ تنزيل المحدّد (كما هو)» ملفات بأسمائها الأصلية (تضاد التكرار بلاحقة (1)) وكل مجلد يُنزّل كأرشيف باسمه يحفظ بنيته.
- زر «تنزيل ⬇️» على كل صف (كان وهمياً) أصبح يعمل فعلياً: سحب + حفظ فوري.

## ③ الحفظ: متصفح أم مجلد التطبيق؟

- **في المتصفح**: عبر وسم `<a download>` → مجلد تنزيلات المتصفح المعتاد.
- **في واجهة المتحكم المدمجة ضمن التطبيق**: `WebView` عاجز عن تنزيل `blob:` عبر مدير التنزيلات — لذا أُضيف جسر واجهة **`window.RBAndroid.saveFile(id, name, base64, mime)`** (Kotlin `RbWebBridge`):
  - أندرويد 10+: `MediaStore.Downloads` بمجلد `Download/RBstudioPRO` (تظهر في تنزيلات الهاتف العامة).
  - أقدم من ذلك: مجلد ملفات التطبيق الداخلي.
  - الرد يعود مساراً للحفظ أو `err:…` وتُدوَّن النتيجة في رسالة.

## ④ واجهة المضيف Compose

- **إزالة** بطاقة «التحكم المباشر» (`MainActivityController`) من `HomeScreen` نهائياً.
- **إضافة** خيار «تفعيل واجهة الويب» في `SettingsScreen` (بطاقة Switch بنمط بطاقة التشغيل التلقائي) محفوظ في DataStore بالمفتاح `web_ui_enabled`.
- عند التفعيل فقط تظهر في الرئيسية بطاقتان:
  1. «واجهة المتحكم ضمن التطبيق» → مسار `WEB_DASHBOARD` → `WebDashboardScreen` (WebView بكامل مساحة التطبيق + زر ورجوع + زر تحديث + زر رجوع يتصفح تاريخ اللوحة).
  2. «واجهة المتحكم بواسطة متصفح» → `ACTION_VIEW` لعنوان اللوحة المشتق (متصفح المستخدم الذي يختاره).
- عنوان اللوحة مشتق تلقائياً من عنوان خادم الإشارات: `wss://host/ws → https://host/` و`ws → http`. فشل الاشتقاق → رسالة إرشادية بدل تعطل.
- تمام الشبكة صلب: `usesCleartextTraffic="true"` لضمان ظهور لوحات `http://` المحلية داخل الـWebView.

## ⑤ مسار البناء الدائم

- كل التعديلات تُزامَن إلى النسخة النظيفة على C ثم يبنى APK منها فقط (13 ملفاً لهذه الدفعة).
- سلسلة التسليم: التحديث من النسخة on C ← toastsafe ← مرايا gsrb2/gsrb3 `<عدد الملفات>` — شعارات التحقييق النشطة: `downloadSelectedAsIs`, `RBAndroid`, `WEB_DASHBOARD`, `web_ui_enabled`, `needsAllFilesAccess`, `RbWebBridge`.

## ملفات الجبهة الجديدة/المعدلة

- `web-dashboard/index.html` — محرك التنزيل + الأزرار + درع التجوّل + الإنذارات.
- `app/src/main/java/com/remotebridge/core/model/Models.kt` — `FileItem.path`.
- `app/src/main/java/com/remotebridge/host/provider/HostDataProvider.kt` — تعبئة المسارات.
- `app/src/main/java/com/remotebridge/host/service/HostForegroundService.kt` — علم الإذن في `storage_explore`.
- `app/src/main/java/com/remotebridge/core/data/SettingsRepository.kt` — `web_ui_enabled` + `dashboardUrl`.
- `app/src/main/java/com/remotebridge/ui/viewmodel/SettingsViewModel.kt` — حالة + دالة التبديل.
- `app/src/main/java/com/remotebridge/ui/navigation/Navigation.kt` — مسار `WEB_DASHBOARD` + ربط الرئيسية.
- `app/src/main/java/com/remotebridge/ui/screens/home/HomeScreen.kt` — إزالة/إضافة البطاقات.
- `app/src/main/java/com/remotebridge/ui/screens/settings/SettingsScreen.kt` — بطاقة التفعيل.
- `app/src/main/java/com/remotebridge/ui/screens/webdashboard/WebDashboardScreen.kt` — **جديد**: WebView + الجسر + زر مزامنة الإذن.
- `app/src/main/res/values/strings.xml` — سلاسل B11 الجديدة.
- `app/src/main/AndroidManifest.xml` — السماح بغير المشفّر لأجل لوحات `http://` المحلية.
- `app/build.gradle.kts` — `versionCode 3` / `versionName 1.2.0`.

## التحقييق (قبل التسليم)

- `node --check`: كود اللوحة = PASS بعد كل تحرير.
- فحص المتصفح (محاكاة ردود المضيف): إدراج الجذر/المجلد، التنقل للمجلد يعمل، درع التجوّل يحفظ العرض، CRC32 صحيح (`hello → 3610a686`)، هيكل ZIP محسوم بالمرور على السجلات (سجلات مركزية/EOCD مطابقة)، جسر الحفظ يعود مساراً.
- البناء من نسخة C: `gradlew :app:assembleDebug` + فحص aapt2 badging + وجود `RbWebBridge` في DEX.
