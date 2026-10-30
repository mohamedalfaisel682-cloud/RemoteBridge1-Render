# الدليل المرجعي والهندسي الشامل لنظام "جسر عن بعد" (RemoteBridge Pro Master Guide)

---

## 1. نظرة عامة ورسالة النظام (System Overview & Architecture)

تطبيق **RemoteBridge Pro (جسر عن بعد)** هو نظام أندرويد أصلاني (Native Android App) متكامل ومتقدم مخصص لربط الأجهزة الذكية واستكشاف بياناتها والتحكم بها عبر الإنترنت أو شبكات Wi-Fi المحلية بنمط الاتصال المباشر (Peer-to-Peer) المعتمد على بروتوكولات **WebRTC** وخادم إشارات خفيف الوزن بلغة **Go**.

تم تصميم النظام ليعمل بمرونة فائقة وثبات كامل على جميع هواتف الأندرويد الحديثة (**Android 11+ / API 30+**) بأسلوب أصلاني متوافق 100% مع بيئة **Android Studio** و **Gradle**.

---

## 2. النمط التشغيلي الثلاثي للتطبيق (Triple-Mode System Architecture)

يتيح التطبيق البدء بثلاثة أنماط تشغيلية احترافية:

1. **جهاز مُضيف (Host Mode - الهاتف البعيد):**
   - يعمل كخادم خلفي متصل بالشبكة عبر خدمة أمامية عالية الثبات ومحصنة ضد القتل التلقائي (`HostForegroundService` بأنواع `dataSync|camera|microphone`).
   - يقوم بقراءة واستكشاف كافة بيانات الهاتف (نظام الملفات المباشر، MediaStore، الرسائل SMS، جهات الاتصال، سجل المكالمات، الإشعارات الحية، البطارية، ومساحة الذاكرة).
   - يوفر بثاً مباشراً لكاميرا الهاتف (الأمامية أو الخلفية) مع صوت الميكروفون عبر WebRTC P2P Video/Audio Tracks.

2. **جهاز تحكم رئيسي (Native Controller Mode):**
   - واجهة أصلانية مبنية بالكامل بلغة Kotlin و Jetpack Compose لربط جوال آخر واستعراض ملفاته وسجلاته بسرعة فائقة ودعم حفظ الملفات والتنزيل المحلي.

3. **لوحة الويب المدمجة (Embedded Web Controller - WebView Browser & Desktop):**
   - دمج واجهة الويب الاحترافية (`web-dashboard/index.html`) كأصل مدمج داخل التطبيق (`file:///android_asset/web-dashboard/index.html`) وتوفرها للمتصفحات الخارجية.
   - تشمل مستكشف الذاكرة والملفات، شاشة البث الحي للكاميرا والميكروفون، سجلات المكالمات، الرسائل، الأسماء، والإشعارات، مع محرك فرز وتصفية متقدم وفوري لجميع الجداول والقوائم.

---

## 3. الهوية والبيانات الفعلية للمشروع (Real Project Specifications)

| العنصر | القيمة الحقيقية والمعتمدة |
| :--- | :--- |
| **اسم التطبيق (App Name)** | `جسر عن بعد` (RemoteBridge Pro) |
| **معرّف الحزمة (Application ID)** | `com.remotebridgepro` |
| **نطاق الكود (Namespace)** | `com.remotebridgepro` |
| **حزمة المصدر (Source Package)** | `com.remotebridge` |
| **التوافق التأسيسي (Target Platform)** | أندرويد أصلاني Native (Android 11+ / API 30+، compileSdk 36) |
| **بيئة البناء والتصدير** | Gradle Kotlin DSL + Android Studio + Jetpack Compose |
| **قاعدة البيانات المحلية** | Room DB (`bridge.db` v1.0) |
| **مخزن الإعدادات** | DataStore (`bridge_settings`) |
| **عنوان سيرفر الأشارات (Cloud - Render)** | `wss://remotebridge1.onrender.com/ws` |
| **عنوان سيرفر الأشارات (Cloud - Fly.io)** | `wss://my-ai-bridge-app.fly.dev/ws` |
| **عنوان سيرفر الأشارات (Local LAN)** | `ws://<Local-IP-Address>:10000/ws` |
| **منفذ الاتصال المحلي الافتراضي** | `10000` |
| **خادم الـ STUN الافتراضي** | `stun:stun.l.google.com:19302` |
| **معرّف الجهاز المضيف (Host Device ID)** | يُولد تلقائياً بتنسيق فريد ويُعرض في شاشة المضيف للربط مع "المعرف المستهدف" (Target Device ID) في الويب |

### آلية عمل خادم Google STUN وخادم Render في الاتصال:
1. **خادم Render (`wss://remotebridge1.onrender.com/ws`):** يعمل كخادم إشارات (Signaling Relay) عبر WebSocket للتعارف الأولي وتبادل رسائل الـ SDP والعناوين بين المضيف والمتحكم بدون تمرير فيديو الكاميرا أو الصوت المباشر.
2. **خادم Google STUN (`stun:stun.l.google.com:19302`):** يعمل كخدمة استكشاف عناوين الـ Public IP والـ NAT لكلا الجهازين (الأندرويد ومتصفح الويب) لتمكين قنوات الـ WebRTC من البث الحي المباشر (Peer-to-Peer) بأعلى جودة وأقل زمن تأخير.

---

## 4. مصفوفة الأذونات والصلاحيات المعتمدة (Permission Architecture Matrix)

تم إدراج وضبط كافة الأذونات المطلوبة للعمل بكفاءة على أندرويد 11+ وتوافقية أندرويد 14+ داخل `AndroidManifest.xml` وواجهات Compose:

| التصنيف | الصلاحية (Permission ID) | الغرض والوظيفة | آلية المنح والتفعيل |
| :--- | :--- | :--- | :--- |
| **الإنترنت والشبكة** | `android.permission.INTERNET` | الاتصال بخادم الإشارات وشبكة الـ WebRTC | تثبيت تلقائي |
| | `android.permission.ACCESS_NETWORK_STATE` | فحص حالة الاتصال بالإنترنت وWi-Fi | تثبيت تلقائي |
| **العمل بالخلفية** | `android.permission.FOREGROUND_SERVICE` | إبقاء خدمة المضيف تعمل باستقرار | تثبيت تلقائي |
| | `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | مزامنة ونقل الملفات والبيانات | تثبيت تلقائي |
| | `android.permission.FOREGROUND_SERVICE_CAMERA` | استمرار بث الكاميرا في الخدمة الأمامية | تثبيت تلقائي |
| | `android.permission.FOREGROUND_SERVICE_MICROPHONE` | استمرار بث الميكروفون في الخدمة الأمامية | تثبيت تلقائي |
| | `android.permission.WAKE_LOCK` | منع نوم المعالج أثناء جلسات النقل المباشر | تثبيت تلقائي |
| | `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | استثناء التطبيق من قيود توفير الطاقة (Doze Mode) | نافذة إعدادات النظام |
| **الإشعارات** | `android.permission.POST_NOTIFICATIONS` | إظهار إشعار الخدمة الأمامية وإشعارات التنبيه (Android 13+) | تصريح وقت التشغيل Runtime |
| | `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` | رصد واستقبال إشعارات التطبيقات على المضيف | إعدادات الوصول للإشعارات |
| **الملفات والذاكرة** | `android.permission.MANAGE_EXTERNAL_STORAGE` | استكشاف وتصفح كافة ملفات وذاكرة الهاتف (Android 11+) | شاشة منح الوصول الكامل للملفات |
| | `android.permission.READ_EXTERNAL_STORAGE` | قراءة الملفات في الإصدارات الأقدم (حتى Android 12) | تصريح وقت التشغيل Runtime |
| | `android.permission.WRITE_EXTERNAL_STORAGE` | حفظ وتصدير الملفات محلياً (maxSdkVersion 29) | تصريح وقت التشغيل Runtime |
| | `android.permission.READ_MEDIA_IMAGES` | قراءة وتصفح الصور بجودة عالية (Android 13+) | تصريح وقت التشغيل Runtime |
| | `android.permission.READ_MEDIA_VIDEO` | قراءة واستعراض مقاطع الفيديو (Android 13+) | تصريح وقت التشغيل Runtime |
| | `android.permission.READ_MEDIA_AUDIO` | قراءة المقاطع والتسجيلات الصوتية (Android 13+) | تصريح وقت التشغيل Runtime |
| **الرسائل والمكالمات** | `android.permission.READ_SMS` | قراءة واستعراض الرسائل النصية القصيرة | تصريح وقت التشغيل Runtime |
| | `android.permission.RECEIVE_SMS` | استقبال ومزامنة الرسائل الواردة فوراً | تصريح وقت التشغيل Runtime |
| | `android.permission.READ_CONTACTS` | قراءة أسماء وجهات الاتصال وأرقام الهواتف | تصريح وقت التشغيل Runtime |
| | `android.permission.READ_CALL_LOG` | قراءة سجل المكالمات (الواردة، الصادرة، الفائتة) | تصريح وقت التشغيل Runtime |
| **الكاميرا والصوت** | `android.permission.CAMERA` | التقاط وبث الفيديو الحي للكاميرا الأمامية والخلفية | تصريح وقت التشغيل Runtime |
| | `android.permission.RECORD_AUDIO` | التقاط وبث الصوت الحي للميكروفون | تصريح وقت التشغيل Runtime |

---

## 5. ميزات وهيكل واجهة الويب ولوحة المتابعة (Web Dashboard v2.0 Architecture)

تتضمن واجهة الويب الموحدة (`web-dashboard/index.html` و `assets/web-dashboard/index.html`) الأقسام التالية:

### 1. مستكشف ملفات وذاكرة الهاتف (Storage & File Explorer)
- **مسار التصفح الحي (Breadcrumbs):** شريط يعرض المسار الفعلي داخل الهاتف (مثل `/Download/` أو `/DCIM/`).
- **محرك التصفية حسب النوع:** مجلدات (`folder`)، صور (`image`)، فيديو (`video`)، صوتيات (`audio`)، مستندات (`document`).
- **محرك الفرز التفاعلي:**
  - فرز حسب: **الاسم** (أبجدياً)، **الحجم** (تصاعدي/تنازلي بالأحجام الفعلية)، **التاريخ** (أحدث/أقدم)، أو **النوع**.
  - زر تبديل اتجاه الفرز (تصاعدي ⬆️ / تنازلي ⬇️).
- **أزرار الإجراءات:** تنزيل الملفات، فتح المجلدات، وعرض البيانات الوصفية (MIME-Type، الحجم، تاريخ التعديل).

### 2. البث المباشر للكاميرا والميكروفون (Live Camera & Mic Streaming)
- نافذة عرض فيديو حية عبر عنصر `<video autoplay playsinline>` وموجه صوت متصل بمسار WebRTC Audio/Video Track القادم من الهاتف.
- أزرار تحكم كاملة من واجهة الويب:
  - **بث الكاميرا والصوت** (عبر أمر `start_camera_stream` و `stop_camera_stream`).
  - **بث الصوت فقط من الميكروفون** (عبر أمر `start_audio_stream` مع إيقاف الكاميرا وعرض مؤشر بصرى تفاعلي للصوت).
  - **التبديل بين الكاميرا الأمامية والخلفية** (عبر أمر `switch_camera` و `CameraVideoCapturer.switchCamera`).
  - **كتم / تفعيل الميكروفون** (عبر أمر `toggle_audio` و `AudioTrack.setEnabled`).
- تم إزالة خيارات البث الحي المباشر من واجهة التطبيق الأصلية على الهاتف، حيث يقتصر العرض والتحكم والإدخال بالكامل على واجهة الويب عبر المتصفح.

### 3. قائمة الأسماء وجهات الاتصال (Contacts List)
- بطاقات عرض مريحة تتضمن الاسم، الحرف الأول المميز، والتصنيف (شخصي، عمل، موبايل).
- استعراض كامل أرقام الهواتف المرتبطة بكل جهة اتصال مع زر نسخ مباشر بنقرة واحدة.
- **تصفية وفرز:** بحث فوري بالاسم أو الرقم، فرز حسب الاسم أو عدد الأرقام المسجلة.

### 4. سجل الرسائل النصية القصيرة (SMS Messages)
- استعراض الرسائل في بطاقات منسقة مع شارة تمييزية لنوع الرسالة (واردة 📩 / صادرة 📤).
- عرض اسم جهة الاتصال أو رقم المرسل، التاريخ والوقت الدقيق، والنص الكامل للرسالة.
- **تصفية وفرز:** تصفية حسب الرسائل الواردة أو الصادرة، فرز حسب التاريخ أو المرسل.

### 5. سجل المكالمات (Call Logs)
- بطاقات مكالمات واضحة بشارات بصرية: 🟢 واردة، 🔵 صادرة، 🔴 فائتة.
- توثيق دقيق لمدة المكالمة (بالدقائق والثواني) وتوقيت الاتصال.
- **تصفية وفرز:** تصفية حسب نوع المكالمة، فرز حسب التاريخ الأحدث، أطول مدة، أو الاسم.

### 6. الإشعارات الحية (Live Notifications)
- رصد مستمر للإشعارات الواردة إلى هاتف المضيف مع اسم التطبيق والحزمة (`packageName`).
- عرض العنوان ومحتوى الإشعار ووقت الوصول.
- **تصفية وفرز:** بحث فوري في محتوى الإشعارات وفرز تصاعدي أو تنازلي بالتاريخ.

---

## 6. بروتوكول الأوامر المزدوج (Dual-Transport RPC Protocol)

يدعم النظام نقل الأوامر إما عبر قناة بيانات WebRTC DataChannel المباشرة أو عبر خادم الإشارات (WebSocket Relay) كمسار احتياطي عالي الاعتمادية:

| الأمر (Command) | المعاملات (Parameters) | الوصف |
| :--- | :--- | :--- |
| `ping` | `{}` | فحص زمن استجابة الاتصال |
| `device_info` | `{}` | قراءة نسبة البطارية الفعلية، حالة الشحن، المساحة الحرة، وإصدار أندرويد |
| `storage_explore` | `{"path": "/..."}` | استكشاف مجلدات وذاكرة الهاتف بالمسار المباشر |
| `file_list` | `{"bucket": "images\|videos\|..."}` | جلب قائمة الملفات من MediaStore |
| `file_open` | `{"fileId": 123, "bucket": "..."}` | فتح وتنزيل ملف مقطعاً بحجم 64KB عبر Binary Chunks |
| `sms_list` | `{}` | جلب أحدث رسائل SMS النصية الحقيقية |
| `contacts_list` | `{}` | جلب قائمة جهات الاتصال والأرقام الحقيقية |
| `calls_list` | `{}` | جلب سجل المكالمات الحقيقي |
| `notifications_list`| `{}` | جلب الإشعارات النشطة والمخزنة مؤقتاً |
| `start_camera_stream`| `{"front": boolean, "audio": boolean}` | تشغيل بث الكاميرا والميكروفون |
| `start_screen_stream`| `{}` | تشغيل بث شاشة الجوال المباشرة عبر MediaProjection |
| `start_audio_stream` | `{}` | تشغيل بث الصوت الحي فقط من الميكروفون بدون كاميرا |
| `stop_camera_stream` | `{}` | إيقاف بث الكاميرا والصوت |
| `switch_camera` | `{}` | التبديل الفوري بين الكاميرا الأمامية والخلفية |
| `toggle_audio` | `{"enable": boolean}` | كتم أو تفعيل صوت الميكروفون |

---

## 7. الهيكل المصدري المحدث للمشروع (Full File Tree Structure)

```text
/app/applet/ (مساحة العمل الرسمية)
├── app/
│   ├── build.gradle.kts                    # إعدادات البناء، الاعتماديات، و compileSdk 36
│   └── src/main/
│       ├── AndroidManifest.xml             # الأذونات الكاملة، الخدمات الأمامية المسجلة
├── app/
│   ├── build.gradle.kts                    # إعدادات البناء، الاعتماديات، و compileSdk 36
│   └── src/main/
│       ├── AndroidManifest.xml             # الأذونات الكاملة، الخدمات الأمامية المسجلة
│       ├── res/                            # السلاسل المترجمة والسمات والأيقونات
│       └── java/com/remotebridge/
│           ├── BridgeApplication.kt        # نقطة البداية وتهيئة Room
│           ├── MainActivity.kt             # النشاط الرئيسي لـ Compose
│           ├── controller/                 # إدارة اتصالات التحكم وWebRTC
│           ├── core/
│           │   ├── data/                   # Room DB & DataStore Repositories
│           │   ├── model/Models.kt         # Naming, Signaling, RPC Commands & Models
│           │   ├── signaling/              # WebSocket & Signaling Logic
│           │   └── webrtc/                 # P2P Engine, Camera2 & Chunk Protocol
│           ├── host/                       # Host Data Providers & Services
│           │   ├── provider/HostDataProvider.kt # Direct Storage & ContentResolver
│           │   └── service/                # HostForegroundService & NotificationListener
│           └── ui/
│               ├── navigation/Navigation.kt # مسارات التنقل (تطبيق الأندرويد الأصلي)
│               ├── screens/
│               │   ├── controller/         # Native Controller Screen
│               │   ├── dashboard/          # Remote Data Dashboard
│               │   ├── files/              # Media & File Explorer
│               │   ├── home/HomeScreen.kt  # اختيار نمط المضيف أو التحكم
│               │   ├── host/HostScreen.kt  # شاشة المضيف، مركز الصلاحيات، وبث الكاميرا
│               │   └── settings/           # ضبط واسترجاع عناوين السيرفر
│               └── theme/                  # Material 3 Custom Theme
├── web-dashboard/index.html                # واجهة الويب الخارجية المستقلة (المتحكم والطرف الآخر)
├── main.go                                 # خادم الإشارات الرسمي بلغة Go (Port 10000)
├── Dockerfile                              # حاوية النشر السحابي
├── render.yaml / fly.toml                  # ملفات التكليفات السحابية
└── build.gradle.kts / settings.gradle.kts # ملفات تهيئة بناء الأندرويد
```

---

## 8. فصل واجهة الويب وتعزيز قنوات الاتصال والحصول على البيانات الحية

- **فصل الواجهة بالكامل:** تم إزالة متصفح الويب المدمج من تطبيق الأندرويد ليعمل التطبيق كمضيف/متحكم أصلي، وتعمل واجهة `web-dashboard/index.html` كمتحكم مستقل كلياً عبر المتصفح.
- **دعم البيانات المباشرة فوق DataChannel:** دعم الاستجابات بنصوص UTF-8 JSON المباشرة والدعم التلقائي للبيانات المزدوجة (Raw UTF-8 JSON & Binary Framing).
- **التصنيف الذكي للبيانات:** تحليل تلقائي ونوعي للاستجابات (Contacts, SMS, Calls, Notifications, Files) وعرضها فوراً في واجهة المتحكم.
- **إعادة المصافحة المباشرة للـ WebRTC (Renegotiation):** إعادة إرسال عرض WebRTC تلقائياً عند بدء بث الكاميرا أو الشاشة أو الصوت لضمان وصول مسارات الفيديو والصوت الحية مباشرة دون تأخير.
- **الفحص الدوري التلقائي (3 ثوانٍ):** استمرار فحص الأجهزة المضيفة عبر `autoDiscoverAndConnectHosts()` وتحديث قائمة الهواتف المتاحة والربط المباشر بها.

---

## 9. فحص اتصال خوادم Google STUN وتشخيص ترحيل مرشحات ICE (WebRTC Diagnostics)

- **دالة اختبار STUN عند الطلب (On-Demand STUN Testing):**
  - تم توفير الدالة العالمية `window.testStunConnectivity([customStunUrl])` ومرادفها `testGoogleStunServer()` متاحة للاستدعاء الفوري من كونسول المتصفح (Browser Console) أو عبر زر مخصص في تبويب الإعدادات (Settings Tab).
  - تقوم الدالة بإنشاء `RTCPeerConnection` تجريبي وتوليد Offer وقناة بيانات، ثم فحص استجابة خوادم STUN (`stun:stun.l.google.com:19302` وغيرها).
  - تتحقق الدالة تلقائياً من وصول مرشحات من نوع **`srflx` (Server Reflexive)** التي تثبت أن منافذ UDP مفتوحة وغير محظورة من قبل جدار الحماية أو مزود الخدمة، وتعرض جدول تفصيلي بالعناوين والمنافذ في كونسول المتصفح.

- **سجل التشخيص الموسع لمرشحات ICE (`onicecandidate` Logging):**
  - تسجيل دقيق لكافة بيانات المرشح المحلي: نوع المرشح (`host`, `srflx`, `relay`)، البروتوكول (`UDP` / `TCP`)، عنوان الـ IP والمنفذ (`address:port`)، و`sdpMid` و`sdpMLineIndex`.
  - التحقق الفوري من حالة اتصال خادم الإشارات WebSocket وتأكيد نجاح عملية ترحيل المرشح إلى جهاز المضيف المستهدف (`Relayed to Server`).
  - رصد وتدوين حالات تغير تجميع المرشحات (`onicegatheringstatechange`) وتغير حالة اتصال الـ ICE المباشر (`oniceconnectionstatechange`) وتنبيه المستخدم بأي أخطاء عبر `onicecandidateerror`.

- **إصلاح وجهة ترحيل مرشحات ICE في جهاز المضيف الأندرويد (`activeControllerId`):**
  - تم إصلاح المشكلة الجوهرية في `HostForegroundService.kt` حيث كانت ترسل مرشحات المضيف بقيمة وجهة فارغة (`to = ""`) مما كان يؤدي لإسقاطها من قبل خادم الإشارات `main.go`.
  - أصبح التطبيق يحفظ معرّف جهاز التحكم (`activeControllerId = signal.from`) فور استقبال `Signal.Offer`، ويرسل كافة مرشحات ICE الخاصة بالمضيف موجهة مباشرة إلى معرّف جهاز التحكم لضمان نجاح المصافحة الـ P2P.

- **تخزين مؤقت لمرشحات الـ ICE الواردة (Candidate Buffering):**
  - معالجة وصول مرشحات ICE قبل اكتمال تطبيق `setRemoteDescription(Answer)` في المتصفح عبر مصفوفة `pendingRemoteIceCandidates` وتفريغها وتطبيقها تلقائياً بمجرد إتمام المصافحة لمنع فقدان أي مرشحات اتصال.

---

## 10. معمارية التوافق مع أندرويد 13 (Android 13 / API 33 Architecture) وحل تعذر جلب البيانات والبث

1. **قراءة البطارية المتوافقة مع أندرويد 13 (`BatteryManager` Direct Property):**
   - تم تحديث آلية قراءة البطارية في `HostDataProvider.kt` لتعتمد نظام `BatteryManager.BATTERY_PROPERTY_CAPACITY` المباشر والدقيق في أندرويد 13 بدلاً من الاعتماد الحصري على النوايا الملصقة (Sticky Intents) التي قد تخضع لقيود الحماية والخلفية في أنظمة OEM.
   - التحقق الفوري والدقيق من حالة الشحن عبر `BatteryManager.isCharging` وخاصية `BATTERY_PROPERTY_STATUS`.

2. **حل عدم استجابة الأوامر وتعذر جلب الرسائل وسجلات الهاتف:**
   - **تحديد هوية الأمر (Command Tagging):** تم تضمين حقل `"cmd"` الصريح في كافة استجابات `Signal.RpcResponse` ومعالجة الطلبات داخل `HostForegroundService.kt` لتمكين واجهة الويب من مطابقة وتصنيف الاستجابات وتحديث الجداول فوراً حتى لو كانت القوائم فارغة أو بانتظار تصاريح الوصول.
   - **المزامنة الصريحة لمعرّف المضيف المستهدف:** ربط دالة `selectHost(deviceId)` بالتحديث الفوري لحقل `target-device-id` وقيمة `appConfig.targetDeviceId` وحفظها في `localStorage` لمنع إرسال الأوامر بالمعرف الافتراضي القديم.
   - **الاستجابة الفورية عند انقطاع المضيف في `main.go`:** إضافة إرسال إشعار خطأ مباشر `rpc_response` فوري من خادم الإشارات إلى المتحكم في حال كان المضيف غير متصل أو المعرّف غير متطابق، للقضاء نهائياً على مشكلة الانتظار الصامت بدون نتيجة.

3. **حل تعذر بث الكاميرا والصوت عبر WebRTC:**
   - تفعيل إطلاق عرض WebRTC تلقائياً فور اكتشاف المضيف أو اختياره عبر `createWebRTCOffer(deviceId)` من واجهة الويب لضمان إنشاء وتجهيز `PeerConnection` وقناة البيانات قبل إطلاق أوامر البث.
   - إضافة تشغيل تلقائي مباشر لمسارات الصوت والفيديو الواردة في دالة `pc.ontrack` وتغليف المسارات الفردية في `MediaStream` متوافق مع كافة المتصفحات.
   - إعادة تفاوض الـ WebRTC تلقائياً عند طلب تشغيل الكاميرا أو الشاشة أو الصوت لاستقبال المسارات الجديدة المضافة في الجهاز المضيف فوراً.

---

## 11. لوحة مراقبة WebRTC المباشرة ونتائج الفحص الشبكي لخادم Render 1

### 1. لوحة المراقبة المباشرة في Dashboard (`updateWebRTCDiagnosticUI`):
- تم دمج لوحة تشخيص تفاعلية متقدمة أعلى لوحة المتابعة الرئيسية تعرض بصرياً ولحظياً:
  - **حالة اتصال ICE (`iceConnectionState`):** `new`, `checking`, `connected`, `completed`, `failed`, `disconnected`, `closed` مع مؤشر لوني نبضي.
  - **حالة تجميع المرشحات (`iceGatheringState`):** `new`, `gathering`, `complete`.
  - **حالة اتصال الـ Peer (`connectionState`):** `new`, `connecting`, `connected`, `failed`.
  - **حالة قناة البيانات (`dataChannel.readyState`):** `open`, `connecting`, `closing`, `closed`.
  - **إحصائيات المرشحات المتبادلة:** عدد المرشحات المحلية (`localCandidatesCount`)، عدد المرشحات البعيدة المستلمة (`remoteCandidatesCount`)، ونوع آخر مرشح (`srflx`, `host`, `relay`).
  - **أزرار سريعة للإجراءات:** زر فحص STUN & UDP الفوري، وزر إعادة ربط P2P بضغطة واحدة.

### 2. التقرير الشبكي والفحص الحي لخادم Render (`remotebridge1.onrender.com`):
- **نقطة النهاية (WebSocket Endpoint):** `wss://remotebridge1.onrender.com/ws`
- **نقطة الفحص الصحي (Health Check):** `https://remotebridge1.onrender.com/` (أعادت استجابة `HTTP/2 200 OK - RemoteBridge Signaling Server is Active & Healthy!`).
- **ترقية بروتوكول الويب سوكيت (WS Upgrade):** تم التحقق الفعلي من إتمام ترقية الاتصال بنجاح عبر بروتوكول RFC 6455 برمز الحالة `101 Switching Protocols` مع تشفير TLS 1.3 عالي الأمان.
- **تحديث الإعدادات الافتراضية:** تم تحديث الرابط الافتراضي في `SettingsRepository.kt` و `SignalingClient.kt` و `HostScreen.kt` و `web-dashboard/index.html` ليعتمد تلقائياً خادم `remotebridge1`.

### 3. الجاهزية والاعتمادية لنظام أندرويد 13 (Android 13 / API 33):
- تم اعتماد استدعاء `BatteryManager.BATTERY_PROPERTY_CAPACITY` المباشر المتوافق كلياً مع أندرويد 13 والذي لا يتطلب تسجيل مستقبلات إذاعية قد تخضع لقيود تصدير الحزم في API 33 (`RECEIVER_NOT_EXPORTED`).
- تم ضبط واستيفاء تصاريح الخدمة الأمامية وتوفير مسار احتياطي آمن (`fallback`) يضمن استقرار الخدمة وعدم حدوث أي انهيار برمجي حتى في حال تأخر المستخدم عن منح إذن الكاميرا أو الميكروفون وقت الإقلاع.



