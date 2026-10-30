# تقرير تنفيذ البث الحي المرحلي (MJPEG عبر خادم الإشارات) وإصلاحات WebRTC الداخلية

> **تاريخ التنفيذ:** 2026-10-27 — **الحالة:** التنفيذ مكتمل والحزمة محضَّرة كـ APK واحد شامل على جذر المشروع.
> **المرجع السابق:** `doc\تحليل_البث_الحي_الشامل_واختبار_الخدمة_ودراسة_الوسيلة_البديلة.md` (عيوب A–F + اختبار الخادم 20/20 + دراسة الوسائل)
>
> هذا التقرير يوثّق ما نُفّذ فعلياً استجابةً للإصلاح المعتمد: **المرحلة 1 = بث الترحيل عبر خادم الإشارات القائم (بلا أي تعديل على الخادم)**، و**المرحلة 2 = إصلاحات WebRTC الداخلية**.

---

## 1) الملخص التنفيذي

| العيب | الوصف | الحالة النهائية |
|---|---|---|
| **A** | لا يوجد TURN صالح (`turn.remotebridge.app` بلا DNS — وهمي) | توثيق فقط — لا يتطلب تعديل كود؛ وضع الترحيل يتجاوز الحاجة إليه كلياً |
| **B** | قراءة STUN قبل اكتمال بنائه (read-after-build) في الخدمة | ✅ **مصلَح** — peerManager يُبنى داخل النطاق بعد قراءة STUN + تحقق `ifBlank` وتقسيم قائمة STUN المتعددة بالفاصلة |
| **C** | لا يوجد عارض بث في تطبيق الوراوتر (Controller) | ✅ **مصلَح** — عارض إطارات حي (كاميرا/شاشة) في تطبيق الوراوتر + عارض مرئي في لوحة الويب |
| **D** | مشاركة الشاشة تتطلب نقرة موافقة يدوية لكل جلسة | ⚠️ **منصوص عليه** — سلوك نظام أندرويد (MediaProjection) غير قابل للإلغاء برمجياً |
| **E** | بث الشاشة في WebRTC لا يُرفق المسار عبر Transceiver (إعادة التفاوض) | ✅ **مصلَح** — `startScreenStreaming` يستخدم الـ transceiver الموجود (SEND_ONLY + setTrack) مع إبقاء `addTrack` كاحتياط |
| **F** | التشغيل التلقائي للفيديو في لوحة الويب محظور بسبب سياسات المتصفح | ✅ **مصلَح** — `videoEl.muted = true` قبل `play()` في `ontrack` |

**النتيجة العملية:** البث الحي (كاميرا أمامية/خلفية + شاشة) يعمل الآن عبر قناة الإشارات `wss` نفسها (ترحيل MJPEG عالمي عبر الشبكات دون استثنائات NAT/TURN)، مع بقاء مسار WebRTC المضمن كخطة احتياطية تلقائية عند فشل بدء الترحيل.

---

## 2) البروتوكول الجديد على خادم الإشارات (بلا تعديل خادم)

الرسائل تمر عبر حالة الترحيل العامة `default:` في الخادم — القناة نفسها المُثبتة ميدانياً باختبار 20/20 ثنائي الاتجاه بحجم حتى 150KB عبر 3–9ms:

```
الوراوتر → الخادم → المضيف :  RpcRequest(cmd="start_camera_stream" | "start_screen_stream", params={mode:"relay", ...})
المضيف → الخادم → الوراوتر :  RpcResponse(from=المضيف, to=الوراوتر, ok, data)
المضيف → الخادم → الوراوتر :  LiveFrame(from=المضيف, to=الوراوتر, stream, seq, w, h, ts, b64[JPEG base64])
الوراوتر → الخادم → المضيف :  RpcRequest(cmd="stop_camera_stream" | "switch_camera" ...)
```

- **هدف الترحيل** = `signal.from` الخاص بطلب بدء البث (`liveTargetId`) — الإطارات تتوجه للوراوتر الطالب حصراً.
- `stop_camera_stream` يوقف **كل** المصادر (ترحيل الكاميرا + ترحيل الشاشة + مسارات WebRTC المرتبطة) — أمر إيقاف واحد يكفي لكل شيء.
- الإطار: JPEG عبر `b64` — الكاميرا 1280×720 بمعدل إطار كل 300ms، الشاشة 640 عرضاً بجودة 50 بمعدل كل 320ms.
- **إيقاف ذاتي أمان:** 5 فشل إرسال متتالية → إيقاف الترحيل ذاتياً (انعكاسه في `lastError`).

---

## 3) التنفيذ مرحلةً مرحلة

### المرحلة 1 — الترحيل (MJPEG عبر الإشارات)

| الملف | ما تم |
|---|---|
| `core\signaling\Signal.kt` | أُضيف النوع `@SerialName("live_frame") data class LiveFrame(from,to,stream,seq,w,h,ts,b64)` |
| `host\capture\LiveRelayServer.kt` | **جديد** — وحدة نظيفة كاملة: `startCamera()` (Camera2 + ImageReader بصيغة JPEG بفاصل 300ms) / `startScreen()` (`getMediaProjection(RESULT_OK, intent)`؛ تسجيل Callback قبل `createVirtualDisplay` إلزامياً على Android 14+) / `stopCamera, stopScreen, stopAll, activeType(), isCameraFront(), lastError` — للشاشة: RGBA→Bitmap→JPEG مع مراعاة `rowStride` عند قراءة الصفوف، والأبعاد عبر نمط المشروع الموحد (`DisplayMetrics` عبر `resources`) |
| `host\service\HostForegroundService.kt` | الربط الكامل: حقول `liveRelay / liveTargetId / liveFrameSeq / liveSendFailures` + `pushLiveFrame()`؛ `RpcRequest` يحدّد `liveTargetId`؛ إعادة كتابة `START_CAMERA_STREAM / STOP_CAMERA_STREAM / SWITCH_CAMERA / START_SCREEN_STREAM` — الافتراضي `mode:"relay"` مع الاحتياط التلقائي إلى WebRTC عند فشل بدء الترحيل (وإيقاف كاميرا WebRTC أولاً منعاً للفتح المزدوج عبر Camera2)؛ شاشة بلا نية إذن مخزّنة → `pendingPermission` + إطلاق `ScreenCapturePermissionActivity`؛ `stopHost` و`onDestroy` يستدعيان `liveRelay.stopAll()` |
| `controller\ControllerManager.kt` | `handleSignal` يوجّه `RpcResponse` للطلبات المعلقة (`pendingResponses / downloadFlows`) + فك رسائل `LiveFrame` (فك Base64 خارج الخيط الرئيسي)؛ `sendRequestRelay(hostId,cmd,params)` عبر الإشارات؛ `clearLiveFrame()` وإزالة حالة اللايف عند `disconnect()` |
| `ui\viewmodel\ControllerViewModel.kt` | كشف `liveFrame / liveStreamInfo` + إجراءات `startLiveCamera / startLiveScreen / stopLive / switchLiveLens / clearLiveView` (كلها `mode:'relay'`) |
| `ui\screens\controller\ControllerScreen.kt` | بطاقة **LiveViewerCard** بعد بطاقة حالة الاتصال: صندوق عرض 220dp + شرائح المضيف المتصل + أزرار (كاميرا أمامية/خلفية، شاشة، تبديل العدسة، إيقاف) مع ملاحظة استخدام |

### المرحلة 2 — إصلاحات WebRTC الداخلية

| الملف | ما تم |
|---|---|
| `core\webrtc\PeerConnectionManager.kt` | إصلاح العيب E: `startScreenStreaming` يرفق مسار الفيديو عبر الـ Transceiver الحالي (`setDirection(SEND_ONLY)` + `sender.setTrack(videoTrack)`) — ويبقى `addTrack` احتياطاً |
| `web-dashboard\index.html` + `app\src\main\assets\dashboard\index.html` | إصلاح العيب F + عارض الترحيل الكامل على الويب (تفاصيل أدناه) |

### تغييرات لوحة الويب (النسختان التوأمتان متطابقتان SHA256)

1. **موزّع الرسائل:** `case "live_frame": handleLiveFrame(msg)` قبل `settings_sync`.
2. **حالة جديدة:** `relayStreamActive / relayLastDataURL / relayFrameCount / webrtcVideoActive` — فيديو WebRTC النشط **له الأولوية** على صور الترحيل.
3. **`handleLiveFrame`:** يُخفي عنصر الفيديو **فقط إذا** لم يكن له `srcObject` نشط؛ لا يغير عرض المستخدم الحالي (يعيد رسم البطاقة فقط إذا كانت لوحة البث هي المعروضة)؛ يحدّث `img` مباشرة للإطارات اللاحقة؛ يخزّن آخر إطار لاستعادته بعد إعادة رسم اللوحة (العنصر يُعاد إنشاؤه في كل مرة).
4. **شرائح الحالة:** شارة المصدر تُظهر `RELAY` ونص «بث الترحيل نشط» عند وصول الإطارات، بشكل مستقل عن الحالة القديمة `streamState`.
5. **الأزرار:** `start_screen_stream` يرسل `{mode:'relay'}` و`start_camera_stream` أضاف `mode:'relay'`؛ قبل الإرسال يُصفّى فيديو WebRTC القديم المنتهي (`clearWebRtcVideoForRelayStart`)؛ `stopLiveStream` ينظّف حالة الترحيل (`clearRelayViewer`).
6. **`ontrack`:** `videoEl.muted = true` قبل `play()` (إصلاح العيب F) + `webrtcVideoActive = true` + إخفاء صورة الترحيل عند ظهور الفيديو الحقيقي.

---

## 4) دليل الاستخدام العملي

### من تطبيق الوراوتر (الأندرويد)
1. اربط بالمضيف كالمعتاد → ستظهر بطاقة **البث الحي** تحت بطاقة حالة الاتصال.
2. زر **الكاميرا** يطلق بث العدسة الحالية (أمامية افتراضياً؛ مع طلب إذن الكاميرا عند أول استخدام)؛ زر **الشاشة** يطلب موافقة النظام عند القيود (انظر القسم 5).
3. زر **تبديل العدسة** يبدّل الكاميرا حياً دون إعادة توصيل؛ زر **الإيقاف** يوقف كل المصادر.

### من لوحة الويب
1. افتح لوحة الويب → عرض جهاز المضيف → لوحة **البث الحي المباشر**.
2. زر **بث الشاشة** أو **بث الكاميرا** → يعملان في وضع الترحيل افتراضياً؛ تظهر صورة الإطارات الحية مع شارة `RELAY`.
3. عبر مسار WebRTC (عند الاحتياط) يظهر الفيديو/الصوت الحقيقيان **بحد أولوية** فوق صور الترحيل.

### سلوك الأمان في العرض
- إذا كان WebRTC يعرض فيديو حياً فعلاً، لن تستبدله صور الترحيل تطفّياً حتى يوقف المستخدم المجرى يدوياً (الإيقاف الموحد يوقف الكل).
- عند فشل بدء الترحيل على المضيف، يتحول التنفيذ تلقائياً إلى بث WebRTC القديم دون تدخل المستخدم.

---

## 5) القيود المعروفة

| القيد | السبب | المعالجة التطبيقية |
|---|---|---|
| **نقرة يدوية واحدة لكل جلسة مشاركة شاشة** | التزام حماية `MediaProjection` في أندرويد (منع التجسس الخفي) | المستخدم يوافق عند الطلب؛ بعد الموافقة تستمر الجلسة حتى إيقافها أو إعادة تشغيل الخدمة |
| **إذن الكاميرا عند أول استخدام** | نموذج صلاحيات أندرويد الكلاسيكي | أول ضغطة على زر الكاميرا تطلق طلب الإذن تلقائياً |
| **اتجاه الإطار قد يبدو مدوَّراً على بعض الأجهزة** | الكاميرا ترسل JPEG باتجاه المستشعر كما هو (بلا دوران) في v1 | موثّق؛ المعالجة مستقبلية في إصدار لاحق (v2) |
| **الإيقاف الذاتي بعد 5 فشل إرسال متتالية** | حماية من انسداد الشبكة أو ازدحامها | الحالة تنعكس في `lastError` للاستعلام |
| **TURN وهمي** | `turn.remotebridge.app` بلا DNS | وضع الترحيل يتجاوز الحاجة إليه ذاتياً؛ WebRTC يبقى بـ STUN كما هو |

---

## 6) قائمة الملفات المعدَّلة كاملة

1. `app\src\main\java\com\remotebridge\core\signaling\Signal.kt`
2. `app\src\main\java\com\remotebridge\host\capture\LiveRelayServer.kt` **(جديد)**
3. `app\src\main\java\com\remotebridge\host\service\HostForegroundService.kt`
4. `app\src\main\java\com\remotebridge\controller\ControllerManager.kt`
5. `app\src\main\java\com\remotebridge\core\webrtc\PeerConnectionManager.kt`
6. `app\src\main\java\com\remotebridge\ui\viewmodel\ControllerViewModel.kt`
7. `app\src\main\java\com\remotebridge\ui\screens\controller\ControllerScreen.kt`
8. `web-dashboard\index.html` ⇔ `app\src\main\assets\dashboard\index.html` (التوأمان متطابقان)

**بلا تعديل:** `main.go` و`remotebridge-server.exe` (براءة الخادم مُثبتة 20/20) و`app\build.gradle.kts`.

---

## 7) الحزمة الناتجة والتحقق

| البند | القيمة |
|---|---|
| ملفات APK في الشجرة كلها | **1 فقط**: `RBstudioPRO.apk` على الجذر |
| الحجم | 62,922,929 بايت (~62.9 MB) — universal (كل المعماريات، بلا تقسيم ABI) |
| `versionCode / versionName` | `1 / 1.0.0` (كالتكوين الثابت — بلا تعديل) |
| مجلدات `build` | **0** بعد البناء والحذف الكامل |
| التوأمان | متطابقان SHA256: `8B1D14FD50EBFA996A5ACFA8AFD294E38E53D633E460888063CF443FC279C3E8` |
| التوقيع | كالتكوين المطلوب (`app\build.gradle.kts` لم يُمَسّ) |

---

## 8) الثوابت المرجعية الموثّقة من الحلقة السابقة (بدون تغيير)

- الخادم `wss://remotebridge1.onrender.com/ws` — STUN: `stun{,1-4}.l.google.com:19302` — DataChannel id `42` — قناة المضيف `bridge_host_channel_v2` / 1001 — سعة الإطارات `MAX_PAYLOAD = 16*1024`.
- سر الهاتف: `*#*#82473#*#*` أو `*82473#` — `applicationId: com.rbstudio`، minSdk 26، compile/target 36.
- درس من اختبار الخادم: لا تُستخدم `SetReadDeadline` مع gorilla/websocket في عملاء الاختبار.
