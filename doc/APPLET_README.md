# الدليل المرجعي والشامل الوحيد لنظام "جسر عن بعد" (RemoteBridge Pro Master Guide)

---

## 1. نظرة عامة ورسالة النظام (System Identity & Overview)

تطبيق **RemoteBridge Pro (جسر عن بعد)** هو نظام هندسي متكامل ومتقدم مخصص لربط الأجهزة الذكية والأجهزة البعيدة بنمط الأندرويد المباشر (**Peer-to-Peer**) المعتمد على بروتوكولات **WebRTC** وخادم إشارات خفيف الوزن بلغة **Go**.

يتيح النظام تحويل أي هاتف أندرويد قديم إلى جهاز مضيف (**Host**) يعمل في الخلفية باستقرار عالٍ، واستعراض وسائطه، وملفاته، ورسائله، وجهات اتصاله، وإشعاراته اللحظية، وحالة بطاريته عن بعد عبر هاتف آخر (**Controller**) أو عبر لوحة تحكم الويب (**Web Dashboard**) على أجهزة الكمبيوتر.

### 🌟 الهوية والبيانات الفعلية للمشروع:
| العنصر | القيمة الحقيقية في المشروع |
| :--- | :--- |
| **اسم التطبيق (App Name)** | `جسر عن بعد` (RemoteBridge Pro) |
| **معرّف الحزمة البنائي (Application ID)** | `com.remotebridgepro` |
| **نطاق الكود المصدري (Namespace)** | `com.remotebridgepro` |
| **حزمة الكود (Source Package)** | `com.remotebridge` |
| **الكلاس الرئيسي للتطبيق** | `com.remotebridge.BridgeApplication` |
| **النشاط الرئيسي (Main Activity)** | `com.remotebridge.MainActivity` |
| **قاعدة البيانات المحلية (Room DB)** | `bridge.db` (الإصدار 1.0) |
| **مخزن الإعدادات (DataStore)** | `bridge_settings` |
| **منفذ الخادم المحلي الافتراضي (Port)** | `10000` (أو `8080` في بعض الخوادم المحلية) |

---

## 2. الهيكل الحقيقي لمجلدات وأكواد المشروع (Actual Project File Tree)

```text
/ (المستودع الرئيسي للمشروع)
├── app/                                    # وحدة تطبيق الأندرويد الرئيسي
│   ├── build.gradle.kts                    # إعدادات البناء، Namespace، والاعتماديات
│   ├── proguard-rules.pro                  # قواعد حماية وضغط الكود
│   └── src/main/
│       ├── AndroidManifest.xml             # الأذونات والخدمات والأنشطة المسجلة
│       ├── res/                            # الموارد الرسومية والنصية والسمات
│       │   ├── values/strings.xml          # النصوص العربية الرسمية
│       │   ├── values/themes.xml           # سمة Material 3 الزمردية (Teal)
│       │   └── xml/file_paths.xml          # مسارات مشاركة الملفات المحمية
│       └── java/com/remotebridge/          # الكود المصدري بلغة Kotlin
│           ├── BridgeApplication.kt        # تهيئة التطبيق وقاعدة بيانات Room
│           ├── MainActivity.kt             # المدخل الرسومي الرئيسي لـ Compose
│           ├── controller/                 # وحدة جهاز التحكم (Controller)
│           │   ├── ControllerManager.kt   # إدارة اتصالات WebSocket و WebRTC
│           │   └── RemoteRepository.kt     # مستودع جلب البيانات والتخزين المؤقت
│           ├── core/                       # النواة المشتركة للنظام
│           │   ├── data/                   # قاعدة البيانات والـ DAOs والإعدادات
│           │   │   ├── BridgeDatabase.kt   # قاعدة بيانات Room الرسمية
│           │   │   ├── SettingsRepository.kt # إدارة إعدادات DataStore
│           │   │   ├── dao/                # CachedFileDao, CachedSmsDao
│           │   │   └── entity/             # CachedFileEntity, CachedSmsEntity
│           │   ├── discovery/              # الاكتشاف التلقائي بالشبكة المحلية
│           │   │   └── LocalDiscoveryManager.kt # فحص شبكة الـ Wi-Fi والمنفذ 10000
│           │   ├── model/Models.kt         # نماذج البيانات المتبادلة والأوامر
│           │   ├── signaling/              # عميل WebSocket وبروتوكولات الإشارة
│           │   │   ├── Signal.kt           # رسائل SDP / ICE / Register
│           │   │   └── SignalingClient.kt  # عميل الاتصال وإعادة الربط التلقائي
│           │   └── webrtc/                 # محرك WebRTC P2P وتقطيع البيانات
│           │       ├── EglBaseProvider.kt  # سياق OpenGL العتادي المشترك
│           │       ├── FrameProtocol.kt    # بروتوكول تأطير وتقطيع الـ Chunks
│           │       ├── PeerConnectionManager.kt # محرك P2P وتدفق البيانات
│           │       └── SimpleSdpObserver.kt # مراقب جلسات الـ SDP
│           ├── host/                       # وحدة الجهاز المضيف (Host)
│           │   ├── provider/HostDataProvider.kt # قراءة MediaStore, SMS, Contacts
│           │   └── service/                # الخدمات الأمامية ومستقبلات البث
│           │       ├── BootReceiver.kt     # التشغيل التلقائي عند إقلاع النظام
│           │       ├── BridgeNotificationListenerService.kt # التقاط الإشعارات
│           │       ├── DeviceIdentifier.kt # إدارة المعرّف الثابت للجهاز
│           │       ├── HostForegroundService.kt # الخدمة الأمامية المتصلة بالشبكة
│           │       └── NotificationHelper.kt # إدارة قنوات وإشعارات أندرويد
│           └── ui/                         # واجهة المستخدم بنمط Jetpack Compose
│               ├── navigation/Navigation.kt # شجرة التنقل والمسارات الرسمية
│               ├── screens/                # شاشات التطبيق الست الرسمية
│               │   ├── controller/ControllerScreen.kt # اكتشاف الأجهزة والمصافحة
│               │   ├── dashboard/DashboardScreen.kt   # لوحة تحكم البيانات
│               │   ├── files/FileListScreen.kt       # استكشاف الوسائط والتنزيل
│               │   ├── home/HomeScreen.kt             # اختيار دور الجهاز
│               │   ├── host/HostScreen.kt             # إدارة وضع المضيف والأذونات
│               │   └── settings/SettingsScreen.kt     # ضبط السيرفر والخيارات الثلاثة
│               ├── theme/Theme.kt          # السمة البصرية الرسمية
│               └── viewmodel/              # فئات إدارة الحالة (ViewModels)
├── web-dashboard/                          # لوحة تحكم الويب المستقلة
│   └── index.html                          # واجهة الويب بلغة HTML/JS و Tailwind CSS
├── main.go                                 # خادم الإشارات الرسمي بلغة Go
├── go.mod / go.sum                         # وحدة واعتمديات خادم Go (Gorilla WebSocket v1.5.3)
├── Dockerfile                              # حاوية البناء والنشر السحابي
├── fly.toml                                # إعدادات النشر السحابي على Fly.io
├── render.yaml                             # إعدادات النشر السحابي على Render
├── run-server.ps1                          # سكريبت التشغيل المحلي عبر PowerShell
├── build-and-test.ps1                      # سكريبت اختبار وبناء التطبيق
├── build.gradle.kts                        # ملف البناء الرئيسي للجذر
├── settings.gradle.kts                     # تعريف المشروع وتسميته
├── gradle.properties                       # إعدادات الذاكرة وبناء Gradle
└── gradle/libs.versions.toml               # كتالوج الكائنات والإصدارات الرسمية
```

---

## 3. مواصفات السيرفر والروابط السحابية والمحلية المعتمدة (Connection URLs & Presets)

يتضمن التطبيق خيارات ربط سريعة مصفوفة بصف واحد، مع تعبئة وحفظ تلقائي فوري:

### 🔗 خيارات الرابط الافتراضية المعتمدة:
1. ☁️ **خادم Render السحابي (Render Cloud Preset):**
   `wss://remotebridge1.onrender.com/ws`
2. 🌐 **خادم Fly.io السحابي (Fly.io Cloud Preset):**
   `wss://my-ai-bridge-app.fly.dev/ws`
3. 💻 **الربط المحلي بدون إنترنت (Local Offline Format):**
   `ws://<Local-IP-Address>:10000/ws`
   - *مثال المحاكي المحلي:* `ws://10.0.2.2:10000/ws`
   - *مثال الـ Wi-Fi المحلي:* `ws://192.168.1.100:10000/ws`

### 🔍 ميزة الفحص والاكتشاف التلقائي لشبكة الـ LAN (`LocalDiscoveryManager`):
- عند النقر على زر **"محلي LAN/Port"** في الأندرويد، يقوم كلاس `LocalDiscoveryManager` بمسح عنوان الـ IP الخاص بنطاق شبكة الـ Wi-Fi الحالية على المنفذ `10000` بتوازٍ سريع، واكتشاف عنوان الخادم المحلي تلقائياً وحفظه بنقرة واحدة دون الحاجة لإدخال الـ IP والمنفذ يدوياً.

---

## 4. المواصفات والاعتماديات الفنية الحقيقية (Real Technical Specifications)

### أ. أدوات وإصدارات البناء (Build Tools):
- **إصدار أندرويد SDK البنائي (Compile SDK):** `36`
- **أدنى إصدار أندرويد مدعوم (Min SDK):** `26` (Android 8.0 Oreo)
- **الإصدار المستهدف (Target SDK):** `36` (Android 15+)
- **مكون Gradle Plugin (AGP):** `9.1.1`
- **لغة كوتلن (Kotlin):** `2.2.10`
- **محرّك البناء (Gradle):** `8.11.1`
- **معالج الرموز (KSP):** `2.3.5`

### ب. الاعتماديات والمكتبات المدمجة:
- **واجهة المستخدم:** `Jetpack Compose (Compose BOM 2024.09.00)`, `Material 3`, `Navigation Compose 2.8.9`.
- **التخزين المحلي:** `Room DB 2.7.0` مع معالج `KSP` لإنشاء جداول `cached_files` و `cached_sms` بداخل `bridge.db`، و `DataStore Preferences 1.1.7` لبيانات `bridge_settings`.
- **الربط والشبكات P2P:** `Stream WebRTC Android 1.3.9`, `Java-WebSocket 1.5.7`, `OkHttp 4.10.0`, `Kotlinx Serialization Json 1.7.3`.
- **عرض وسائط الفيديو والصور:** `Media3 ExoPlayer 1.5.1`, `Coil Compose 2.7.0`.

---

## 5. تكوين الأذونات والخدمات في النظام (`AndroidManifest.xml`)

### أ. الأذونات المفعلة:
1. **الشبكة:** `INTERNET`, `ACCESS_NETWORK_STATE`
2. **الخدمات الأمامية:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`
3. **التشغيل عند الإقلاع:** `RECEIVE_BOOT_COMPLETED`
4. **الوصول للوسائط:** `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`)
5. **البيانات الشخصية:** `READ_SMS`, `READ_CONTACTS`, `READ_CALL_LOG`

### ب. الخدمات والأنشطة المسجلة:
- **التطبيق (Application):** `com.remotebridge.BridgeApplication`
- **النشاط الرئيسي (Activity):** `com.remotebridge.MainActivity`
- **خدمة المضيف الأمامية:** `com.remotebridge.host.service.HostForegroundService` (متوافقة مع Android 14+ بنوع `dataSync`)
- **خدمة قراءة الإشعارات اللحظية:** `com.remotebridge.host.service.BridgeNotificationListenerService`
- **مستقبل الإقلاع التلقائي:** `com.remotebridge.host.service.BootReceiver`

---

## 6. بروتوكول تأطير ونقل البيانات (`FrameProtocol.kt`)

يستخدم النظام ترويسة ثابتة بطول **17 بايت** لجميع البيانات المتبادلة عبر قناة WebRTC DataChannel الموثوقة (`bridgeData`):

```text
 0          1           5           9          13          17 (Bytes)
+-----------+-----------+-----------+-----------+-----------+----------------------+
| Type (1B) | MsgID(4B) | Seq (4B)  | Total(4B) | Len (4B)  | Payload (Len Bytes)  |
+-----------+-----------+-----------+-----------+-----------+----------------------+
```

- **Type = 0:** رسائل تحكم واستعلام بلغة JSON (طلب قائمة الصور أو الرسائل).
- **Type = 1:** قطع ثنائية للملفات والصور (Chunk Payload).
- **حجم القطعة الواحدة (Chunk Size):** `16 KB` (`16384 Bytes`) لضمان أقصى سرعة نقل واستقرار.

---

## 7. دليل التشغيل، البناء، والنشر السحابي (Operational Commands)

### أ. تشغيل خادم Go محلياً:
```powershell
# من جذر المشروع:
go run main.go
# أو عبر السكريبت المرفق:
.\run-server.ps1
```
*(يعمل الخادم تلقائياً على منفذ `10000`)*

### ب. بناء تطبيق الأندرويد وإخراج الـ APK:
```powershell
gradle assembleDebug
```
- **مسار ملف الـ APK الناتج النهائي:**
  `app/build/outputs/apk/debug/app-debug.apk`

### ج. النشر السحابي على Fly.io:
```powershell
fly auth login
fly deploy
```

### د. النشر السحابي على Render:
- ارفع المشروع إلى مستودع GitHub، واختر **New Blueprint** في Render؛ سيتم قراءة `render.yaml` ونشر الخادم تلقائياً على المنفذ `10000`.

---

## 8. دليل الاستخدام والربط الميداني (Field Usage Guide)

### 📱 أ. تجهيز الهاتف القديم (Host):
1. قم بتثبيت `app-debug.apk` وافتح التطبيق باختيار **"جهاز مُضيف (القديم)"**.
2. اختر السيرفر المناسب من خيارات الخوادم السريعة الثلاثة (Render أو Fly.io أو محلي).
3. اضغط **"منح الأذونات"** ثم اضغط **"بدء المضيف"**.

### 📱 ب. التحكم عبر هاتف آخر (Controller):
1. افتح التطبيق واختر **"جهاز تحكم (الجديد)"**.
2. حدد المضيف المتصل واضغط **"اتصال"** لفتح لوحة البيانات وتصفح الملفات والرسائل.

### 💻 ج. المتابعة عبر متصفح الكمبيوتر (Web Dashboard):
1. افتح الملف **`web-dashboard/index.html`** بداخل أي متصفح.
2. اختر أحد السيرفرات السريعة بصف واحد (Render / Fly.io / محلي) أو أدخل المعرف واضغط **"اتصال بالجهاز"**.

---

## 9. الخلاصة
هذا الملف (`README.md`) هو التوثيق الموحد المكتمل والمرجع الشامل والوحيد لمشروع **RemoteBridge Pro**، وقد تم استبعاد جميع ملفات التوثيق الفرعية الأخرى للتسهيل والاعتماد التام عليه.
