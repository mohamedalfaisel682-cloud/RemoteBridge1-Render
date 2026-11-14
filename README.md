# RemoteBridge Pro Studio — RBstudioPRO (RBstudioAS)

تطبيق أندرويد للتحكم عن بعد بين واجهة ويب (متحكم) وتطبيق أندرويد (مضيف) باستخدام `WebRTC`، وخادم إشارات، وبروتوكول `RPC`.

## 📌 النسخة المعتمدة للاختبار والتطوير لأول مرة

- **اسم التطبيق (App Name):** `RBstudioAS`
- **معرف الحزمة (Application ID & Namespace):** `com.rbstudioas`
- **رمز الإصدار (Version Code):** `12`
- **اسم الإصدار (Version Name):** `1.5.2`
- **حالة الاعتماد:** النسخة الأولى الناجحة والمكتملة والمعتمدة رسمياً كبدء أساس للاختبار والتطوير المباشر.

## 📋 الميزات

- **واجهة ويب للتحكم** (`Controller`): استكشاف الملفات، والبث الحي، والرسائل، وجهات الاتصال، والمكالمات.
- **تطبيق أندرويد مضيف** (`Host`): خدمة أمامية تعمل في الخلفية مع صلاحيات الكاميرا والميكروفون والملفات.
- **اتصال `WebRTC P2P`** مع دعم خوادم `TURN` للتغلب على `NAT` وجدران الحماية.
- **خادم إشارات** بلغة `Go` مع مصادقة بالتوكين ومقاييس `Prometheus`.
- **نقل بيانات موثوق** مع `ACK/NACK` و`checksum` من نوع `CRC32`.
- **استعادة تلقائية للاتصال** مع `ICE Restart` وإعادة المحاولة.

## 🗂️ هيكل المشروع

```text
RBstudioPRO/
├── web-dashboard/
│   ├── indexMergeFull.html   # الواجهة القانونية الحصرية للتعديل
│   └── indexMergeFull.sha256 # بصمة مرجعية لحارس التحقق
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/remotebridge/
│           ├── core/
│           │   ├── webrtc/          # PeerConnectionManager, FrameProtocol
│           │   ├── signaling/       # SignalingClient, Signal
│           │   ├── data/            # SettingsRepository
│           │   └── discovery/       # LocalDiscoveryManager
│           ├── host/
│           │   ├── service/         # HostForegroundService, DeviceIdentifier
│           │   └── provider/        # HostDataProvider
│           └── ui/                  # شاشات التطبيق (Compose)
├── main.go                          # خادم الإشارات (Go)
├── go.mod / go.sum                  # تبعيات Go
├── docker-compose.turn.yml          # تشغيل خادم TURN
├── Dockerfile                       # صورة دوكر للخادم
├── .env.example                     # مثال لمتغيرات البيئة
├── opencode.jsonc                   # إعدادات OpenCode و MCP
├── init.ps1 / init.sh               # سكريبتات تهيئة
├── extract_check.mjs                # فحص الواجهة القانونية
├── README.md                        # هذا الملف
└── دليل الفيصلي الذكي/              # الحوكمة والسجلات ووصف النسخ
```

## 🚀 التشغيل السريع

### 1. تشغيل خادم `TURN`

```bash
docker-compose -f docker-compose.turn.yml up -d
```

### 2. تشغيل خادم الإشارات

```bash
go run main.go
```

المنفذ الافتراضي هو `10000`، ويمكن تغييره عبر متغير البيئة `PORT`.

### 3. بناء تطبيق الأندرويد

```bash
./gradlew :app:assembleDebug --offline
```

مخرج البناء الفعلي:

```text
app/build/outputs/apk/debug/ApkFull-debug.apk
```

### 4. تثبيت `APK` على الهاتف

```bash
adb install -r app/build/outputs/apk/debug/ApkFull-debug.apk
```

### 5. فتح واجهة التحكم

بعد تشغيل الخادم، افتح:

```text
http://localhost:10000/
```

الخادم يقدّم `web-dashboard/indexMergeFull.html` تلقائيًا بوصفها الواجهة القانونية الحصرية. الملفان `index.html` و`indexMerge.html` مرجعان تاريخيان محفوظان في الأرشيف، ولا يُعدّلان، ولا يوجدان في الجذر الحالي.

## ✅ التحقق السريع

```bash
go vet ./...
go build -o remotebridge-server.exe .
./gradlew :app:compileDebugKotlin --offline
./gradlew :app:assembleDebug --offline
node extract_check.mjs
```

على `Windows PowerShell` استخدم `.\gradlew.bat` بدلًا من `./gradlew` عند الحاجة.

ملاحظة: يفحص `node extract_check.mjs` الآن ملفات الواجهة الموجودة فعليًا فقط، ويتحقق من وجود `web-dashboard/indexMergeFull.html` وبصمتها عبر `web-dashboard/indexMergeFull.sha256`. النتيجة الحالية `NODE_CHECK=PASS`. بعد أي تعديل معتمد على الواجهة القانونية، حدّث البصمة بالأمر `node extract_check.mjs --update-baseline`. العيوب `D1`–`D4` مُصلَحة وموثقة في جدول «الحالة المعروفة بعد الإصلاح» أدناه.

## 🔧 الإعدادات المهمة

### خادم Metered TURN / STUN المعتمد افتراضياً

تم دمج وتوثيق خادم Metered TURN المعتمد كإعداد افتراضي دائم في الكود المصدري وواجهة المتحكم الويب:

- **STUN Server:** `stun:stun.relay.metered.ca:80`
- **TURN Server:** `turn:standard.relay.metered.ca:80`
- **Username:** `76605207fb94657c3cfb5512`
- **Credential / Password:** `/Rhb/9cPGGJsPVXE`
- **API Key:** `a5062af1ec6052b35444b0bf853d161e2fc3`

### قائمة ICE Servers المعتمدة:
1. `stun:stun.relay.metered.ca:80`
2. `turn:standard.relay.metered.ca:80` (UDP)
3. `turn:standard.relay.metered.ca:80?transport=tcp` (TCP)
4. `turn:standard.relay.metered.ca:443` (UDP/TLS)
5. `turns:standard.relay.metered.ca:443?transport=tcp` (TURNS/TLS)

### متغيرات البيئة (`.env`)

انسخ `.env.example` إلى `.env`:

```env
TURN_SERVER_URL=turn:standard.relay.metered.ca:80
TURN_USERNAME=76605207fb94657c3cfb5512
TURN_PASSWORD=/Rhb/9cPGGJsPVXE
METERED_API_KEY=a5062af1ec6052b35444b0bf853d161e2fc3
SIGNALING_SERVER_URL=wss://remotebridge1.onrender.com/ws
```

## 📊 مراقبة الصحة والمقاييس

- **فحص الصحة:** `http://localhost:10000/health`
- **مقاييس `Prometheus`:** `http://localhost:10000/metrics`

كلا المسارين يخدمان من المنفذ نفسه؛ لا يوجد منفذ `9090` منفصل في هذا المشروع.

## 🧪 الحالة المعروفة بعد الإصلاح

| المعرّف | الوصف | الأثر | الحالة |
|---|---|---|---|
| `D1` | عنصر `legacy-iface-select` كان غائباً والدالة `mrgLegacyIfaceSwitch` فارغة | `NODE_CHECK=FAIL` سابقاً | مُصلَح — أُضيف العنصر ورُبط بالمبدّل، `NODE_CHECK=PASS` |
| `D2` | `mediaItemsCache` كان غير معرّف في عرض «الإحصائيات» | `ReferenceError` سابقاً | مُصلَح — عُرِّف وتُحدَّث قيمته عند `media_items` |
| `D3` | `rbFmtBytes` كان غير معرّف في «جدول الملخصات» | `ReferenceError` سابقاً | مُصلَح — أُضيفت الدالة |
| `D4` | `ensureStreamModalsShell` كانت معرّفة مرتين | ازدواج تعريف بلا أثر وظيفي مباشر | مُصلَح — حُذف التعريف المكرر |
| `D5` | فجوة إصدار التسليم بين الجذر `1.0.0` والمخرج الطازج `1.5.1` | خطر اختبار نسخة قديمة | مُحسوم — الجذر الآن `1.5.1` (`versionCode 11`)، والقديم `1.0.0` في `legacy` |
| `D6` | سكربتات `init.ps1` و`init.sh` و`build-and-test.ps1` كانت تشير إلى مسارات وملفات غير موجودة | فشل الأتمتة أو طباعة مسارات خاطئة | مُصلَح — أُعيدت كتابة السكربتات وتحققت في جولة `D6` |
| `D7` | `opencode.jsonc` كان يوجّه `MCP` إلى مجلد مشروع آخر | احتمال قراءة أو كتابة ملفات المشروع الخطأ | مُصلَح — وُجّه إلى جذر المشروع بموجب القسم `2.18` |

## 📞 استكشاف الأخطاء

| المشكلة | الحل |
|---|---|
| فشل اتصال `WebRTC` | تأكد من تشغيل خادم `TURN` وإضافة إعداداته في التطبيق |
| فقدان حزم البيانات | تم تفعيل `ACK/NACK` تلقائيًا في الإصدار الجديد |
| انقطاع الخدمة | تمت إضافة `ICE Restart` تلقائي (حتى 3 محاولات) |
| الأذونات غير ممنوحة | امنح جميع الأذونات قبل بدء خدمة المضيف |

## 📚 الوثائق والحوكمة

- `دليل الفيصلي الذكي/agents.md`: الدستور الحاكم.
- `دليل الفيصلي الذكي/skill.md`: المرآة الحاكمة.
- `دليل الفيصلي الذكي/project_state.md`: السجل التراكمي.
- `دليل الفيصلي الذكي/execution_log.md`: سجل التنفيذ.
- `دليل الفيصلي الذكي/RBstudioPRO_backup_description.md`: وصف النسخ الاحتياطية.

الوثائق التاريخية القديمة لم تعد داخل جذر المشروع؛ حُفظت في أرشيف التنظيف الموثّق داخل مجلد النسخ الاحتياطي، ويمكن استرجاعها عند الحاجة.

---

**إصدار التطبيق:** `1.5.1` (`versionCode 11`)
**إصدار الخادم:** `2.0.0`
**تاريخ التحديث:** 8 نوفمبر 2026
**التوافق:** `Android 8.0+` (`API 26+`)
