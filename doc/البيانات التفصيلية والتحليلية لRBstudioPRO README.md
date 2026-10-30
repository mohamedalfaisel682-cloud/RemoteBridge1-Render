# 📘 البيانات التفصيلية والتحليلية لـ RBstudioPRO — ملف المرجع الموحد

<div align="center">

### النسخة الشاملة — تجميع كل ملفات الوصف والتحليل والشرح في مرجع واحد للقراءة فقط

</div>

---

| الحقل | القيمة |
|---|---|
| الإصدار | 1.0 (تجميع الجولة الشاملة) |
| التاريخ | 2026-10-26 |
| السطر التاريخي | بنيت على جولة «الأوامر والتحكم البعيد» + كل الجولات السابقة |
| مكان النسخة | `opencodeGLM5.3FLASHA\cosmic-harbor\docs\` (ملف للقراءة فقط read-only) |
| طبيعة الملف | **للقراءة فقط** (IsReadOnly) — مرجع مؤرشف لا يُعدَّل |

---

## 📚 الفهرس — أبواب المرجع

| الباب | المحتوى | الأصل |
|---|---|---|
| **الباب 1** | الوصف العام للمشروع — README | `README.md` |
| **الباب 2** | الخلاصة الشاملة للإعدادات | `PROJECT_CONFIG_SUMMARY.md` |
| **الباب 3** | الدليل المرجعي الهندسي الشامل | `app\applet\REMOTEBRIDGE_MASTER_GUIDE.md` |
| **الباب 4** | التقرير الشامل للنظام (16 فصلاً) | `COMPLETE_SYSTEM_REPORT.md` |
| **الباب 5** | خريطة نظام المعلومات — كنز التقنيات والحدود (15 فصلاً) | `SYSTEM_INFO_MAP.md` |
| **الباب 6** | تحليل المشروع وتقرير التغييرات | `ANALYSIS_AND_CHANGES.md` |
| **الباب 7** | التشريح الكامل للتطبيق وآليات الاتصال (ملف الجولة) | `التشريح الكامل...md` |
| **الباب 8** | التحليل العميق والمقترحات القوية + النمزوج الحيادي (ملف الجولة) | `التحليل العميق...md` |
| **الباب 9** | فروقات هذه المحادثة عن المحادثة الرئيسية (ملف الجولة) | `فروقات...md` |

> **كيف تقرأ هذا الملف:** الأبواب 1–6 هي أرشيف التقارير المبنية سابقاً كما هي (لم تُحرف)،
> والأبواب 7–9 هي ملفات جولة الأوامر الحالية. كل باب يبدأ بعلامة **≪ بداية باب ≫** —
> للتصفح السريع استخدم بحث «بداية باب».


---

# الباب 1 — الوصف العام للمشروع (README)

**≪ بداية باب ≫**  (نص الأصل كما هو — لم يُحرف)

# RemoteBridge Pro Studio

تطبيق أندرويد للتحكم عن بعد بين واجهة ويب (متحكم) وتطبيق أندرويد (مضيف) باستخدام WebRTC، خادم إشارات، وبروتوكول RPC.

## 📋 الميزات

- **واجهة ويب للتحكم** (Controller) - استكشاف الملفات، البث الحي، الرسائل، جهات الاتصال، المكالمات
- **تطبيق أندرويد مضيف** (Host) - خدمة أمامية تعمل في الخلفية مع صلاحيات الكاميرا، الميكروفون، والملفات
- **اتصال WebRTC P2P** مع دعم TURN servers للتغلب على NAT وجدران الحماية
- **خادم إشارات (Signaling Server)** بلغة Go مع مصادقة بالتوكين ومقاييس Prometheus
- **نقل بيانات موثوق** مع ACK/NACK و checksum (CRC32)
- **استعادة تلقائية للاتصال** مع ICE Restart وإعادة المحاولة

## 🗂️ هيكل المشروع

```
remotebridge-pro-studio/
├── web-dashboard/
│   └── index.html          # واجهة التحكم الويب
├── app/
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
├── main.go                        # خادم الإشارات (Go)
├── go.mod / go.sum                # تبعيات Go
├── docker-compose.turn.yml        # تشغيل خادم TURN
├── Dockerfile                     # صورة دوكير للخادم
├── .env.example                   # مثال لمتغيرات البيئة
├── .opencode/opencode.jsonc       # إعدادات OpenCode و MCP
├── init.sh                        # سكريبت تهيئة (Linux/macOS)
├── init.ps1                       # سكريبت تهيئة (Windows)
├── PROJECT_CONFIG_SUMMARY.md      # الخلاصة الشاملة بكل البيانات
└── README.md                      # هذا الملف
```

## 🚀 التشغيل السريع

### Linux / macOS
```bash
chmod +x init.sh
./init.sh
```

### Windows (PowerShell)
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
.\init.ps1
```

### التشغيل اليدوي
1. **تشغيل خادم TURN:**
   ```bash
   docker-compose -f docker-compose.turn.yml up -d
   ```

2. **تشغيل خادم الإشارات:**
   ```bash
   go run main.go
   ```

3. **بناء تطبيق الأندرويد:**
   ```bash
   ./gradlew assembleDebug
   ```

4. **تثبيت APK على الهاتف:**
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

5. **فتح واجهة الويب:**
   افتح `web-dashboard/index.html` في المتصفح

## 🔧 الإعدادات المهمة

### متغيرات البيئة (`.env`)
انسخ `.env.example` إلى `.env` وعدل القيم:
```env
TURN_SERVER_URL=turn:turn.remotebridge.app:3478?transport=udp
TURN_USERNAME=webrtcuser
TURN_PASSWORD=StrongTurnPassword2026!
SIGNALING_SERVER_URL=wss://remotebridge1.onrender.com/ws
```

### إعدادات TURN في التطبيق
في شاشة الإعدادات بالتطبيق، أضف:
- عنوان خادم TURN
- اسم المستخدم
- كلمة المرور

## 📊 مراقبة الصحة والمقاييس

- **فحص الصحة:** `http://localhost:10000/health`
- **مقاييس Prometheus:** `http://localhost:9090/metrics`

## 📞 استكشاف الأخطاء

| المشكلة | الحل |
|---------|------|
| فشل اتصال WebRTC | تأكد من تشغيل خادم TURN وإضافة إعداداته في التطبيق |
| فقدان حزم البيانات | تم تفعيل ACK/NACK تلقائياً في الإصدار الجديد |
| انقطاع الخدمة | تم إضافة ICE Restart تلقائي (حتى 3 محاولات) |
| الأذونات غير ممنوحة | منح جميع الأذونات قبل بدء خدمة المضيف |

## 📄 الملفات الهامة

- `PROJECT_CONFIG_SUMMARY.md` - الخلاصة الشاملة بكل المسارات، الروابط، كلمات المرور، والمتغيرات
- `.opencode/opencode.jsonc` - إعدادات OpenCode و MCP للوصول لملفات المشروع عبر الذكاء الاصطناعي

---

**الإصدار:** 2.0.0  
**تاريخ التحديث:** 22 أكتوبر 2026  
**التوافق:** Android 8.0+ (API 26+)


---

# الباب 2 — الخلاصة الشاملة للإعدادات

**≪ بداية باب ≫**  (نص الأصل كما هو — لم يُحرف)

# 📋 ملف الخلاصة الشامل - RemoteBridge Pro Studio

> **تاريخ الإنشاء:** 22 أكتوبر 2026  
> **حالة المشروع:** جاهز للتعديل والبناء عبر Android Studio  
> **الإصدار:** 2.0.0 (مع إصلاحات WebRTC و TURN و MCP)

---

## 🗂️ هيكل الملفات والمسارات

| المسار | الوصف |
|--------|-------|
| `F:\Ai\agent folders\Editing By opencode\remotebridge-pro-studio by opencode\` | جذر المشروع |
| `web-dashboard/index.html` | واجهة التحكم الويب (Controller Dashboard) |
| `main.go` | خادم الإشارات (Signaling Server) بلغة Go |
| `go.mod` / `go.sum` | تبعيات خادم الإشارات |
| `app/build.gradle.kts` | إعدادات بناء تطبيق الأندرويد |
| `app/src/main/AndroidManifest.xml` | ملف بيان التطبيق (الأذونات والخدمات) |
| `app/src/main/java/com/remotebridge/` | كود مصدر تطبيق الأندرويد |
| `.opencode/opencode.jsonc` | إعدادات OpenCode و MCP للمشروع |
| `docker-compose.turn.yml` | إعدادات تشغيل خادم TURN (coturn) |
| `Dockerfile` | صورة دوكير لخادم الإشارات |
| `.env.example` | مثال لمتغيرات البيئة |
| `.gitignore` | ملفات ومجلدات مستثناة من git |
| `PROJECT_CONFIG_SUMMARY.md` | هذا الملف (الخلاصة الشاملة) |

---

## 🌐 عناوين الخوادم والروابط (URLs)

| العنوان | الغرض | الحالة |
|---------|-------|--------|
| `wss://remotebridge1.onrender.com/ws` | خادم الإ Signaling الأساسي (Render) | افتراضي |
| `wss://my-ai-bridge-app.fly.dev/ws` | خادم الإشارات الاحتياطي (Fly.io) | احتياطي |
| `ws://localhost:10000/ws` | خادم الإشارات محلياً للتطوير | محلي |
| `ws://10.0.2.2:10000/ws` | خادم الإشارات لمحاكي أندرويد | محاكي |
| `stun:stun.l.google.com:19302` | خادم STUN أساسي (Google) | نشط |
| `stun:stun1.l.google.com:19302` | خادم STUN احتياطي 1 | نشط |
| `stun:stun2.l.google.com:19302` | خادم STUN احتياطي 2 | نشط |
| `stun:stun3.l.google.com:19302` | خادم STUN احتياطي 3 | نشط |
| `stun:stun4.l.google.com:19302` | خادم STUN احتياطي 4 | نشط |
| `turn:turn.remotebridge.app:3478?transport=udp` | خادم TURN أساسي | يحتاج نشر |
| `turn:turn.remotebridge.app:3478?transport=tcp` | خادم TURN عبر TCP | يحتاج نشر |
| `turns:turn.remotebridge.app:5349?transport=tcp` | خادم TURN عبر TLS | يحتاج نشر |
| `http://<server-ip>:9090/metrics` | مقاييس Prometheus لخادم الإشارات | جديد |
| `http://<server-ip>:10000/health` | فحص صحة خادم الإشارات | جديد |

---

## 🔑 كلمات المرور والقيم السرية (SECRETS)

> ⚠️ **تنبيه:** هذه القيم افتراضية للتطوير. يجب تغييرها قبل النشر في الإنتاج.

| المتغير | القيمة الافتراضية | مكان الاستخدام | 중요性 |
|----------|------------------|--------------|---------|
| `TURN_PASSWORD` | `StrongTurnPassword2026!` | خادم TURN (coturn) و PeerConnectionManager.kt | **عالٍ** |
| `TURN_USERNAME` | `webrtcuser` | خادم TURN و PeerConnectionManager.kt | **عالٍ** |
| `STORE_PASSWORD` | `android` | ملف署명 التصحيح (debug keystore) | متوسط |
| `KEY_PASSWORD` | `android` | مفتاح التصحيح | متوسط |
| `GITHUB_TOKEN` | `your_github_token_here` | MCP Server - GitHub | متوسط |
| `NOTION_TOKEN` | `your_notion_token_here` | MCP Server - Notion | متوسط |

### أين توجد هذه القيم في الكود:

1. **في `PeerConnectionManager.kt`** (السطر 63-74):
   ```kotlin
   .setUsername("webrtcuser")
   .setPassword("your_turn_password")  // ← استبدل StrongTurnPassword2026!
   ```

2. **في `web-dashboard/index.html`** (DEFAULT_CONFIG):
   ```javascript
   turnUsername: "webrtcuser",
   turnPassword: "your_turn_password"  // ← استبدل StrongTurnPassword2026!
   ```

3. **في `docker-compose.turn.yml`**:
   ```yaml
   - TURN_PASSWORD=StrongTurnPassword2026!
   - TURN_USERNAME=webrtcuser
   ```

4. **في `.env.example`**:
   ```
   TURN_PASSWORD=StrongTurnPassword2026!
   TURN_USERNAME=webrtcuser
   ```

---

## 🔧 إعدادات WebRTC و ICE Servers

### ICE Servers المحدثة (في PeerConnectionManager.kt و index.html):

```kotlin
// STUN Servers (5 خوادم من Google)
stun:stun.l.google.com:19302
stun:stun1.l.google.com:19302
stun:stun2.l.google.com:19302
stun:stun3.l.google.com:19302
stun:stun4.l.google.com:19302

// TURN Servers (3 خوادم - UDP, TCP, TLS)
turn:turn.remotebridge.app:3478?transport=udp
turn:turn.remotebridge.app:3478?transport=tcp
turns:turn.remotebridge.app:5349?transport=tcp
```

### إعدادات WebRTC المضافة:
- `iceTransportPolicy: "all"` - يسمح بالاتصال عبر TURN relay
- `bundlePolicy: "max-bundle"` - تجميع الوسائط في قناة واحدة
- `sdpSemantics: "unified-plan"` - خطة SDP موحدة
- `triggerIceRestart()` - إعادة تشغيل ICE تلقائي عند الفشل
- الحد الأقصى لمحاولات ICE restart: **3 محاولات**
- مهلة انتظار اتصال ICE: **15 ثانية**

---

## 📱 إعدادات تطبيق الأندرويد (Android)

| الإعداد | القيمة |
|--------|-------|
| `compileSdk` | 36 |
| `minSdk` | 26 |
| `targetSdk` | 36 |
| `versionCode` | 1 |
| `versionName` | 1.0.0 |
| `namespace` | `com.rbstudio` |
| `applicationId` | `com.rbstudio` |

### الأذونات المطلوبة (AndroidManifest.xml):

**أذونات حرجة (يجب منحها قبل بدء الخدمة):**
- `CAMERA` - الوصول للكاميرا
- `RECORD_AUDIO` - الوصول للميكروفون
- `READ_MEDIA_IMAGES` - قراءة الصور
- `READ_MEDIA_VIDEO` - قراءة الفيديو
- `READ_MEDIA_AUDIO` - قراءة الصوت
- `INTERNET` - الاتصال بالشبكة
- `ACCESS_NETWORK_STATE` - حالة الشبكة

**أذونات सेवा أمامية (Foreground Service):**
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `FOREGROUND_SERVICE_CAMERA`
- `FOREGROUND_SERVICE_MICROPHONE`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`

**أذونات إضافية:**
- `READ_SMS`, `RECEIVE_SMS` - الرسائل النصية
- `READ_CONTACTS` - جهات الاتصال
- `READ_CALL_LOG` - سجل المكالمات
- `POST_NOTIFICATIONS` - الإشعارات
- `MANAGE_EXTERNAL_STORAGE` - الوصول الكامل للملفات (Android 11+)
- `BIND_NOTIFICATION_LISTENER_SERVICE` - مستمع الإشعارات

---

## 🔌 إعدادات خادم الإشارات (main.go)

### المنافذ المستخدمة:
| المنفذ | البروتوكول | الغرض |
|--------|-----------|-------|
| 10000 | TCP/WebSocket | خادم الإشارات الأساسي |
| 9090 | HTTP | مقاييس Prometheus |
| 3478 | TCP/UDP | خادم TURN (coturn) |
| 5349 | TCP/TLS | خادم TURN آمن |
| 49152-65535 | UDP | نطاق منافذ媒体 TURN |

### مقاييس Prometheus المضافة:
| المقياس | النوع | الوصف |
|--------|-------|-------|
| `signaling_ws_connections_total` | Gauge | عدد اتصالات WebSocket الحالية |
| `signaling_messages_relayed_total` | Counter | عدد الرسائل المنقولة حسب النوع والحالة |
| `signaling_ice_candidates_relayed_total` | Counter | عدد مرشحات ICE المنقولة |
| `signaling_handshake_duration_seconds` | Histogram | زمن المصافحة من Offer إلى Answer |
| `signaling_rpc_requests_total` | Counter | عدد طلبات RPC حسب الأمر والحالة |

### نقاط نهاية الخادم:
| النقطة | الطريقة | الوصف |
|--------|--------|-------|
| `/` | GET | معلومات الخدمة |
| `/health` | GET | فحص صحة الخادم (JSON) |
| `/metrics` | GET | مقاييس Prometheus |
| `/ws` | WebSocket | إشارات WebRTC و RPC |

---

## 📦 إعدادات MCP (OpenCode)

### ملف الإعداد: `.opencode/opencode.jsonc`

| الخادم | النوع | الأمر | الغرض |
|--------|-------|-------|-------|
| `project-files` | STDIO | `npx -y @modelcontextprotocol/server-filesystem .` | الوصول لملفات المشروع |
| `git` | STDIO | `npx -y @modelcontextprotocol/server-git --repository .` | عمليات Git |
| `code-search` | STDIO | `npx -y @modelcontextprotocol/server-grep` | البحث في الكود |
| `android-sdk` | STDIO | `npx -y @modelcontextprotocol/server-android` | أدوات أندرويد SDK |
| `postgres` | STDIO | `npx -y @modelcontextprotocol/server-postgres` | قاعدة بيانات (اختياري) |
| `chrome` | STDIO | `npx -y @modelcontextprotocol/server-chrome` | اختبار واجهة الويب |

### متغيرات البيئة المطلوبة لـ MCP:
```bash
ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
DATABASE_URL=postgresql://user:pass@localhost:5432/db
GITHUB_TOKEN=your_github_token
NOTION_TOKEN=your_notion_token
```

---

## 🚀 خطوات البناء والنشر (Deployment)

### 1. تشغيل خادم TURN (coturn):
```bash
# في مجلد المشروع
docker-compose -f docker-compose.turn.yml up -d

# أو تشغيل coturn فقط
docker run -d --name coturn --network host \
  -e TURN_SERVER_NAME=turn.remotebridge.app \
  -e TURN_REALM=remotebridge.app \
  -e TURN_USERNAME=webrtcuser \
  -e TURN_PASSWORD=StrongTurnPassword2026! \
  -e TURN_MIN_PORT=49152 \
  -e TURN_MAX_PORT=65535 \
  coturn/coturn:latest
```

### 2. تشغيل خادم الإشارات:
```bash
# محلياً
go run main.go

# أو باستخدام دوكير
docker build -t remotebridge-signaling .
docker run -d -p 10000:10000 -p 9090:9090 remotebridge-signaling
```

### 3. بناء تطبيق الأندرويد (Android Studio):
```bash
# فتح المشروع في Android Studio
# ثم:
./gradlew assembleDebug      # إصدار تصحيح
./gradlew assembleRelease    # إصدار إنتاج (يحتاج توقيع)

# ملف الـ APK الناتج:
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

### 4. منح الأذونات على الهاتف:
بعد تثبيت التطبيق، افتحه ومنح جميع الأذونات التالية:
1. الكاميرا والميكروفون
2. الوصول للصور والفيديو والصوت
3. الوصول الكامل للملفات (Manage all files)
4. الإشعارات
5. تجاهل تحسين البطارية (Ignore battery optimizations)
6. ثم انقر على "بدء المضيف" (Start Host)

---

## 📊 مؤشرات النجاح المتوقعة (بعد الإصلاحات)

| المقياس | قبل الإصلاح | بعد الإصلاح (الهدف) |
|--------|------------|---------------------|
| معدل نجاح اتصال WebRTC P2P | ~60-70% | > 95% |
| زمن إنشاء الاتصال (Handshake) | غير محدد | < 3 ثوانٍ |
| معدل فقدان حزم البيانات | عالي | 0% (مع ACK/Retransmission) |
| استعادة الاتصال بعد الانقطاع | يدوي | < 10 ثوانٍ تلقائياً |
| استهلاك البطارية (خدمة خلفية) | غير محدد | < 2% / ساعة |
| زمن استجابة RPC | بطيء | < 500ms (P2P) |
| توافر خادم الإشارات | نقطة فشل واحدة | 99.9% (مع مراقبة) |

---

## ⚠️ ملاحظات هامة للتطوير المستقبلي

1. **كلمات المرور:** استبدل جميع القيم `"your_turn_password"` و `"StrongTurnPassword2026!"` بقيم آمنة حقيقية قبل النشر.

2. **خادم TURN:** الاتصال خلف NAT المتماثل (Symmetric NAT) يتطلب خادم TURN عام. بدون TURN، سي_fail حوالي 30% من الاتصالات.

3. **مفتاح التوقيع:** للتوزيع عبر Google Play، تحتاج إلى ملف keystore للتوقيع (لا تستخدم debug keystore في الإنتاج).

4. **إصدار الأندرويد:** يدعم التطبيق Android 8.0 (API 26) فما فوق. بعض الميزات تتطلب Android 11+ (API 30+).

5. **مـC P:** ملف `.opencode/opencode.jsonc` يتيح للمساعد الوصول لملفات المشروع، Git، والبحث في الكود عبر خوادم MCP.

---

## 📞 دعم وصيانة

لأي مشكلة في الاتصال أو البث المباشر، راجع:
1. لوحة التشخيص في واجهة الويب (زر "فحص الاتصال")
2. سجلات خادم الإشارات (main.go)
3. سجلات تطبيق الأندرويد (LogCat في Android Studio)
4. مقاييس Prometheus على `:9090/metrics`


---

# الباب 3 — الدليل المرجعي الهندسي الشامل

**≪ بداية باب ≫**  (نص الأصل كما هو — لم يُحرف)

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






---

# الباب 4 — التقرير الشامل للنظام

**≪ بداية باب ≫**  (نص الأصل كما هو — لم يُحرف)

# التقرير الشامل — نظام RemoteBridge

> مرجع متكامل للنظام: الميزات والخدمات والآليات وكيف تعمل القطع معًا، وكل المعلومات التي يحتاجها المطور للتوسعة، وخطوات التشغيل اليدوية، والمشاكل وحلولها، وإجابات الأسئلة العميقة، ثم الاقتراحات التطويرية في الخاتمة.
> آخر تحديث: بعد إعادة هيكلة بطاقة لوحة التحكم (نصف الموقع + عدّاد «منذ آخر رؤية»، ونقل البطارية إلى بطاقة النظام).
> حالة البناء المؤكدة: `assembleDebug` ناجح في 1د57ث، ونسخة Release الموقّعة ناجحة في 8د5ث (EXIT 0).

---

## 0) المعلومات السريعة

| # | العنصر | القيمة |
|---|---|---|
| 1 | خادم الإشارات الأساسي (Render) | `wss://remotebridge1.onrender.com/ws` |
| 2 | فحص الصحة (وإيقاظ الخادم النائم) | `https://remotebridge1.onrender.com/health` |
| 3 | مقاييس Prometheus | `https://remotebridge1.onrender.com/metrics` |
| 4 | الخادم البديل (بروفايل Fly) | `wss://my-ai-bridge-app.fly.dev/ws` |
| 5 | خادم STUN الافتراضي | `stun:stun.l.google.com:19302` |
| 6 | خادم محلي للحاسوب | `ws://<pc-ip>:10000/ws` — وفي المحاكي: `ws://10.0.2.2:10000/ws` |
| 7 | بروفايلات الاتصال | `HYBRID` الافتراضي، `RENDER_ONLY`، `FLY_ONLY`، `LOCAL_LAN` |
| 8 | كود USSD النظامي (يعمل فوراً) | `*#*#82473#*#*` |
| 9 | كود USSD الحرفي | `*82473#` — يتطلب تفعيل خدمة المراقبة من الإعدادات |
| 10 | استرجاع إضافي | زر «إظهار الآن» في إعدادات المضيف + مفتاح «إخفاء الأيقونة عند تشغيل المضيف» |
| 11 | مفتاح التوقيع | `my-upload-key.jks` في جذر المشروع؛ كلمة السر الافتراضية في سكربت البناء: `RemoteBridge123`؛ يُنشأ تلقائياً إن لم يوجد |
| 12 | أحجام APK الموقَّعة النهائية | armeabi-v7a: 8.23MB، arm64-v8a: 12.97MB، x86: 13.73MB، x86_64: 14.19MB، universal: 43.36MB |
| 13 | أوامر البناء | `.\gradlew.bat assembleDebug` للتحقق السريع، و `.\build-small-apk.ps1` للنسخة الموقّعة، و `go build .` للخادم (وللتشغيل المحلي `go run .`) |
| 14 | هوية التطبيق | `com.rbstudio` — والشيفرات الداخلية تحت الحزمة `com.remotebridge.*` |
| 15 | مستندات مرجعية أخرى | `ANALYSIS_AND_CHANGES.md` (سجل التغييرات التفصيلي) |

**شرح كودي الاسترجاع:**

- الصيغة النظامية `*#*#82473#*#*` يرسلها نظام الهاتف كحدث `Telephony.SECRET_CODE` بعنوان `android_secret_code://82473`، فتلتقطها `SecretCodeReceiver` المسجلة في `AndroidManifest.xml` مباشرة، وتفتح التطبيق وتظهر أيقونته إن كانت مخفية. هذه الصيغة لا تحتاج أي إعداد.
- الصيغة الحرفية `*82473#` تمر كطلب USSD عادي ولا تصل إلى حدث `SECRET_CODE`. لذلك يُفعل من الإعدادات خدمة المراقبة `DialCodeAccessibilityService` التي تراقب حقل كتابة الاتصال فقط وعند تطابق النص تفتح التطبيق وتظهر الأيقونة. الخدمة لا تقرأ أي شاشة أخرى.
- ولأي سبب: زر «إظهار الآن» في شاشة إعدادات المضيف يظهر الأيقونة يدوياً.

---

## 1) نظرة عامة: النظام من أربعة عناصر

```
+-----------------------------+                  +-----------------------------+
|  جهاز المضيف (HOST)         |                  |  جهاز المتحكم (CONTROLLER)  |
|  HostForegroundService      | <== WebRTC ==>   |  تطبيق أندرويد أو متصفح     |
|  صامت، بلا إشعارات          |  DataChannel     |  لوحة تحكم وأقسام بيانات    |
+-----------------------------+     (P2P)       +-----------------------------+
                 |                                        |
      تسجيل وإشارات تفاوض (register / offer / answer / ICE)
                 |                                        |
              +----------------------------------------------+
              |      خادم إشارات Go على Render (main.go)        |
              |    يمرر الإشارات ويتذكر آخر ظهور لكل جهاز      |
              +----------------------------------------------+
```

- قناة البيانات الحية هي **WebRTC DataChannel** مباشرة بين الجهازين، وهي مشفرة DTLS من مستوى WebRTC تلقائياً. الخادم يرى إشارات الاتصال فقط (SDP وICE والتسجيل) ولا يرى محتوى البيانات.
- الخادم بوابة إشارات خفيفة: يحوّل الرسائل بين الطرفين بمفتاح `to`، ويتذكر `lastSeen` لكل جهاز، ويجيب على `get_last_seen`.
- تطبيق واحد بأدوار ثلاثة: وجهة **المضيف** (جهاز يُراقَب ويُدار عن بعد) ووجهة **المتحكم** (جهاز يتحكم) ولوحة **الويب** المدمجة. الثلاثة في الحزمة نفسها.
- تعمل لوحة الويب أيضاً من متصفح خارجي عبر الملف نفسه، مع جسر JS محلي داخل WebView للتطبيق.

---

## 2) خادم الإشارات بالتفصيل (main.go على Render)

### 2.1 نقاط النهاية

| النقطة | الوظيفة |
|---|---|
| `/ws` | نقطة WebSocket للإشارات، والجهازان يتصلان هنا |
| `/health` | فحص الصحة، وهي أيضاً وسيلة سريعة لإيقاظ الخادم المجاني |
| `/metrics` | مقاييس Prometheus: عدّادات الرسائل والتسجيل والاستعلام |
| `/` | صفحة نصية قصيرة تعرض قائمة النقاط |

### 2.2 رسائل WebSocket (كلها JSON بمفتاح النوع `type`)

| الرسالة | الاتجاه | الوظيفة |
|---|---|---|
| `register` | جهاز نحو الخادم | تسجيل `deviceId` و `role` (host أو controller) و `deviceName` و `token` اختياري |
| `registered` | الخادم نحو الجهاز | تأكيد التسجيل مع إصدار `token` جديد يحفظه التطبيق في الذاكرة طوال الجلسة |
| `hosts` | الخادم نحو المتحكم | لقطة قائمة المضيفين مع `lastSeenMs` لكل واحد |
| `host_online` و `host_offline` | الخادم نحو المتحكم | إعلان دخول أو خروج مضيف لحظياً |
| `offer` / `answer` / `ice` / `reject` | تمر بين الطرفين بمفتاح `to` | تفاوض WebRTC: عرض وقبول ومرشحات ورفض |
| `rpc_request` / `rpc_response` | متحكم ضد مضيف عبر القناة المباشرة | بروتوكول الأوامر والبيانات |
| `ping` / `pong` | جهاز ضد الخادم | نبض حيوية وقياس زمن الاستجابة |
| `get_last_seen` | متحكم نحو الخادم | استعلام آخر ظهور لكل الأجهزة |
| `last_seen` | الخادم نحو المتحكم | رد الاستعلام بجدول deviceId مع توقيت آخر ظهور لكل جهاز |
| `settings_sync` | تمر بين جهازين | مزامنة الإعدادات السريعة: الأحدث زمنياً يفوز، و `ts=0` طلب لقطة |
| `error` | الخادم نحو الجهاز | خطأ مصادقة أو تعذر توجيه |

### 2.3 المصادقة بالتوكن

- عند تسجيل جهاز بمعرف جديد، يولد الخادم توكن ويخزنه في `hub.clientTokens[deviceId]` ويرسله في `registered`.
- التطبيق يحفظ التوكن في الذاكرة داخل `SignalingClient` ويرسله مع كل `register` خاص به.
- إذا وصل `register` لمعرف موجود وتوكن غير مطابق، رفضه الخادم برسالة `error` ذات نص `Invalid authentication token`.
- الذاكرة متطايرة: إعادة نشر الخادم تفرغ التوكنات، فالتسجيل بعدها يصدر توكنًا جديدًا بلا رفض.

### 2.4 ذاكرة آخر ظهور

- خريطة `hub.lastSeen[deviceId]` تُحدَّث عند كل تسجيل وعند الانقطاع، وتضم في لقطة `hosts` وفي رد `get_last_seen`.
- هي أيضاً في الذاكرة فقط: إعادة النشر تمسحها، فتظهر الدوائر برتقالية أو حمراء حتى أول ظهور جديد للمضيف.

### 2.5 النشر اليدوي على Render (بعد كل تعديل في `main.go`)

```
git add main.go
git commit -m "وصف التعديل"
git push origin main
```

ثم راقب سجل Deployment في لوحة Render حتى يعود Live. تذكيرات المستوى المجاني: بداية باردة من 30 إلى 60 ثانية لأول طلب، وسبات بعد نحو 15 دقيقة خمول يقطع اتصالات WebSocket المفتوحة.

---

## 3) بروفايلات الاتصال — أربع طرق وآلية كل واحدة

البروفايلات لا تغيّر البروتوكول، بل تضبط **الخادم المختار** بلمسة واحدة من بطاقة «بروفايلات وأنماط نقل البيانات والاتصال» في شاشة الإعدادات. قيمة STUN تبقى حقلًا مستقلاً في الإعدادات ويستخدم في ICE عند مسار الإنترنت.

| # | البروفايل | ما يفعله | متى تستخدمه |
|---|---|---|---|
| 1 | `HYBRID` (الافتراضي) | يضبط الرابط إلى خادم Render تلقائياً | الاستخدام العام: الإشارات عبر Render والبث والبيانات عبر WebRTC مباشرة بين الجهازين |
| 2 | `RENDER_ONLY` | يضبط الرابط إلى خادم Render أيضاً | التزام بكل شيء يمر عبر الخادم السحابي |
| 3 | `FLY_ONLY` | يضبط الرابط إلى خادم Fly.io البديل | بديل عند تعطل الرابط الأساسي أو الحاجة إلى خادم ثانٍ |
| 4 | `LOCAL_LAN` | يضبط الرابط إلى العنوان المحلي | شبكة محلية بلا إنترنت وخصوصية أعلى |

### آلية كل طريقة اتصال

**أ) عبر Render (السحابي الافتراضي):**

1. كل جهاز يفتح WebSocket على `/ws` ويرسل `register` بدوره.
2. المتحكم يستقبل `hosts` ثم يتبعها `host_online` و `host_offline` لحظياً.
3. عند اختيار مضيف يرسل المتحكم `offer`، ويمرر الخادم إلى `to = ذلك المضيف`، وتعود `answer`، ويتبادلان `ice` مع مرشحات STUN من الإعدادات.
4. تنشأ قناة DataChannel المباشرة، ومنذها تمر `rpc_request` و `rpc_response` بدون دور للخادم.

**ب) عبر Fly.io (البديل):** نفس الآلية بالضبط لكن على الرابط `wss://my-ai-bridge-app.fly.dev/ws` المحدد في الإعدادات.

**ج) الاتصال المحلي LAN:** شغّل على الحاسوب `go run .` فيستمع على المنفذ 10000، ثم اختر `LOCAL_LAN` أو أدخل `ws://<pc-ip>:10000/ws` يدوياً. يمسح `LocalDiscoveryManager` الشبكة في نطاق `10.0.x.x` على المنفذ 10000 ويقترح العنوان عند وجوده. الطريقة بلا إنترنت وخصوصيتها أعلى.

**د) المحاكي أثناء التطوير:** الرابط `ws://10.0.2.2:10000/ws`، إذ `10.0.2.2` هو الحاسوب من داخل المحاكي.

---

## 4) جهاز المضيف (HOST) بالتفصيل

### 4.1 البداية والإخفاء والاسترجاع

1. زر **«بدء المضيف»** يشغّل `HostForegroundService` كخدمة أمامية صامتة.
2. بعد نجاح التشغيل يخفي `ComponentStateManager` أيقونة التطبيق من قائمة التطبيقات؛ ويبقى العمل في الخلفية.
3. الاسترجاع من ثلاث طرق: كود `*#*#82473#*#*` فوراً، أو كود `*82473#` بعد تفعيل خدمة المراقبة، أو الزر «إظهار الآن» في الإعدادات.

### 4.2 الوقاية من الإنهاء التعسفي

| المُركّب | الوظيفة |
|---|---|
| القناة الصامتة `bridge_host_channel_v2` | أهمية `IMPORTANCE_NONE`؛ لا صوت ولا هزّة ولا بطاقة ظاهرة |
| إشعار الخدمة الأمامية غير المرئي | شرط نظامي للخدمة الأمامية، لكنه بلا نص أو عنوان أو توقيت |
| `BridgeNotificationListenerService` | يلغي فوراً أي إشعار متبقى للتطبيق حين تظهره أنظمة تتجاهل القناة الصامتة |
| `HostRestartScheduler` | عند قتل العملية: يعيد WorkManager الإحياء بعد مهلة مضمونة من الإعدادات (`restartDelayMinutes`)، مع مراقب دوري كل 15 دقيقة |
| `BootReceiver` | يعيد تشغيل الخدمة بعد إقلاع الجهاز |
| بطاقات البطارية | زر «طلب تجاهل تحسينات البطارية» مباشرة + فتح تفاصيل التطبيق لإلغاء القيود |

الحد النظامي المتبقي الوحيد: المؤشر الصغير «يعمل في الخلفية» على شريط الحالة في أندرويد 13+، وهو عنصر نظامي لا يمكن لأي تطبيق إزالته.

### 4.3 مركز الصلاحيات (ست بطاقات)

1. **الرسائل والمكالمات وجهات الاتصال**: قراءة البيانات للرد على أوامر الرسائل والسجلات.
2. **الكاميرا والميكروفون**: بث الصورة والصوت.
3. **الموقع وتتبع المسار**: تشغيل `LocationTracker`، والمنحة تبدأ التتبع فوراً دون إعادة تشغيل الخدمة.
4. **تجاهل تحسينات البطارية**: يمنع القتل على الأجهزة الصارمة.
5. **كافة الملفات `MANAGE_EXTERNAL_STORAGE`**: استعراض وتحميل الملفات بأي ملف موجود على الأذونات.
6. **خدمة الإشعارات**: أرشفة اللقطات (`notifications_list`) + الإلغاء الصامت الاحتياطي.

### 4.4 متتبع الموقع LocationTracker

- يجمع الإصلاح من GPS والشبكة بعد منح الإذن الدقيق.
- المسار في الذاكرة لحد 250 نقطة.
- يحفظ نسخة مكتوبة كل 30 ثانية تقريباً في `cacheDir/rb_location_trail.json`، وصالحة 24 ساعة.
- يرد على أمر `location_snapshot` بآخر إصلاح والمسار الكامل.
- لا يبدأ أي عمل موقعي قبل منح الإذن صراحة؛ فلا جمع صامت في الخلفية بلا إذن.

### 4.5 ضمان «صفر إشعارات»

لا يوجد أي استدعاء إشعار مرئي في الشفرة إطلاقاً. الالتزام من ثلاث طبقات: القناة الصامتة بأهمية صفر، ثم إشعار الخدمة الأمامية غير المرئي، ثم المستمع المنقّي يلغي أي عنصر متسرب. النتيجة عملياً: صفر صوت وهزّة وبطاقة ظاهرة.

---
## 5) جهاز المتحكم (CONTROLLER) بتفصيل دقيق

### 5.1 التسجيل والقائمة

- التطبيق يسجل نفسه بدور `controller` ثم يستقبل رسائل `hosts` و `host_online` و `host_offline` لحظياً.
- كل مضيف يظهر في القائمة مع دائرة حالة موحدة بدل أي نص حالة:
  - **خضراء**: متصل الآن وقناة WebRTC تعمل.
  - **برتقالية**: غير متصل مباشرة، لكن الخادم يعرف وقت آخر ظهور له (`lastSeenMs` متوفر).
  - **حمراء**: لا توجد أي بيانات آخر ظهور على الخادم.

### 5.2 نافذة الحالة من النقر على الدائرة

- النقرة على الدائرة تفتح نافذة تعرض الحالة الفنية الفورية، مع استعلام `get_last_seen` يُرسل فور الفتح وتظهر نتيجته على الشاشة.
- تعرض النافذة أيضاً قائمة ملفات الجلسات المحفوظة (`RB_Session_*.json`)، ويمكن فتح أي ملف منها وقراءة محتواه.

### 5.3 الحفظ التلقائي للجلسة SessionRecorder

- عند اكتمال كل اتصال WebRTC ناجح بين مضيف ومتحكم، يكتب `SessionRecorder` ملفاً باسم `RB_Session_<اسم>_<زمن>.json` في مجلد التحميلات المختار عبر SAF.
- إن لم يحدد المستخدم مجلداً بعد، يُحفظ الملف تلقائياً في مسار التطبيق الخاص.
- ملف الجلسة يجمع التوقيت والإشارات والحالة، وهو أرشيف جاهز للمراجعة لاحقاً.

### 5.4 لوحة التحكم — الشكل الجديد للبطاقة

- النصف الأول هو الموقع: الإحداثيات بخط كبير على سطرين (الطول ثم العرض بخمسة أرقام عشرية)، وتحتها سطر المصدر عن وسيلة الإصلاح، ثم زر «فتح الخريطة» للخريطة الحقيقية (§5.5).
- النصف الثاني هو «منذ آخر رؤية»: عدّاد كبير يتحرك كل ثانية عبر `produceState` المستقل عن حالات النصف، وتحته التوقيت المطلق بصيغة `yyyy/MM/dd HH:mm` بتوقيت الجهاز.
- سعة البطارية انتقلت من هذه البطاقة إلى بطاقة «نظام التشغيل والبطارية» (§5.6) ليتسع هذان النصفان ويظهر تفصيلهما بوضوح للعين.

### 5.5 الخريطة الحقيقية (WebView مع Leaflet)

- الملف `assets/map/map.html` يعمل بمحرك Leaflet الإصدار 1.9.4 محلياً داخل الأصول، بلا أي تحميل مكتبة أثناء التشغيل.
- طبقتان: **Esri World Imagery** قمر صناعي افتراضياً، وزر تبديل إلى **OpenStreetMap** للشوارع.
- يعروض أيضاً: خط السير أزرق إن توفر مسار التتبع، ونقطة البداية خضراء وأحدث موقع دائرة حمراء، مع تكبير تلقائي عبر `fitBounds`.
- الغطاء التبايني للخرائط يحتاج إنترنت فقط، والباقي كله محلي.

### 5.6 بطاقة «نظام التشغيل والبطارية» — المكان الجديد للبطارية

- كيف تفتح: نقرة بطاقة «معلومات الجهاز» (أيقونة `Info` في قائمة الأقسام) تفتح أو تغلق هذه البطاقة تحت بطاقة معلومات الجهاز العليا.
- ما تعرضه: أندرويد وإصداره مع رقم API، واسم الجهاز أي الصانع والموديل، والتخزين: المساحة الحرة من الإجمالي، وسطر سعة البطارية بنسبة مئوية.
- عندما يكون الشاحن متصلاً تظهر أيقونة `BatteryChargingFull` ونص «جارٍ الشحن» ملوناً؛ ومصدر الحالة `deviceInfo.isCharging` القاهر من أمر `device_info` بلا أي RPC جديد.

### 5.7 تحميل الملفات DownloadsSaver

- عند طلب ملف يرسل المتحكم `file_open`، فيتقسم الخادم (المضيف) الملف إلى قطع بمقدار 16KB عبر `FrameProtocol` على قناة DataChannel بصيغة Base64 لكل قطعة مع معرف إطار.
- يستقبل المتحكم القطع عبر `DownloadsSaver` ويجمعها ويحفظها في مجلد التحميلات المختار (SAF) أو في مسار التطبيق.
- إذا فشلت قطعة، تُعاد تلك القطعة وحدها فلا إعادة الملف كله.

---

## 6) لوحة الويب (Web Dashboard)

- الصفحة مدمجة داخل التطبيق في الملف `app/src/main/assets/dashboard/index.html`، وتعمل كمتحكم عبر بروتوكول WebSocket نفسه. تفتح من داخل WebView في التطبيق بكل ميزاتها، أو من متصفح خارجي.
- قدراتها بالترتيب:
  1. تسجيل دور `controller` ومسح دوري للأجهزة، مع بطاقات لكل جهاز تعرض البطارية ونظام التشغيل بعد نجاح `device_info`.
  2. عرض قوائم الرسائل وجهات الاتصال ومكالماته وإشعاراته وملفاته عبر RPC.
  3. تحميل ملفات حقيقي عبر جسر `RBNative` ووحدة `NativeFilesSaver` التي تستقبل القطع بنص `saveChunk` مع `messageId`.
  4. مزامنة الإعدادات السريعة عبر `settings_sync`: تسحب الإعدادات الحالية بـ `ts=0` أو تدفعها بنفس النظام، وتشمل رابط الخادم واسم الجهاز ومهلة الإحياء وإخفاء الأيقونة.
  5. نافذة تشخيصية عند فشل الاتصال تفحص: هل بدأ المشغّل على الجهاز الآخر، وتطابق رابط الخادم، والأذونات.
  6. قائمة الجلسات المحفوظة وقراءتها من الجسر عبر `listSavedSessions` و `readSavedSession`.
- **تجديد واجهة البث الحي (آخر جولة)**: أزيل تعريف ميت مكرر للدالة `handleRPCResponse` كان موجوداً مرتين في الملف (التعريف الثاني يحجب الأول تلقائياً)، فحُذفت 122 سطراً من الكود الميت مع خطر تعارض مستقبلي. وأعيد بناء بطاقة البث بتصميم احترافي: رأس أيقوني بشارة حالة نابضة، ومساحة عرض بحد متوهج وخط مسح خفيف، وشارة مصدر صادقة (CAM FRONT أو CAM BACK أو SCREEN أو AUDIO) بدل أرقام وهمية، وشبكة أزرار SVG بألوان حية: الشاشة والكاميرا والصوت والعدسة وكتم الميكروفون والإيقاف، فكل مصدر نشط يظهر بتوهج نابض، والإيقاف بلون روزي، والأزرار تتمدد قليلاً عند الضغط (`active:scale-95`).
- قاعدة هامة: المصدر المرجعي هو `web-dashboard/index.html` في جذر المشروع، وبعد أي تعديل عليه انسخه إلى `app/src/main/assets/dashboard/index.html` وإلا استمر التطبيق على النسخة القديمة.

---

## 7) جدول الأوامر الكامل (RPC على القناة المباشرة)

| الأمر | ما يفعله داخل المضيف | شكل الرد |
|---|---|---|
| `device_info` | يجمع بيانات الجهاز: الصانع والموديل وإصدار أندرويد ورقم API، والبطارية `level` و `isCharging`، والتخزين الحر والكامل | كائن DeviceInfo كامل |
| `file_list` | قائمة محتويات مسار مطلوب على المضيف (بحسب الأذونات الممنوحة) | قائمة الأسماء والأحجام والأنواع |
| `file_open` | يفتح الملف وينقله قطعاً بمقدار `FrameProtocol.MAX_PAYLOAD = 16 * 1024` بايت بصيغة Base64 مع معرف إطار | القطع تتجمع على `DownloadsSaver` في التطبيق أو `NativeFilesSaver` في الويب |
| `sms_list` | قراءة الرسائل (يتطلب الإذن) | قائمة مرسل ونص وزمن |
| `contacts_list` | قراءة جهات الاتصال (يتطلب الإذن) | قائمة أسماء وأرقام |
| `calls_list` | قراءة سجل المكالمات (يتطلب الإذن) | قائمة نوع ورقم وزمن |
| `notifications_list` | أرشيف الإشعارات الملتقطة بعد منح خدمة الإشعارات | قائمة تطبيق وعنوان ونص وزمن |
| `location_snapshot` | لقطة الموقع الحالي وكامل المسار من `LocationTracker` | خط الطول والعرض والمصدر مع نقاط المسار |
| `start_camera_stream` | بدء بث الكاميرا (يتطلب إذن الكاميرا) | إطارات الصورة عبر القناة المباشرة |
| `switch_camera` | بدّل بين الكاميرا الأمامية والخلفية خلال البث | تأكيد التبديل |
| `start_audio_stream` | بدء بث الميكروفون (يتطلب إذن الصوت) | إطارات صوتية |
| `start_screen_stream` | بث الشاشة عبر `MediaProjection` مع موافقة نظامية على المضيف في كل مرة | إطارات الشاشة |
| أوامر الإيقاف `stop...` | إيقاف أي بث سابق | إغلاق الإطار المرتبط |
| `sms_send` | إرسال رسالة نصية من شريحة المضيف عبر `SmsManager` (متعددة الأجزاء تلقائياً) | `sent` أو رسالة خطأ عن الإذن |
| `call_make` | إجراء مكالمة من المضيف (ACTION_CALL مع إذن CALL_PHONE وإلا ACTION_DIAL) | `dialed` |
| `screen_show` | إظهار نص عائم فوق شاشة المضيف (نافذة TYPE_APPLICATION_OVERLAY لمدة ثوانٍ محددة — يتطلب «عرض فوق التطبيقات») | `shown` |
| `contact_add` | إضافة اسم + رقم إلى دفتر الأسماء عبر `ContentProviderOperation` (إذن WRITE_CONTACTS) | `added` |
| `media_add` | إضافة وسائط (صورة/فيديو/صوت بترميز Base64 من المتحكم حتى 4MB) إلى مكتبة MediaStore على المضيف | `added, name, path, size` |
| `capture_photo` | لقطات Camera2 فردية أو متعددة (حد 120 لقطة) بفاصل 5/10/30 ث أو دقيقة أو 5/10/60 دقيقة، لكل عدسة مجلدها | `captured` + قائمة items |
| `capture_video` | مقطع فيديو Camera2 + MediaRecorder (حد 600 ثانية) في Movies/RemoteBridge/Front أو Back | `captured, name, path, size` |
| `record_audio` | تسجيل ميكروفون AAC بمدة محددة (حد ثوانٍ 3600) في Music/RemoteBridge | `recorded, name, path, size` |
| `screen_shot` | لقطة شاشة واحدة عبر إذن إسقاط الوسائط المخزّن (ImageReader + شاشة افتراضية) في Pictures/RemoteBridge/Screen | `captured, name, path, size` أو إرشاد منح الإذن |
| `screen_record` | تسجيل شاشة بمدة محددة عبر نفس إذن الإسقاط (MediaRecorder + شاشة افتراضية) في Movies/RemoteBridge/Screen | `captured, name, path, size` |
| `device_shutdown` | إطفاء الهاتف — عبر صلاحية الجذر `su -c reboot -p` فقط | `executed` + رسالة واضحة عن الحاجة للجذر |
| `device_reset` | تصفير المصنع — عبر الجذر `su -c recovery --wipe_data` فقط | `executed` + رسالة واضحة |
| `command_schedule` | جدولة أي أمر من الأوامر أعلاه بوقت مستقبلي: تُخزّن في `CommandSchedulerStore` (SharedPreferences JSON تصمد عبر إعادة التشغيل) | `scheduled, id, at` |
| `schedule_list` / `schedule_cancel` | عرض الأوامر المجدولة المتبقية أو إلغاء واحدة بالمعرّف | قائمة items / `cancelled` |

**محرك الجدولة**: دورة فحص كل 10 ثوانٍ داخل خدمة المضيف (`schedulerJob`) تستدعي `pollDue()` وتنفّذ المستحق عبر نفس `processRequest`، فلا حاجة لـ AlarmManager ولا لبدء خدمة من الخلفية؛ المصفوفة محفوظة في SharedPreferences فتصمد إعادة تشغيل الخدمة (تحتفظ بها watchdog).

**آلية التقطيع (`FrameProtocol`)**: كل رسالة تُقص إلى قطع 16KB كحد أقصى، وكل قطعة تنقل Base64 مع رقم `messageId`. عند فشل قطعة أو فقد، تُعاد تلك القطعة وحدها، ولا تُعاد الرسالة كاملة.

---

## 8) خطوات التشغيل من الصفر خطوة بخطوة

1. انشر آخر نسخة من `main.go` على Render كما في §2.5 إن كان هناك تعديل غير منشور.
2. ثبّت أحدث APK موقّع على الجهازين. الانسب: `arm64-v8a` لمعظم هواتف اليوم، و `universal` إذا لم تكن متأكداً من نوع المعالج.
3. على جهاز المضيف: افتح التطبيق ومنح الأذونات الست من مركز الصلاحيات (§4.3)، وأهمها الموقع الدقيق لخط السير والخريطة.
4. من الإعدادات على الجهازين: نفس رابط الخادم `wss://remotebridge1.onrender.com/ws`، واسم مميز لجهاز المضيف.
5. اضغط **«بدء المضيف»**؛ تنطلق الخدمة وتختفي أيقونة التطبيق تلقائياً.
6. على جهاز المتحكم: افتح تبويب المتحكم، وراقب دائرة حالة الجهاز، واضغط الاتصال.
7. عند نجاح الاتصال الأول يُكتب ملف الجلسة تلقائياً في مجلد التحميلات، ويظهر في نافذة الحالة لاحقاً.
8. جرّب البطاقات: نصفَي الموقع وآخر رؤية، وفتح بطاقة «نظام التشغيل والبطارية» من بطاقة «معلومات الجهاز»، والخريطة الحقيقية.
9. إذا كان الخادم في السبات، اطرق رابط `/health` في متصفح وانتظر 30 إلى 60 ثانية.

---

## 9) الخطوات اليدوية الواجبة عليك (لا تحدث تلقائياً)

1. **إعادة نشر `main.go` على Render** بعد أي تعديل الخادم، بأوامر `git add` ثم `git commit` ثم `git push` كاملة في §2.5، ومراقبة سجل Deploy حتى Live.
2. **منح الأذونات على جهاز المضيف بعد كل تثبيت APK جديد**. أعد فحصها كل تحديث. الأهم أولاً:
   - الموقع الدقيق (مع خيار «السماح دائماً» في بعض الأنظمة) لعمل تتبع المسار والموقع الحي.
   - كافة الملفات لقوائم الملفات والتحميل الكامل.
   - خدمة الإشعارات + تجاهل تحسينات البطارية، وخيار «غير محدود» داخل تفاصيل البطارية للتطبيق على أجهزة OEM الصارمة.
3. **أذونات الأوامر المتقدمة الجديدة (لكل أمر يريده من تبويب «الأوامر والتحكم البعيد»)**:
   - `SEND_SMS` (الرسائل) لإرسال الرسالة من تبويب الرسائل الجديد — منحها من إعداد التطبيق عند أول استخدام.
   - `CALL_PHONE` (المكالمات) لاتصال مباشر دون نافذة الطلب.
   - `WRITE_CONTACTS` (الأسماء) لإضافة الأسماء.
   - «عرض فوق التطبيقات» من إعدادات النظام لإظهار النص على الشاشة.
   - الجذر `root` فقط لإطفاء الهاتف وتصفير المصنع (بدون جذر يرد المضيف برسالة توضيح).
4. **نفس رابط الخادم على الجهازين** من الإعدادات، مع اسم مميز.
5. **اختيار مجلد التحميلات عبر SAF على جهاز المتحكم** ليحفظ الملفات وملفات الجلسات في مكان معروف.
6. **إيقاظ الخادم مجاني السبات** عبر `GET /health` قبل أول اتصال في اليوم.

---

## 10) المشاكل المتوقعة والحلول

| المشكلة | سببها غالباً | الحل الفوري |
|---|---|---|
| رسالة `Invalid authentication token` | جلسة قديمة تجاوزها الخادم بإصدار توكن جديد لنفس المعرف | أغلق التطبيق وافتحه من جديد فيصدر توكن جديد. وبعد إعادة نشر الخادم تُفرغ الذاكرة فيصبح أي توكن مقبولاً |
| الخادم لا يستجيب أو يقطع الاتصال فوراً | سبات Render المجاني بعد نحو 15 دقيقة خمول | افتح رابط `/health` في متصفح لإيقاظه، وانتظر 30 إلى 60 ثانية، ثم أعد الاتصال |
| قُتلت الخدمة على هاتف Xiaomi أو Huawei أو OnePlus | إدارة بطارية OEM صارمة | «تجاهل تحسينات البطارية» + «غير محدود» في تفاصيل التطبيق + الجدولة عبر `restartDelayMinutes` للإحياء التلقائي |
| الدائرة تحولت برتقالية أو حمراء بعد إعادة نشر الخادم | الذاكرة المتطايرة `lastSeen` أُفرغت | تعود الدوائر طبيعية مع أول ظهور جديد للمضيف، وجعلها دائمة هو الاقتراح رقم 2 |
| الخريطة فارغة من الغطاء المرئي | طبقات Esri أو OSM تحتاج إنترنت | افتح الاتصال بالشبكة أولاً، أما الشفرة المحلية والأوامر فتعمل بلا إنترنت |
| خط السير غير ظاهر | إذن الموقع غير ممنوح بعد، أو لا توجد حركة تُترجم مساراً | امنح الإذن فيبدأ التتبع فوراً دون إعادة تشغيل الخدمة، والخريطة تعرض المسار بينما يتراكم |
| ملفات الجلسة لا تظهر في القائمة | مجلد التحميلات غير محدد عبر SAF، أو لم يكتمل أي اتصال ناجح بعد | حدد المجلد من الإعدادات، والاتصال الناجح يكتب الملف تلقائياً |
| الأيقونة مخفية ولا تجد التطبيق | طبيعة الإخفاء بعد «بدء المضيف» | كود `*#*#82473#*#*` فوراً أو كود `*82473#` بتفعيل خدمة المراقبة أو زر «إظهار الآن» |
| مؤشر «يعمل في الخلفية» ظاهر على الشريط | عنصر نظامي ملزم على أندرويد 13+ | لا حلاً له من التطبيق، وهو أصلاً بلا إشعار أو بطاقة، فقط نقطة مؤشر صغيرة |
| الاتصال يقطع ويعيد نفسه بين حين وآخر | انتقال بين WiFi والبيانات، أو شبكة NAT شديدة الضيق | إعادة الاتصال تلقائية عبر المراقبة؛ وفي الحالات العنيدة جرّب شبكة أكثر استقراراً أو فعّل خادم TURN |
| offer وanswer ناجحان لكن القناة لا تفتح | NAT شديد الضيق يحجب الاتصال المباشر حيث يفشل STUN وحده | الحل الدائم إضافة خادم TURN على VPS (اقتراح 3)، أو استخدام الاتصال المحلي LAN للتجريب |

---

## 11) الإجابات العميقة — الإمكانات والحدود الحقيقية

**س1: هل يمكن التحكم بعدة أجهزة كمضيف، من نفس واجهة المتحكم، وبنفس الوقت، وبنفس رابط Render؟**

- الخادم: نعم بدون أي تعديل. التوجيه بمفتاح `deviceId` يعني كل مضيف يعالج إشاراته الخاصة، وعدة مضيفين ومتحكمين على نفس الخادم أمر طبيعي محدود فقط بذاكرة المستوى المجاني.
- التطبيق كمتحكم حالياً: جهاز واحد في كل لحظة. الوحدة الداخلية واحدة: `PeerConnectionManager` واحدة وحقل `connectedHost` واحد، فالانتقال بين الأجهزة تباعاً: إغلاق الحالي ثم فتح الآخر. أما القائمة فتعرض الجميع بالدوائر ويُراقب الجميع.
- لوحة الويب: نفس المنطق، هدف نشط واحد `activeTargetDeviceId`، والتبديل يعني إعادة فتح القناة للجهاز الجديد.
- الخلاصة: تعدد المضيفين في القائمة والمراقبة يعمل الآن، وتعددهم بالتحكم المتوازي الفعلي يحتاج إعادة هيكلة متعددة القنوات وهي الاقتراح رقم 4.

**س2: هل يمكن تنفيذ عدة متحكمات على نفس المضيف في نفس اللحظة؟**

- الخادم يسمح بذلك من حيث توجيه العروض إلى المضيف، لكن المضيف نفسه يدير اتصال نظير واحد حالياً أيضاً. العرض الثاني يُرفض أو يُهمل.
- التوسعة: مصفوفة اتصالات في المضيف، واحدة لكل متحكم متصل، وهي مربوطة بالاقتراح رقم 4 من الطرف الآخر.

**س3: ما الحد العملي لعدد الأجهزة على الخادم المجاني؟**

- الذاكرة المجانية 512MB وكل اتصال WebSocket خفيف؛ عملياً عشرات الأجهزة بلا مشاكل. راقب عدادات `/metrics` عند تجاوز الاستخدام، وإذا بلغت الحدّ ففكر في ترقية مدفوعة.

**س4: هل قناة البيانات مشفرة فعلاً؟**

- نعم على مستوى النقل: WebRTC يفرض DTLS تلقائياً على DataChannel، والخادم لا يرى المحتوى.
- لا على مستوى الحمولة: لا توجد طبقة تشفير إضافية بمفتاح مشترك بين الطرفين فقط، وهذا احتياط الأمن الموصى به في الاقتراح 1.

**س5: هل يمكن عكس الأدوار (المضيف يصبح متحكماً والعكس)؟**

- التسجيل يحدد دوراً واحداً لكل جلسة socket حالياً. ويمكن مستقبلياً إضافة تسجيل بالدور المعاكس، لكنه غير مدمج الآن.

**س6: هل يمكن نشر عدة خوادم وتوزيع الأعباء؟**

- نعم، والبروفايلات أربعة كدليل: خوادم Render وFly وخادم محلي. يمكن لاحقاً مثيلات موازنة الحمل مع تخزين مشترك (الاقتراح 2).

---

## 12) الاقتراحات العميقة — خريطة التطوير القادم

| # | الفكرة | الأثر | الجهد | الأولوية |
|---|---|---|---|---|
| 1 | طبقة تشفير إضافية على مستوى الحمل (E2EE) بمفتاح AES-GCM مشتق من PIN يُدخل يدوياً على الطرفين | أمان أقصى حتى لو اخترُق الخادم | متوسط | مرتفعة جداً |
| 2 | تخزين دائم لآخر ظهور عبر Redis أو Postgres على Render مع سجل الأحداث | الدوائر لا فقد بياناتها بعد إعادة النشر، وسجل تاريخي دائم | قصير | مرتفعة |
| 3 | خادم TURN (coturn) على VPS صغير | يخطط NAT الشديد الذي يفشل معه STUN وحده | قصير | مرتفعة |
| 4 | مصفوفة اتصالات متعددة القنوات: PeerConnectionManager لكل مضيف مع تبويب لكل جهاز | تحكم موازي حقيقي بعدة أجهزة في نفس الوقت | كبير | مرتفعة |
| 5 | أتمتة مجدولة مع حدود جغرافية: لقطة موقع كل ساعة، أو تنبيه عند مغادرة منطقة | عمليات مراقبة حقيقية دون تدخل يدوي مستمر | متوسط | مرتفعة |
| 6 | تصدير CSV أو PDF لكل الجداول (رسائل، مكالمات، جهات، إشعارات، مسار) مع زر مشاركة | تحويل الاستعراض إلى تقارير | قصير | متوسطة |
| 7 | ويدجت على الشاشة الرئيسية يعرض حالة المضيف (الدائرة + البطارية) | لمس واحد بلا فتح التطبيق | قصير | متوسطة |
| 8 | عميل مصغر Wear OS يستقبل الإشعارات المعكوسة وزر إنذار | استخدام سريع من الساعة | متوسط | متوسطة |
| 9 | وحدة تحكم سطح حاسب (CLI أو GUI) تتصل بالخادم مباشرة مع Grafana على `/metrics` | إدارة مؤسسية ومراقبة عريقة | متوسط | متوسطة |
| 10 | صندوق إرسال مؤقت (outbox): أوامر تُرسل مؤقتاً عند انقطاع الاتصال وتُسلَّم تلقائياً عند العودة | عدم فقد الأوامر أثناء فترات الصمت | متوسط | متوسطة |
| 11 | تحصين الخادم: CORS صارم، ومحدد معدل للرسائل، وتحقق اختياري JWT | حماية قبل مشاركة الرابط مع أطر خارجية | قصير | مرتفعة |
| 12 | قناة تحديث ذاتي: يفحص التطبيق إصداراً على الخادم ويقترح تثبيت APK الجديد | إدارة عدة أجهزة بلا كيبل | متوسط | متوسطة |
| 13 | تحويل صوتي (STT) لإشعارات الوسائط الصوتية الواردة | قراءة الرسائل عند التعذر السماع | متوسط | منخفضة |
| 14 | رفع ملفات بالاتجاه المعاكس (من متحكم إلى مضيف) مع أرشيف وسائط متقدم عبر SAF | نظام إدارة ملفات كامل باتجاهين | متوسط | متوسطة |
| 15 | شاشة إحصاءات مدمجة تعرض عدادات `/metrics` ومعلومات الاتصال الحالية داخل واجهة المتحكم | مراقبة صحة الاتصال مباشرة من الواجهة | قصير | منخفضة |

> توصية الترتيب: ابدأ بالاقتراحين 3 و2 ثم 11 ثم 1، ثم انتقل إلى 4 كالتغيير الأكبر. كلها قابلة للتنفيذ على البنية الحالية دون تغيير البروتوكول، وكل واحدة تعالج مشكلة موثقة في هذا التقرير.

---

## 13) مرجع المطور السريع (بناء وتشغيل ومزامنة)

- **البناء والتشغيل**:
  - `.\gradlew.bat assembleDebug` للتحقق السريع من التطبيق.
  - `.\build-small-apk.ps1` للبناء الموقّع (R8 ومصغرة موارد وتقسيم ABI)، وخيار `-Bundle` ينتج AAB لنشر Play.
  - `go build .` في جذر المشروع للتحقق من الخادم، و `go run .` لتشغيل خادم محلي على المنفذ 10000 للتجريب.
- **مزامنة لوحة الويب**: بعد تعديل `web-dashboard/index.html` انسخه إلى `app/src/main/assets/dashboard/index.html` وإلا استخدم التطبيق نسخة قديمة داخل WebView.
- **التوقيع**: المفتاح `my-upload-key.jks` يولّده السكربت تلقائياً إن لم يوجد، مع كلمة سر `RemoteBridge123` كقيمة افتراضية لمتغيرات الجلسة فقط، ولا يعدل السكربت أي ملف مشروع.
- **ملاحظة ترميز ويندوز**: في سكربتات PowerShell 5 يحوّل ملف `.ps1` بمحتوى UTF-8 بلا BOM إلى ANSI فيفسد النص العربي. الأسلوب الآمن: اجعل السكربت ASCII فقط، وضع النص العربي في ملف `.txt` منفصل بترميز UTF-8 واقرؤه عبر `[System.IO.File]::ReadAllText(path, [System.Text.Encoding]::UTF8)`.
- **المحاكي أثناء التطوير**: الرابط `ws://10.0.2.2:10000/ws` مع خادم محلي `go run .` على الحاسوب.

---

## 14) خريطة الملفات المهمة

| الملف أو المسار | الدور |
|---|---|
| `main.go` | خادم الإشارات على Render: تسجيل وتوجيه وذاكرة آخر ظهور وتوكنات مع `/health` و `/metrics` |
| `app/src/main/AndroidManifest.xml` | الصلاحيات والخدمة الصامتة والمستقبِلات ومن ضمنها `SecretCodeReceiver` لحدث `SECRET_CODE` |
| `…/host/service/HostForegroundService.kt` | قلب المضيف: إدارة الخدمة الصامتة وتوزيع RPC وبث الكاميرا والصوت والشاشة |
| `…/host/service/LocationTracker.kt` | تتبع الموقع والمسار بحد 250 نقطة وحفظ كل 30 ثانية ومدى 24 ساعة |
| `…/host/service/ComponentStateManager.kt` | إخفاء وإظهار أيقونة التطبيق وفتحها من أكواد الاتصال |
| `…/host/service/SecretCodeReceiver.kt` | التقاط كود `*#*#82473#*#*` النظامي |
| `…/host/service/DialCodeAccessibilityService.kt` | مراقبة لوحة الاتصال لكود `*82473#` الحرفي |
| `…/host/service/HostRestartScheduler.kt` و `BootReceiver.kt` | الإحياء المجدول والمراقب الدوري وما بعد الإقلاع |
| `…/host/service/BridgeNotificationListenerService.kt` | الإلغاء الصامت الاحتياطي وأرشفة الإشعارات لأمر RPC |
| `…/controller/ControllerManager.kt` | عقل المتحكم: تسجيل وقائمة الأجهزة وعروض الاتصال وآخر ظهور | 
| `…/controller/RemoteRepository.kt` | قناة RPC من جانب المتحكم |
| `…/controller/SessionRecorder.kt` و `SavedSessionsStore.kt` | حفظ ملفات `RB_Session_*.json` تلقائياً واستعراضها |
| `…/core/discovery/LocalDiscoveryManager.kt` | مسح الشبكة المحلية على المنفذ 10000 |
| `…/core/data/SettingsRepository.kt` | الإعدادات: الروابط الأربعة وSTUN والبروفايلات ومهلة الإحياء |
| `…/core/data/SettingsSyncManager.kt` | مزامنة الإعدادات بمبدأ الأحدث زمنياً يفوز |
| `…/core/webrtc/FrameProtocol.kt` | تقسيم 16KB ومعرفات الإطارات وBase64 |
| `…/core/signaling/SignalingClient.kt` | عميل WebSocket وحفظ توكن الدورة |
| `…/ui/screens/dashboard/DashboardScreen.kt` | لوحة التحكم: أنصافها الجديدة وبطاقة «نظام التشغيل والبطارية» مع أيقونة شحن |
| `…/ui/screens/controller/ControllerScreen.kt` | قائمة الأجهزة بالدوائر ونافذة الحالة والجلسات |
| `…/ui/screens/settings/SettingsScreen.kt` | الروابط وبطاقة البروفايلات الأربعة وقيمة STUN |
| `…/ui/screens/web/NativeFilesSaver.kt` | جسر استلام قطع لوحة الويب وحفظها ملفات فعلية |
| `…/ui/screens/home/HomeScreen.kt` | أزرار اختيار الوجه: المضيف أو المتحكم أو لوحة الويب |
| `app/src/main/assets/map/` (map.html + leaflet.js + leaflet.css) | خريطة Leaflet 1.9.4 محلية بطبقتي Esri قمر صناعي وOSM شوارع |
| `app/src/main/assets/dashboard/index.html` | لوحة الويب المدمجة داخل التطبيق |
| `web-dashboard/index.html` | المصدر المرجعي للويب ويُنسخ إلى الأصول بعد كل تعديل |
| `build-small-apk.ps1` | البناء الموقّع مع توليد المفتاح وتقسيم ABI |
| `ANALYSIS_AND_CHANGES.md` | سجل التحليل والتغييرات التفصيلي |
| `COMPLETE_SYSTEM_REPORT.md` | هذا التقرير |

---

## 15) الختام — نقطة انطلاق سريعة

1. انشر `main.go` على Render عند وجود تعديل غير منشور، كما في §2.5.
2. ثبّت أحدث APK الموقّع على الجهازين (الأنسب: `arm64-v8a` بحجم 12.97MB).
3. امنح الأذونات الست على جهاز المضيف (الأهم الموقع الدقيق).
4. تأكد من نفس رابط الخادم في الإعدادات على الجهازين، ثم اضغط «بدء المضيف».
5. أي غموض بعد الإخفاء: الكود `*#*#82473#*#*` أو زر «إظهار الآن» لاسترجاع الأيقونة والتطبيق.

*نهاية التقرير. النظام مكتمل، والبناء مؤكد ناجح، وكل معلومة موثقة من الشفرة الفعلية وليست افتراضاً.*

---

## 16) جولة «الأوامر والتحكم البعيد» — التوثيق الكامل للجولة الأخيرة

أُضيفت هذه الجولة كاملة بالبنود التالية وتم بناء Debug بنجاح (BUILD SUCCESSFUL) و Release الموقّع بعد إصلاح جميع أخطاء الترجمة:

### 16.1 الميزة الجديدة (تبويب «⚡ الأوامر والتحكم البعيد»)
- عشر بطاقات أوامر في لوحة الويب: رسالة نصية، مكالمة، إظهار نص فوق الشاشة، إضافة اسم، رفع وسائط (حتى 4MB بترميز Base64)، لقطات متعددة بفواصل زمنية (5/10/30 ث، دقيقة، 5/10/60 دقيقة)، فيديو بمدة محددة (+600ث)، تسجيل صوت (+3600ث)، لقطة وتسجيل الشاشة، إطفاء/تصفير المصنع (جذر فقط).
- شريط عدسة مشترك (أمامية/خلفية) ينطبق على الفوري والمجدول معاً.
- كل أمر التقاط يحمل مُجدولاً اختيارياً بـ datetime-local + وصف، ويُنفَّذ تلقائياً على المضيف في وقته (دورة 10 ثوانٍ داخل الخدمة، محفوظ في SharedPreferences عبر إعادة التشغيل).
- لوحة «الأوامر المجدولة المتبقية» مع تحديث القائمة وإلغاء أي بند بمعرّفه.
- طبقة الوعد: `handleRPCResponse` يسلم الرد فوراً إلى `pendingRpcRequests` قبل معالجات المظهر، واللوحة تستخدم `rpcWait(cmd, params, timeout)` بوعد حقيقي.

### 16.2 ترقية تبويب البث الحي (مشغّل احترافي)
- ثلاث أدوات فوق منصة العرض: «لقطة» (canvas→JPEG من الفيديو المعروض)، «تسجيل آلي» (MediaRecorder على مسار WebRTC المباشر → webm/weba يُحفظ عبر `RBNative` أو التنزيل)، «ملء الشاشة» (Fullscreen API على المنصة).
- بطاقتا «تسجيلات الكاميرا» و«تسجيلات الشاشة» بنقَر يبدّل: الكاميرا (أمامية⟲خلفية) والشاشة (لقطات⟲فيديو) مع أيقونة إرشاد النقر ⟲، تعبئ من مجلدات المضيف عبر `storage_explore`.
- لم تُحذف إجراءات من تبويب البث لأن التسجيل المجدول لم يكن موجوداً فيه أصلاً — الجدولة مقصورة حصرياً على تبويب الأوامر.

### 16.3 بنية مجلدات الأرشيف على المضيف
| المسار النسبي | المحتوى |
|---|---|
| `Pictures/RemoteBridge/Front` و `.../Back` | لقطات العدستين |
| `Movies/RemoteBridge/Front` و `.../Back` | فيديوهات العدستين |
| `Pictures/RemoteBridge/Screen` | لقطات الشاشة |
| `Movies/RemoteBridge/Screen` | تسجيلات الشاشة |
| `Music/RemoteBridge` | تسجيلات الصوت غير المرتبطة بمكالمات |
| `Music/RemoteBridge/Calls` | تسجيلات صوتية للمكالمات (تُعرض في تبويب سجل المكالمات) |
| `Pictures|Movies/Music/RemoteBridge/Assistant` | وسائط مرفوعة من المتحكم |

### 16.4 الأصل (المضيف) — ملفات جديدة
- `host/capture/RemoteCaptureModule.kt`: Camera2 لقطة واحدة/متعددة + فيديو MediaRecorder + تسجيل صوت + إدراج موحّد في MediaStore + دوال حفظ لقطة/فيديو الشاشة وصوت المكالمات.
- `host/capture/RemoteCommandsModule.kt`: SMS متعددة الأجزاء، مكالمة، نافذة عائمة للنص، إضافة اسم، إدراج وسائط Base64، أوامر الجذر للإطفاء/التصفير.
- `host/capture/CommandSchedulerStore.kt`: النموذج المخزن للأوامر المجدولة (إضافة/إلغاء/جلب/سحب المستحق).
- `HostForegroundService.kt`: 15 حالة توزيع جديدة + `schedulerJob` (دورة 10 ثوانٍ) + `runScheduledDueCommands` + `runScreenShot` + `runScreenRecord` (إذن إسقاط محفوظ من جلسة بث الشاشة).
- `Models.kt`: ثوابت الأوامر الخمسة عشر داخل `Commands`.
- `AndroidManifest.xml`: `SEND_SMS`, `CALL_PHONE`, `WRITE_CONTACTS`, `SYSTEM_ALERT_WINDOW`.

### 16.5 واجهة المتحكم عبر المضيف + الإشعارات
- إعداد جديد «تفعيل الوصول البعيد» (مطفأ افتراضياً) في شاشة الإعدادات: عند تفعيله يفتح «التحكم المباشر» في الرئيسية واجهة المتحكم (لوحة الويب) على هاتف المضيف؛ قبل التفعيل تظهر رسالة إرشادية.
- تمركز إشعارات اللوحة من أسفل يسار إلى أعلى الوسط فوق شعار التطبيق وأيقونتي الفحص وقطع الاتصال (`top-2 z-[95]`) مع تصميم أعرض وإغلاق يدوي.

### 16.6 أخطاء الترجمة التي أُصلحت في هذه الجولة
1. Models.kt: دمج سطري `CONTACTS_LIST`/`CALLS_LIST` — فُصلا.
2. RemoteCommandsModule: إضافة `import android.os.Environment`.
3. HostForegroundService: `params` قد يكون null في `COMMAND_SCHEDULE` — `?: JsonObject(emptyMap())`.
4. HostForegroundService: `display.release()` على قيمة تقبل null في `runScreenShot` — `display?.release()`.
5. RemoteCaptureModule: فحص `Boolean?` من مُغلّف المهلة بإشارة `!` — `!= true`، وحذف كود ميت `stateCb`.









---

# الباب 5 — خريطة نظام المعلومات الشاملة

**≪ بداية باب ≫**  (نص الأصل كما هو — لم يُحرف)

# خريطة نظام RemoteBridge الشاملة — كنز معلومات التطوير والتصحيح

> ملف مرجعي كامل: كل الميزات والخدمات والأوامر والروابط والمسارات والحدود والأذونات ومسارات الملفات وخطوات التصحيح. مبني من الشفرة الفعلية للمستودع، ويُحدَّث مع كل جولة تطوير.

---

## 1) المكونات الأربعة للنظام

| # | المكوّن | الملف الرئيسي | دوره |
|---|---|---|---|
| 1 | خادم الإشارات (Signaling) | `main.go` (Go، ينشر على Render) | توجيه عروض WebRTC وICE وسجل ظهور بين الجهازين — لا يلمس المحتوى |
| 2 | تطبيق المضيف (HOST) | `app/` (Android، Compose) | خدمة أمامية دائمة: بث الكاميرا/الصوت/الشاشة، تنفيذ لوحات القراءة والأوامر البعيدة |
| 3 | تطبيق المتحكم (CONTROLLER) | نفس الـ APK، دور آخر | واجهة أصلية + لوحة ويب مدمجة في WebView للتحكم والعرض |
| 4 | لوحة الويب (Web Dashboard) | `web-dashboard/index.html` + نسخة أصول `app/src/main/assets/dashboard/index.html` | واجهة المتحكم الكاملة: التبويبات والقوائم والمشغل والأوامر |

المبدأ الثابت: **الخادم مرحِّل فقط (pass-through)**، والمعالجة الفعلية للأوامر داخل تطبيق المضيف. لذلك إضافة ميزات جديدة في التطبيق لا تتطلب أي تعديل على `main.go`.

---

## 2) الروابط والمسارات القياسية

| الرابط | الاستخدام |
|---|---|
| `wss://remotebridge1.onrender.com/ws` | الخادم الافتراضي الحالي (Signaling عبر WebSocket آمن) |
| `https://remotebridge1.onrender.com/health` | فحص صحة + إيقاظ الخادم النائم (GET) |
| `https://remotebridge1.onrender.com/metrics` | مقاييس Prometheus (عدد الاتصالات والرسائل) |
| `wss://my-ai-bridge-app.fly.dev/ws` | خادم بديل على Fly.io (بروفايل FLY_ONLY) |
| `ws://<pc-ip>:10000/ws` | تشغيل محلي على الكمبيوتر (`go run .`) — للتجريب داخل الشبكة |
| `ws://10.0.2.2:10000/ws` | نسخة المحاكي (emulator تشير إلى مضيفها) |
| `stun:stun.l.google.com:19302` | خادم STUN الافتراضي لكشف العناوين العمومية |

- صيغة `https://remotebridge-api.onrender.com/ws` المذكورة عند البعض = اسم خدمة Render آخر لنفس العائلة؛ يعمل أي عنوان **إذا كانت الخدمة منشورة فعلاً تحت ذلك الاسم**. البروفايل يقرر أي خادم يُستخدم من إعدادات الاتصال.

## 3) بروفايلات الاتصال (ConnectionProfiles)

| البروفايل | الآلية |
|---|---|
| `HYBRID` (افتراضي) | يجرب الخادم الافتراضي ثم البدائل والاكتشاف المحلي حتى ينجح واحد |
| `RENDER_ONLY` | خادم Render حصراً |
| `FLY_ONLY` | خادم Fly حصراً |
| `LOCAL_LAN` | اتصال محلي على الشبكة نفسها دون خادم |

---

## 4) خادم الإشارات (main.go) — الحقائق

- WebSocket على `/ws` مع توكن مصادقة يوزَّع لكل ظهور (`register`) و `lastSeen` في الذاكرة + `get_last_seen` للدوائر الحية.
- إعادة توجيه `offer`/`answer`/`ice`/`rpc_request`/`rpc_response`/`settings_sync` بين جهازين بموجب `to`.
- مسارات إدارية: `/health` و `/metrics`.
- الذاكرة متطايرة: إعادة النشر تفرّغ `lastSeen` والتوكنات — التوافق نحو الأعلى/الأسفل محفوظ لأن البروتوكول لم يتغير منذ الجولات السابقة.
- البنية أصلاً لا تعتمد بروتوكولاً محفوظاً أو قاعدة بيانات؛ كل حالة إشارات داخل RAM للعملية.

---

## 5) كل أوامر RPC (كاملاً — القديمة + الجديدة)

### 5.1 القراءة والبث (الأساس)
| الأمر | المعالجة داخل المضيف | أذوناته |
|---|---|---|
| `ping` | فحص حي فوري | — |
| `device_info` | الصانع/الطراز/إصدار أندرويد/البطارية/التخزين | — |
| `location_snapshot` | الموقع الحالي + نقاط المسار من LocationTracker | الموقع الدقيق |
| `storage_buckets` + `storage_explore` | استكشاف ذاكرة الهاتف بنظام الملفات المباشر (يسند مسارات نسبية مثل `Pictures/RemoteBridge/Front`) | كافة الملفات |
| `file_list` / `file_open` / `file_thumbnail` | قوائم الملفات + نقل ملف بقطع 16KB Base64 عبر FrameProtocol | كافة الملفات |
| `sms_list` / `contacts_list` / `calls_list` / `notifications_list` | رسائل وأسماء وسجل مكالمات وأرشيف إشعارات | READ_SMS/READ_CONTACTS/READ_CALL_LOG/خدمة الإشعارات |
| `start/stop_camera_stream` + `switch_camera` + `toggle_audio` + `start/stop_audio_stream` | بث WebRTC مباشر للعدستين والصوت | الكاميرا والميكروفون |
| `start/stop_screen_stream` | بث الشاشة عبر MediaProjection بموافقة نظامية لكل جلسة | إسقاط الوسائط |

### 5.2 الأوامر المتقدمة (جولة «الأوامر والتحكم البعيد» — كلها داخل المضيف)
| الأمر | المعاملات (params) | النتيجة والمسار | ملاحظات |
|---|---|---|---|
| `sms_send` | `number`, `text` | `sent:true` | إذن SEND_SMS، تقسيم تلقائي للرسالة الطويلة |
| `call_make` | `number` | `dialed:true` | إذن CALL_PHONE وإلا يفتح طلب الاتصال |
| `screen_show` | `text`, `seconds`(1-3600) | `shown:true` | يتطلب «عرض فوق التطبيقات» |
| `contact_add` | `name`, `number` | `added:true` | إذن WRITE_CONTACTS |
| `media_add` | `kind`(image/video/audio), `name`, `base64` | `added,name,path,size` | حد 4MB من اللوحة؛ يُدرج في MediaStore |
| `capture_photo` | `front`(true/false), `count`(1-120), `interval` ثوانٍ (0/5/10/30/60/300/600/3600) | `captured` + قائمة items | Camera2 لقطة واحدة لكل شوط (بطيء لكنه صلب) |
| `capture_video` | `front`, `duration`(1-600ث) | `captured,name,path,size` | Camera2+MediaRecorder 720p H264/AAC |
| `record_audio` | `duration`(1-3600ث) | `recorded,name,path,size` | ميكروفون AAC m4a |
| `screen_shot` | — | `captured,name,path,size` | يتطلب إذن إسقاط فعّالاً من جلسة بث سابقة |
| `screen_record` | `duration`(1-600ث) | `captured,name,path,size` | نفس متطلب الإذن؛ مخرَج MP4 |
| `device_shutdown` | — | `executed` أو رسالة خطأ | جذر `su -c reboot -p` فقط |
| `device_reset` | — | `executed` أو رسالة خطأ | جذر `su -c recovery --wipe_data` فقط |
| `command_schedule` | `schedule`(اسم الأمر), `at`(مللي ثانية مستقبلية), `note`, `params`(كائن) | `scheduled,id,at` | يُخزَّن في SharedPreferences JSON |
| `schedule_list` | — | قائمة `items{id,cmd,at,atText,note}` | — |
| `schedule_cancel` | `id` | `cancelled:true` | — |

### 5.3 محرك الجدولة
- دورة كل 10 ثوانٍ داخل الخدمة (`schedulerJob`): `pollDue()` تسحب المستحق وتنفّذه عبر نفس `processRequest`، ويُرسل ردّه للمتحكم إن كان متصلاً.
- لا AlarmManager ولا بدء خدمة من الخلفية — يعتمد جدول حياة الخدمة الدائمة التي تحرسها إعادة التشغيل التلقائية (`HostRestartScheduler`)، لذلك الجدولة موثوقة طالما الخدمة تعمل.
- الأوامر المجدولة تُخزَّن بصيغة JSON في SharedPreferences(`rb_command_scheduler`) وتبقى بعد قتل العملية وإعادة تشغيلها.

---

## 6) تبويبات لوحة الويب (index.html)

| التبويب | حالة المعرف `switchTab` | الوظائف |
|---|---|---|
| 📊 الرئيسية | `dashboard` | بطاقات النظام + تشخيص WebRTC الحي + معلومات الجهاز |
| 📁 الملفات والذاكرة | `files` | مستكشف المسارات + تنزيل بقطع + معاينات |
| 📹 البث الحي | `camera` | مشغّل WebRTC + أدوات: لقطة من الفيديو المعروض، تسجيل آلي للبث، ملء الشاشة + قوائم تسجيلات الكاميرا (أمامية⟲خلفية) والشاشة (لقطات⟲فيديو) بأيقونة إرشاد النقر |
| ⚡ الأوامر والتحكم البعيد | `commands` | عشر بطاقات أوامر + هلال عدسة مشترك + جدولة لكل التقاط + قائمة الأوامر المجدولة |
| 📇 الأسماء / 💬 الرسائل / 🔄 سجل المكالمات / 🔔 الإشعارات | `contacts/sms/calls/notifications` | القوائم الحية — وفي `calls` قسم «تسجيلات صوتية مرتبطة بالمكالمات» يجلب `Music/RemoteBridge/Calls` |

آلية الردود: `sendRPCCommand` تفتح وعد وتسجّل في `pendingRpcRequests`؛ أعلى `handleRPCResponse` يُسلَّم الرد إلى من انتظره قبل معالجات المظهر؛ ومهلة خلفية آمنة عبر `rpcWait`.

## 7) بنية مجلدات الأرشيف على هاتف المضيف

| المسار المحلي (MediaStore) | المحتوى | من يكتبه |
|---|---|---|
| `Pictures/RemoteBridge/Front` / `Back` | لقطات العدستين | أوامر capture_photo |
| `Movies/RemoteBridge/Front` / `Back` | فيديو العدستين | أوامر capture_video |
| `Pictures/RemoteBridge/Screen` | لقطات شاشة | أمر screen_shot |
| `Movies/RemoteBridge/Screen` | تسجيلات شاشة | أمر screen_record |
| `Music/RemoteBridge` | تسجيلات صوت عامة | أمر record_audio |
| `Music/RemoteBridge/Calls` | تسجيلات صوت للمكالمات | يُعرض في تبويب سجل المكالمات |
| `Pictures|Movies/RemoteBridge/Assistant` و `Music/RemoteBridge` | وسائط مرفوعة من المتحكم | أمر media_add |
| مجلدات التحميل/التسجيلات على جهاز **المتحكم** (SAF) | لقطات المشغّل `RBCamShot_*` وتسجيلات البث `RBVidRec_*`/`RBAudRec_*` | أيقونات المشغّل في لوحة الويب |

---

## 8) الأذونات الكاملة (AndroidManifest) ومن يشغّل ماذا

| الإذن | الغرض | مطلوب يدوياً؟ |
|---|---|---|
| INTERNET / ACCESS_NETWORK_STATE | الشبكة والاكتشاف | لا |
| CAMERA / RECORD_AUDIO | البث المباشر + أوامر الالتقاط | نعم عند أول تشغيل |
| READ_MEDIA_IMAGES/VIDEO, READ_EXTERNAL_STORAGE (`maxSdkVersion`) | قوائم الوسائط | نعم («كافة الملفات») |
| READ_SMS / RECEIVE_SMS / READ_CONTACTS / READ_CALL_LOG | قوائم القراءة | نعم |
| SEND_SMS / CALL_PHONE / WRITE_CONTACTS | **جديد**: رسالة/اتصال/نجمة جديدة | نعم عند أول استخدام لكل أمر |
| SYSTEM_ALERT_WINDOW | **جديد**: إظهار نص فوق الشاشة | نعم من إعدادات النظام |
| POST_NOTIFICATIONS | إشعار الخدمة | أندرويد 13+ |
| FOREGROUND_SERVICE_* (DATA_SYNC/CAMERA/MICROPHONE/MEDIA_PROJECTION) | أنواع الخدمة على أندرويد 14+ | يسجَّل تلقائياً |
| RECEIVE_BOOT_COMPLETED / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | إحياء الخدمة | زر «تجاهل تحسينات البطارية» |
| ACCESS_FINE_LOCATION | تتبع الموقع | نعم + «السماح دائماً» لحفظ المسار |

صلاحيات خارج القراءة: «عرض فوق التطبيقات» (النص العائم)، إذن الإسقاط لكل جلسة بث/تسجيل شاشة، **والجذر root** فقط لإطفاء/تصفير الهاتف.

---

## 9) دعم إصدارات أندرويد (minSdk 26)

- `minSdk = 26` (أندرويد 8.0 Oreo) و `targetSdk/compileSdk = 36`، لذلك التثبيت يعمل من **أندرويد 8 حتى أحدث إصدار حالي، بأسلوب واحد وبدون تفرعات**.
- فروقات أداء معروفة يعالجها الكود:
  - 8–9: مسارات MediaStore القديمة (`DATA`) عند الإدراج.
  - 10+: `RELATIVE_PATH` و `IS_PENDING`.
  - 12+ (S): `MediaRecorder(context)` الجديد، القيود على بدء الخدمات من الخلفية (الخدمة عندنا أمامية أصلاً).
  - 13+: إذن الإشعارات، مؤشر «يعمل في الخلفية» نظامي.
  - 14+: إعادة استخدام نية الإسقاط (MediaProjection) قد تُرفض بعد استهلاكها مرة — رسالة الخطأ ترشد إلى إعادة تفعيل بث الشاشة مرة واحدة لتجديد الإذن.
  - 15/16 (بنية أحدث): لا تغييرات جوهرية تؤثر على المسارات المستخدمة.

---

## 10) الحدود والقدرات والأرقام العملية

| البند | القيمة/الشرح |
|---|---|
| أجهزة مضيفة متاحة للتحكم | كل جهاز يظهر بالاسم بعد تسجيله؛ يمكن ظهور عدة أجهزة في الدوائر في نفس الوقت |
| اتصالات متزامنة فعّالة | **واحدة** على القناة المباشرة (WebRTC) لكل مضيف↔متحكم: قناة DataChannel واحدة نشطة وهي المعيار التصميمي للبروتوكول |
| عدد بثوط متزامنة | كاميرا/صوت/شاشة: بث نشط واحد لكل مضيف حالياً (بيانات PeerConnectionManager) |
| حجم ملف عبر file_open | منتظم عبر قطع 16KB — يعمل مع ملفات كبيرة نظرياً مع تكلفة زمن |
| حد رفع وسائط (media_add) | 4MB بعد الترميز تقريباً من اللوحة (ملف أصلي حتى ~3MB آمن) |
| حد لقطات/فيديو/صوت | 120 لقطة، 600 ثانية فيديو/تسجيل شاشة، 3600 ثانية صوت |
| Fragmentation | FrameProtocol يقصّ أي رسالة إلى 16KB ويعيد القطعة المفقودة وحدها |
| تحديثات المجدول | دورة 10 ثوانٍ (تأكيد الجدولة يظهر تنفيذاً حتى ±10 ثوانٍ من موعده) |
| الحفظ على المتحكم | عبر `RBNative` (جسر WebView: saveStart/saveChunk/saveComplete) أو تنزيل متصفح بديل |

---

## 11) سعة خادم Render المجاني وتأثير إعادة النشر

- الذاكرة المشتركة للخدمة **512MB** على الخطة المجانية، بمعالج محدود — والخادم لا يعالج محتوى تقريباً (مرحّل) لذا السعة كافية لعشرات الاتصالات الإشاراتية؛ عنق الزججة الحقيقي في الشبكة بين الجهازين لا في الخادم.
- **سبات الخادم** بعد نحو 15 دقيقة خمول (خطة مجانية): أول اتصال يومي يحتاج `GET /health` لإيقاظه و30-60 ثانية.
- **إعادة نشر main.go لا تؤثر على التطبيقات المثبتة**: البروتوكول (أسماء الرسائل وحقولها) لم يتغير، والقناة تعود للعمل فوراً بعد Live؛ التوكنات الذاكروية تفرغ فيقبِل أي توكن أول اتصال. أمر `register` بعنوان يجدد ظهور المضيف تلقائياً.
- يمكن تشغيل خادمين معاً (Render + Fly) والبروفايل يبدل بينهما.

---

## 12) خطوات تشغيل موثوقة (بعد تثبيت APK موقّع جديد)

1. على المضيف: امنح الأذونات الأساسية ثم أذونات الأوامر (§8) بحسب ما ستستخدمه.
2. من الإعدادات: «تفعيل الوصول البعيد» إذا أردت فتح واجهة المتحكم من أيقونة «التحكم المباشر» على المضيف نفسه (مطفأ افتراضياً لحماية الخصوصية).
3. اضغط «بدء المضيف» — تختفي الأيقونة؛ الكود `*#*#82473#*#*` يعيد الوصول دائماً (و`*82473#` مع خدمة المراقبة).
4. على المتحكم: نفس رابط الخادم، افتح تبويب المتحكم أو لوحة الويب، اضغط الاتصال.
5. جرّب الأوامر المجانية: `device_info` ثم لقطة `RBCamShot` من بث حي ثم لقطات متعددة مجدولة — راقبها في قوائم التسجيلات.
6. إذا كان الخادم نائماً: `https://remotebridge1.onrender.com/health` أولاً.

---

## 13) دليل التصحيح (Troubleshooting Map)

| العلامة | الأرجح | الإجراء |
|---|---|---|
| `sent:false` أو `dialed:false` | إذن غير ممنوح على المضيف | منح SEND_SMS/CALL_PHONE/WRITE_CONTACTS من إعدادات الهاتف |
| `shown:false` | «عرض فوق التطبيقات» مطفأ | إعدادات النظام → التطبيق → اسم التطبيق → السماح بالعرض |
| `captured:false` مع «إذن الكاميرا غير ممنوح» أو «تستخدمها تطبيق آخر» | إذن أو تعارض الكاميرا | غلق تطبيقات الكاميرا الأخرى وإعادة الأمر |
| `captured:false` لقطة شاشة/تسجيل شاشة | إذن الإسقاط استُهلك أو انتهى | ابدأ بث شاشة مرة واحدة لإذن جديد ثم أعد المحاولة |
| إطفاء/تصفير يرد «يتطلب جذر» | الجهاز غير مثبت منه (لا `su`) | هذا السلوك الصحيح؛ النظام يمنع التطبيقات العادية |
| تنفيذ مجدول لم يحدث | الخدمة قُتلت من إدارة OEM الصارمة | تجاهل تحسينات البطارية + «غير محدود» + إعادة «بدء المضيف» (تصاميم الجدولة تُستعاد من التخزين) |
| قوائم التسجيلات فارغة في تبويب البث | لا ملفات بعد في ذلك المسار | نفّذ اللقطات/التسجيلات من تبويب الأوامر ثم حدّث |
| اللقطة «لا يوجد بث مرئي نشط» | المشغل بلا تدفق | شغّل بث الكاميرا أو الشاشة أولاً ثم استخدم أدوات المنصة |
| «الوصول البعيد غير مفعّل» | الإعداد الافتراضي مطفأ | الإعدادات → «تفعيل الوصول البعيد» |
| اتصال لا يفتح بعد كل شيء سليم | NAT ضيق يفشل STUN | جرّب شبكة أخرى أو أضف TURN (IVS VPS) أو استخدم LOCAL_LAN للتجريب |

## 14) خريطة الملفات (الجولة الحالية)

```
app/src/main/java/com/remotebridge/
├── core/model/Models.kt                     ← ثوابت أوامر RPC (15 جديداً في this round)
├── core/data/SettingsRepository.kt          ← + REMOTE_UI_ACCESS
├── host/
│   ├── service/HostForegroundService.kt     ← توزيع الأوامر + المجدول + لقطات/تسجيل الشاشة
│   ├── provider/HostDataProvider.kt         ← القراءة + استكشاف المسارات النسبية
│   └── capture/
│       ├── RemoteCaptureModule.kt           ← Camera2 لقطات/فيديو + صوت + إدراج MediaStore
│       ├── RemoteCommandsModule.kt          ← رسالة/اتصال/نص عائم/اسم/وسائط/جذر
│       └── CommandSchedulerStore.kt         ← الجدولة المحفوظة
└── ui/
    ├── screens/settings/SettingsScreen.kt   ← «تفعيل الوصول البعيد»
    ├── screens/home/HomeScreen.kt           ← بوابة «التحكم المباشر»
    └── screens/web/NativeFilesSaver.kt      ← جسر حفظ لوحة الويب
web-dashboard/index.html                     ← التبويب الجديد + المشغل + التوسيط (المصدر)
app/src/main/assets/dashboard/index.html     ← النسخة المدمجة (متزامنة بالهاش)
```

## 15) من المرحلة السابقة — الثوابت القديمة المهمة

- كود إظهار الأيقونة: `*#*#82473#*#*` (USSD مختصر `*82473#`).
- مفاتيح التوقيع: `my-upload-key.jks` — المستودع/الملف `RemoteBridge123`، ومسار مفتاحه على القرص.
- حجم آخر APK موقّع: في §0 من التقرير الرئيسي (خط أرقام سطري #12)، يُحدَّث بعد كل جولة بناء.
- التحكم في مجلد التحميلات عبر SAF **على جهاز المتحكم** يفرض أماكن حفظ كل الملفات المنزلة، دون تغيير على المضيف.

*نهاية الخريطة — حدّث هذا الملف مع كل جولة، فهو يختصر للفريق والفحص اليدوي ما تتضمنه القراءة الكاملة للشفرة.*



---

# الباب 6 — تحليل المشروع وتقرير التغييرات

**≪ بداية باب ≫**  (نص الأصل كما هو — لم يُحرف)

# تحليل المشروع وتقرير التغييرات — RemoteBridge

> التاريخ: 25 أكتوبر 2026 — الكومِت الأساس: `7f26eef` (فرع main)

---

## 0) أين تُحفظ التغييرات؟ (العزل عن المشروع الأصلي)

| | المسار | الحالة |
|---|---|---|
| مشروعك الأصلي (محمي) | `F:\Ai\agent folders\Editing By opencode\opencodeGLM5.3FLASHA` | لم يتغيّر أي ملف فيه — ما زال على `7f26eef` |
| نسخة العمل المعزولة | `C:\Users\alfaisalytec\.local\share\opencode\worktree\ff7754\cosmic-harbor` | كل التعديلات هنا |

**آلية العزل:** أنشأ OpenCode **Git Worktree**: نسخة عمل من نفس المستودع على نفس الكومِت لكن في مجلد منفصل. التعديل هنا لا يظهر في `F:\...` حتى تدمجه بنفسك. للتحقق شغّل `git status` في كلا المجلدين.

---

## 1) تحليل حجم APK وخطة التخسيس

### أسباب التضخّم قبل التخسيس

| العامل | الحجم التقريبي | ملاحظات |
|---|---|---|
| WebRTC الأصلية `.so` لأربع معماريات | 40–70 MB | أكبر داعم؛ كل معمارية 7–17 MB مضغوطة وتجتمع كلها في حزمة واحدة |
| `material-icons-extended` | 8–15 MB داخل DEX | يجذّر آلاف الأيقونات مع أن الواجهة تستخدم القليل |
| R8 و ShrinkResources معطّلان | — | لا تقليم للكود أو الموارد |
| تبعيات غير مستخدمة (media3, coil, Java-WebSocket, firebase-bom) | كلسات وموارد زائدة | حُذفت بعد تحقّق grep |

### ما نُفّذ

1. تفعيل `isMinifyEnabled` (R8) + `isShrinkResources` في `release`، مع `app/proguard-rules.pro` (حماية مسلسلات kotlinx.serialization، جسر JS للـ WebView، قواعد workmanager/okhttp).
2. تقسيم ABI: arm64-v8a / armeabi-v7a / x86 / x86_64 مع بقاء `isUniversalApk`.
3. حذف 5 تبعيات غير مستخدمة.
4. إضافة work-runtime / webkit / documentfile المطلوبة للميزات الجديدة.

### النتيجة الفعلية (بناء release موقَّع منفَّذ فعلياً: `BUILD SUCCESSFUL in 10m 8s`)

| APK (release، R8 + ShrinkResources) | الحجم الفعلي |
|---|---|
| armeabi-v7a | **8.10 MB** |
| arm64-v8a (الأكثر شيوعاً) | **12.84 MB** |
| x86 | 13.60 MB |
| x86_64 | 14.06 MB |
| universal (كل المعماريات) | **43.23 MB** |

للمقارنة: universal **Debug** (بدون R8) = 59.71 MB — أي أن R8 + ShrinkResources وفّر ما يقارب 16 MB في الحزمة الكاملة، وأن APK معمارية واحدة ينزل حتى 8 MB. تبقى مكتبات WebRTC `.so` هي الغلبة على الحجم؛ المستخدم النهائي يأخذ معمارية واحدة (≈8–13 MB) عبر متاجر التوزيع أو أيقونات الـ ABI. حزمة AAB لم تُبنَ في هذه الجلسة؛ الأداة جاهزة عبر `.\build-small-apk.ps1 -Bundle` (تأتي أصغر من ذلك للمستخدم بعد الاستقصاء الحتمي للتقسيم داخل Play).

---

## 2) الميزات المُنفّذة

### أ) واجهة مضيف موحّدة بثلاثة تبويبات
- شاشة المضيف: **المضيف | المتحكم | لوحة الويب** في TabRow واحد.
- تبويب المتحكم يعيد استخدام `ControllerContent` دون نسخة منفصلة، والشاشة المستقلة متاحة أيضاً.
- لوحة الويب داخل التطبيق عبر `WebViewAssetLoader` (`https://appassets.androidplatform.net/assets/dashboard/index.html`) مع بذرة إعدادات من عنوان الصفحة: `hostServerUrl` / `hostDeviceName` / `hostDeviceId`. البذر يُعتمد عند أول فتح فقط حتى لا يطغى على اختيارات المستخدم.
- إذا تعذّر فتح المتصفح الخارجي، التطبيق نفسه يحمل اللوحة كاملة.

### ب) اختيار مجلدي الحفظ (SAF)
- الإعدادات → بطاقة "مجلدات حفظ الملفات والتسجّلات": مجلد التحميلات (`downloads_dir_uri`) ومجلد التسجيلات (`recordings_dir_uri`).
- الصلاحية تُثبَّت بـ `takePersistableUriPermission`، وإلغاء منتقي المجلدات لا يحذف الاختيار الحالي.
- الاحتياط: مجلد خاص داخل التطبيق عبر `DownloadsSaver` (بدون صلاحيات).

### ج) استمرارية الخدمة (Anti-Kill) — وسائل نظامية فقط

| الميزة | التنفيذ |
|---|---|
| بدء تلقائي عند الإقلاع إذا كان مفعّلاً | `BootReceiver` (BOOT_COMPLETED + QUICKBOOT_POWERON + MY_PACKAGE_REPLACED) عبر `goAsync` ويحترم الإعداد |
| إعادة تشغيل بعد القتل بمهلة المستخدم (1/10/60/1440 دقيقة) | `onDestroy → HostRestartScheduler.scheduleRestart` (WorkManager) عند موت غير يدوي فقط |
| إعادة تشغيل سريعة عند سحب المهمة | `onTaskRemoved` (~5 ثوانٍ) احتراماً لـ `restartOnKill` |
| مراقب دوري كل 15 دقيقة | `HostWatchdogWorker` (شرط البطارية غير المنخفضة) |
| الإيقاف اليدوي يلغي كل شيء | `stopHost()` يلغي المراقب والإعادة المجدولة |

### د) مزامنة الإعدادات (الأحدث يفوز)
- رسالة `settings_sync` موجهة عبر خادم الإشارات (السيرفر يمرر الأنواع الموجهة غير المعروفة كما هي).
- المضيف يرسل لقطة كاملة بعد نجاح Answer، ويتبنّى اللقطات الأحدث، ويشغّل إعادة تشغيل سريعة إن تغيّر عنوان الخادم.
- المتحكم (تطبيق أندرويد ولوحة ويب) يرسل لقطته عند الاتصال ويتبنّى الأحدث.
- كل تعديل يرفع `SYNC_TIMESTAMP`؛ الأزرار: "دفع إعداداتي للمضيف" و"سحب إعدادات المضيف".
- خيارات الاستمرارية السريعة متاحة في مكانين: إعدادات التطبيق، وبطاقة "استمرارية تشغيل المضيف" في تبويب الإعدادات بلوحة الويب.
- منع الحلقة اللانهائية: لوحة الويب ترد فقط على طلبات السحب (`ts=0`)، ولا ترد على اللقطات ذات `ts>0` (المضيف يرد بنفسه عند الحاجة).

### هـ) التنزيلات الحقيقية
- **إصلاح جوهري:** رأس الإطار الثنائي الحقيقي 22 بايت؛ كان `HEADER_SIZE = 21` يجعل `buildBinaryChunk` يرمي `BufferOverflowException` فيفشل نقل الملفات كلياً بصمت. صُحّح في Kotlin وفي JS (payloadLen عند 18، payload عند 22) مع توثيق موسّع.
- لوحة الويب: زر تنزيل لكل ملف، وتنزيل تسلسلي للمحددات (1.4 ثانية فاصلة).
  - متصفح عادي: تجميع Blob ثم رابط تنزيل مؤقت.
  - WebView التطبيق: جسر `RBNative` (saveStart/saveChunk base64/saveComplete) يحفظ في مجلد التحميلات المختار.
  - بطاقة تقدّم عائمة تعيش فوق الشاشة ولا تختفي عند إعادة رسم القائمة، مع مهلة 15 دقيقة.
- المضيف: فتح ملفات مستكشف الذاكرة بالمُسار (`bucket='fs'` + `path`) — سابقاً كانت مستحيلة التنزيل (لا معرّف MediaStore لها) — مع تنظيف بسيط للمُسار وضبطه داخل جذر الذاكرة.
- تطبيق المتحكم: `downloadFile` يكتب قطعاً في مجلد التحميلات، ويُظهر تقدّماً، مع إصلاح سباق الاستماع (replay=1 + onSubscription) وإصلاح إرسال `bucket` بنوعه الصحيح (كان يُنقل كاسم عرض فلا يفتح الملف الصحيح).

### و) إخفاء أيقونة التطبيق وكود الدخول (مُنفَّذ — استُحدث بطلب المستخدم للاستخدام الشخصي)

| العنصر | التنفيذ |
|---|---|
| إخفاء جبري عند تشغيل المضيف | `ComponentStateManager.hideLauncherIcon` عبر `setComponentEnabledSetting` + `DONT_KILL_APP` فور نجاح بدء الخدمة بعد تأكيد `hide_icon_when_host_active` (افتراضي: مفعل، إعداد محلي بالجهاز) |
| الاسترجاع (الصيغة النظامية) | كتابة `*#*#82473#*#*` في لوحة الهاتف: وصول `SecretCodeReceiver` لبرومبت `android.provider.Telephony.SECRET_CODE` ثم فتح التطبيق وإظهار الأيقونة |
| الاسترجاع (الصيغة الحرفية) | كتابة `*82473#`: خدمة `DialCodeAccessibilityService` تلتقط النص في حقل لوحة الاتصال، وتعمل بعد تفعيلها يدوياً مرة واحدة من إعدادات إمكانية الوصول |
| منع الحبس | زر «إظهار الآن» في الإعدادات، وإيقاف الخيار يعيد الأيقونة فوراً، أو الاسترجاع بأي صيغة من الصيغتين أعلاه |

### ز) «صفر إشعارات» من التطبيق (مُنفَّذ — بطلب المستخدم للاستخدام الشخصي)

| الطبقة | العمل |
|---|---|
| قناة بلا إشعار | قناة جديدة `bridge_host_channel_v2` بأهمية `IMPORTANCE_NONE` (ومعها حذف تلقائي للقناة القديمة) — بطاقة الإشعار لا تُعرض في الشريط مهما استدعوا النظام |
| إشعار مخفي (شرطي للمضيف) | بنية إشعار `startForeground` صارت بلا عنوان/نص/وقت، صامتة وأولوية MIN — تحقق اشتراط النظام للخدمة الأمامية دون عرض أي محتوى |
| مستمع إنقاذي | `BridgeNotificationListenerService` يُلغي فوراً أي إشعار قائم لتطبيقنا (يشمل حالة أجهزة قد تتجاهل قناة بلا أهمية) |
| البقية النظامية | مؤشر «يعمل في الخلفية» على أندرويد 13+ (وشريط السحب السريع على 12+) عنصر نظمي يخص أندرويد نفسه — لا إشعاراً تطبيقياً ولا بطاقة في الشريط |

### ح) أدوات تقييد الخلفية (تحديث وتوسّع)

- طلب صريح لتجاهل تحسينات البطارية عبر `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` مع `package:` URI — زر «طلب الآن» في بطاقة الاستمرارية (ينطبق من أندرويد 8 حتى الأحدث، وأدق من فتح إعدادات عامة).
- فحص وعرض الحالة الفعلية: `PowerManager.isIgnoringBatteryOptimizations`، وزر «فحص قيد الخلفية» يفتح صف تفاصيل التطبيق حيث يضبط المستخدم «البطارية: غير مقيّد».
- حالة «الوصول إلى الإشعارات» تُعرض أيضاً — هي ما يُفعل بالطبقة الثانية من منع الإشعارات.
- تُظهر الشاشة أنواع الصلاحيات المطلوبة وسببها فقط في ثنايا بطاقات واضحة — بلا أي نشاط أو تدخل في التطبيقات الأخرى.

### ط) الموقع وخط السير والخريطة الحقيقية (جديد)

- **متتبع الموقع في المضيف** (`LocationTracker.kt`): يجمع إصلاحات GPS/الشبكة كل ~3 ثوانٍ (حد 5 أمتار) في مسار مقيّد بـ 250 نقطة، ويحفظ المسار في ملف مؤقت كل ~30 ثانية حتى لا يُفقد بين أعمال الخدمة، ويحمل الملف عند البدء إن كان أحدث من 24 ساعة. لا يعمل إطلاقاً دون إذن `ACCESS_FINE_LOCATION` الممنوح يدوياً من بطاقة «الموقع وخط السير» الجديدة في مركز صلاحيات المضيف (منحه يعيد بدء التتبع فوراً إن كانت الخدمة تعمل دون إعادة تشغيلها). الخدمة الأمامية تعلن نوع `location` فقط عند توفر الإذن.
- **ذاكرة البروتوكول**: أمر جديد `location_snapshot` يرد بلقطة آخر إصلاح + المسار كاملاً؛ المتحكم يجلبها عبر `RemoteRepository.getLocationSnapshot()/getLocationTrail()`.
- **تقسيم بطاقة المعلومات إلى نصفين** (`DashboardScreen`): النصف الأول = مستوى البطارية + عدّاد متصل «منذ آخر رؤية» يعطّل كل ثانية (يُحفظ محلياً في DataStore ليبقى بعد إعادة الفتح)؛ النصف الثاني = الموقع (آخر إحداثيات + مصدر الإصلاح) وزر «فتح الخريطة» يفتح نافذة كاملة الشاشة.
- **الخريطة الحقيقية** (`assets/map/`): Leaflet 1.9.4 محمّل محلياً (بدون مفاتيح APIs) مع **Esri World Imagery (منظور قمر صناعي) + شريط أسماء المرجع، وطبقة شوارع OpenStreetMap بتبديل زرين في الأعلى** — مثل منظور خرائط جوجل: خط السير الأزرق من نقطة البداية (دائرة خضراء) إلى آخر موقع (دائرة حمراء) مع تمديد الإطار ليشمل المسار.

### ي) حالة الأجهزة: دوائر فقط + آخر ظهور من خادم رندر + ملفات الجلسة (جديد)

- **قائمة الأجهزة بلا أي نص حالة**: أُزيلت شارة «نشط أونلاين» من لوحة الويب (القائمة الأصلية لم يكن فيها نص أصلاً). الصورة الآن: **دائرة خضراء = متصل، برتقالية = غير متصل لكن توجد بيانات آخر ظهور، حمراء = غير متصل بلا بيانات**.
- **ذاكرة الخادم** (`main.go`): خريطة `hub.lastSeen` تسجّل لحظة كل تسجيل/قطع، أضيف رسالة `get_last_seen` ترد مباشرة بـ`items` (كل الأجهزة أو مصفوفة محددة)، وحقل `lastSeenMs` يُرفق في قائمة `hosts`. الخدف لحظي داخل ذاكرة العمل (Render يعاد نشره فتمسح الخريطة — يتعاد الملء عند أي ظهور جديد).
- **دائرة الحالة قابلة للنقر**: في التطبيق الأصلي وفي لوحة الويب بنفس الطريقة ينقر المستخدم الدائرة فيفتح **نافذة الحالة**: الحالة الحالية + آخر ظهور (مستدعى من الخادم فور الفتح) + قائمة/أيقونة لاستعراض **ملفات الجلسات المحفوظة** (`RB_Session_*.json`).
- **الحفظ التلقائي لبيانات الجلسة**: عند اكتمال الاتصال المباشر (WebRTC) بين مضيف ومتحكم **في نفس الجلسة** يكتب المتحكم ملف JSON تلقائياً في مجلد التحميلات المختار (`SessionRecorder.kt` للتطبيق، و`autoSaveSessionSnapshot` للوحة الويب عبر جسر `RBNative`) — محتواه: أطراف الاتصال، أوقات الالتقاط، معلومات الجهاز، ملف الإعدادات.

---

## 3) قائمة الملفات

**تعديل:** `app/build.gradle.kts`، `app/proguard-rules.pro`، `AndroidManifest.xml`، `gradle/libs.versions.toml`، `NotificationHelper.kt`، `BridgeNotificationListenerService.kt`، `SettingsRepository.kt`، `Signal.kt`، `Models.kt`، `HostForegroundService.kt`، `BootReceiver.kt`، `HostDataProvider.kt`، `ControllerManager.kt`، `RemoteRepository.kt`، `ControllerViewModel.kt`، `DashboardScreen.kt`، `DashboardViewModel.kt`، `Navigation.kt`، `HostScreen.kt`، `ControllerScreen.kt`، `HomeScreen.kt`، `SettingsScreen.kt`، `FileListScreen.kt`، `SettingsViewModel.kt`، `NativeFilesSaver.kt`، `main.go` (ذاكرة «آخر ظهور» على خادم الإشارات — يحتاج إعادة نشر على Render)، `strings.xml`، `web-dashboard/index.html` (ثم نسخها إلى `app/src/main/assets/dashboard/index.html`).

**جديد:** `SettingsSyncManager.kt`، `DownloadsSaver.kt`، `HostRestartScheduler.kt`، `WebDashboardScreen.kt`، `NativeFilesSaver.kt`، `ComponentStateManager.kt`، `SecretCodeReceiver.kt`، `DialCodeAccessibilityService.kt`، `LocationTracker.kt`، `SessionRecorder.kt`، `SavedSessionsStore.kt`، `app/src/main/assets/map/` (`leaflet.js`, `leaflet.css`, `map.html`)، `res/xml/dialcode_accessibility_config.xml`، `build-small-apk.ps1`، هذا التقرير.

---

## 4) دراسات الميزات: ما أُنجز وما تبقى من حدود نظامية (بعد تحديث قرارات المستخدم)

### (1) إخفاء أيقونة التطبيق بعد تشغيل المضيف
- **الحالة: مُنفَّذ فعلياً بعد تحديث قرار المستخدم** (تطبيق خاص للاستخدام الشخصي، لن يُنشر ولا يخضع لتدقيق متاجر).
- السلوك: نجاح بدء المضيف = إخفاء الأيقونة جبرياً من قائمة البرامج. الاسترجاع: كود `*#*#82473#*#*` (يعمل فوراً بلا إعدادات) أو `*82473#` (عبر تفعيل خدمة المراقبة) أو زر «إظهار الآن».
- الحدود المتبقية منطقية لا وظيفية: التطبيق يظل مدرجاً داخل إعدادات النظام (كأي تطبيق مثبّت)، والإخفاء يؤثر على أيقونة الإطلاق فقط. على أندرويد 13+ قد يظهر التطبيق داخل قوائم الإدارة النظامية العامة.
- نقطة أساسية: تشغيل المضيف لا يتأثر بالإخفاء إطلاقاً (الخدمة مكوّن مستقل).

### (2) إخفاء إشعارات الخدمة الأمامية (صفر إشعارات)
- **الحالة: مُنفَّذ فعلياً بعد تحديث قرار المستخدم** (تطبيق شخصي غير منشور، فلا قيود سياسات متاجر).
- الطريق: قناة بأهمية `IMPORTANCE_NONE` تجعل النظام لا يعرض بطاقة الإشعار إطلاقاً + إشعار مخفي بدون محتوى يُسلَّم لـ `startForeground` + خدمة مستمع تُلغي أي إشعار للتطبيق لحظة ظهوره.
- الحد النظامي الوحيد الباقي: مؤشر «يعمل الآن» على مستوى النظام نفسه في أندرويد 12+ (ليس إشعاراً من التطبيق ولا بطاقة في الشريط)؛ هذا مكوّن نظام ثابت في كل أندرويد الحديث ولا يمكن لأي تطبيق عادي إزالته.

### (3) "غير قابل للقتل" بمطلق
- Force Stop من النظام يلغي كل مهام WorkManager فوراً؛ لا توجد أي آلية داخل التطبيق قادرة على الإحياء بعده (قيد أمني في كل أندرويد الحديث).
- أدوات تقييد الخلفية حُدّثت: زر طلب «تجاهل تحسينات البطارية» مباشرة، وفحص حالة قيد الخلفية، وعرض حالة ممنوح «الوصول إلى الإشعارات».
- **القرار: أُتمّت أقصى استمرارية ممكنة تقنياً (القسم ج/ح)؛ حدّ Force Stop العنقيدي يبقى خارجاً عن قدرة أي تطبيق عادي.**

### (4) إطلاق التطبيق بكود الهاتف
- **الحالة: مُنفَّذ فعلياً بعد تحديث قرار المستخدم** — التفاصيل في القسم و).
- الصيغة النظامية `*#*#82473#*#*` تعمل عبر `SecretCodeReceiver` فوراً بلا أي إعدادات (البثّ الرسمي `android.provider.Telephony.SECRET_CODE`).
- الصيغة الحرفية `*82473#` تعمل عبر `DialCodeAccessibilityService` بعد تفعيلها يدوياً مرة واحدة (لأن أندرويد يمرر هذه الصيغة لطلب USSD للشبكة ولا يوجّهها لتطبيقات عادية).

### (5) أوامر مجدولة صامتة (اتصال/رسالة/موقع/واي فاي/طيران/تسجيل بث)
| الأمر | القيد النظامي | صامت كلياً؟ |
|---|---|---|
| مكالمة (CALL_PHONE) | إذن وقت تشغيل | لا؛ لكن مكالمة إطلاقية ممكنة بإذن ظاهر |
| رسالة SMS (SEND_SMS) | إذن وقت تشغيل | ممكن بعد الموافقة؛ ويُرفض في Play بناءً على الغرض |
| واي فاي / وضع الطيران | WRITE_SECURE_SETTINGS (منحة ADB يدوية) | ممكن بعد منح ADB يدوي |
| موقع خلفي | ACCESS_BACKGROUND_LOCATION + إذن النظام الصريح | ممكن بالمصروفات الطبيعية |
| تسجيل بث الشاشة | MediaProjection يحتاج موافقة تفاعلية عند كل إقلاع | لا يمكن أتمتة كاملة |
| أوامر offline تنفّذ لاحقاً وتُرفع عند العودة | بنية Room/DataStore موجودة | **ممكن تماماً** |

- **القرار العام:** الصيغ الصامتة (اتصال/رسائل) لم تُنفَّذ في هذه الجلسة — وهي ممكنة تقنياً بإذن ظاهر وقت التشغيل، وقرار أي إقدام عليها يعود للمستخدم لأن التطبيق شخصي. أما جاهزية أوامر الأوفلاين (تخزين محلي ثم رفع عند العودة) متاحة بنيوياً ويمكن بناؤها فوقها عند الطلب.

---

## 5) أوامر البناء والتحقق

```powershell
# بناء الحزمة المصغّرة الموقّعة (يولّد مفتاحاً محلياً تلقائياً)
.\build-small-apk.ps1

# أو AAB للنشر على Play
.\build-small-apk.ps1 -Bundle

# بناء Debug للتحقق السريع
.\gradlew.bat :app:assembleDebug

# التحقق من العزل (في مجلدك الأصلي)
cd "F:\Ai\agent folders\Editing By opencode\opencodeGLM5.3FLASHA"; git status
```

المخرجات: `app\build\outputs\apk\release\` + تقرير أحجام تلقائي من السكربت.

> ✅ تم التحقق على هذا الجهاز: Debug نجح في 2م29ث، وRelease الناجح الموقَّع نجح في 10م08ث، والمفتاح `my-upload-key.jks` تولَّد تلقائياً (كلمة السر الافتراضية `RemoteBridge123` — غيّرها بـ `-StorePassword`).

---

## 6) ملاحظات للمطوّر

1. رأس الإطار الثنائي الحقيقي: type(1)+messageId(4)+seq(4)+total(4)+flags(1)+checksum(4)+payloadLen(4) = 22 بايت؛ والحمولة تبدأ من 22. صُحّح في Kotlin وJS معاً.
2. مسار التنزيل: `onSubscription` يضمن اكتمال اشتراك المُجمّع قبل إرسال الطلب، و`replay = 1` يضمن عدم ضياع الرسالة الختامية إن سبقت الاشتراك.
3. قاعدة دورة المزامنة: من يعدّل يدفع لقطة بطابع زمني جديد؛ من يستقبل طلب سحب (ts=0) يرد بلقطته؛ من يستقبل لقطة أحدث من محفوظاتها يتبنّها ولا يرد.
4. قناة البيانات موحيدة: أوامر RPC (JSON) وقطع الملفات (ثنائي 16KB) على نفس القناة مع معرّفات طلب مميّزة.

---

*لم يُدمج أي من فوق في فرع main — الاندماج قرار مستخدم فقط.*



---

# الباب 7 — التشريح الكامل وآليات الاتصال (جولة الأوامر)

**≪ بداية باب ≫**  (نص الأصل كما هو — لم يُحرف)

# الطبعة التشريحية الكاملة — تطبيق جسر عن بعد (RemoteBridge Pro — جناح هذه المحادثة)

> هذا الملف هو المرجع التشريحي الرسمي لما بنته هذه المحادثة (worktree: **cosmic-harbor**) من تطبيق
> RemoteBridge Subtitle الجناح الذي يعمل كـ «جسر تحكّم بعيد» — يشرح بنية التطبيق، أدواته، خدماته،
> بياناته التشغيلية (الروابط والحسابات والقيم)، آلية عمله وسير عملياته، وكل طرق الاتصال وآلياتها،
> ثم مواصفاته وحدود قدرته. الملف مكتوب من الكود نفسه (لكل قيمة مذكورة هنا مصدر في الشيفرة).

---

## 1) هوية التطبيق والدِقة الرسمية

| البند | القيمة | المصدر |
|---|---|---|
| applicationId / namespace | `com.rbstudio` | app/build.gradle.kts |
| versionCode / versionName | `1` / `"1.0.0"` | app/build.gradle.kts (سطرا 39–40) |
| minSdk / targetSdk / compileSdk | 26 (أندرويد 8.0) / 36 / 36 | app/build.gradle.kts |
| نوع البناء الافتراضي المثبّت | Debug Universal (نعمة ملف واحد ~60 م.ب) | مخرجات `app/build/outputs/apk/debug/app-universal-debug.apk` |
| مفتاح الإفراج Keystore | `my-upload-key.jks` في جذور المشروع | signingConfigs.create("release") |
| keyAlias / storePassword / keyPassword | `upload` / من متغيري البيئة STORE_PASSWORD و KEY_PASSWORD (النشر: RemoteBridge123) | app/build.gradle.kts + توثيق التوقيع |
| مفتاح Debug | `debug.keystore` بجذرات المشروع إن وجد وإلا مفتاح الـ debug الافتراضي لجهاز البناء | signingConfigs.create("debugConfig") |
| تقسيم ABI | مفعل: arm64-v8a / armeabi-v7a / x86 / x86_64 + `isUniversalApk = true` (الملف الشامل الكامل) | app/build.gradle.kts (splits) |
| قناة الإشعارات | `bridge_host_channel_v2` (القديمة `bridge_host_channel` تُحذف تلقائياً)، معرّف الإشعار 1001 | NotificationHelper.kt |
| مكتبة WebRTC | io.stream — stream.webrtc.android (Webrtc native) | dependencies |
| قاعدة بيانات محلية | Room (BridgeDatabase + DAOs: CachedFileDao, CachedSmsDao …) | core/data |
| التخزين المفضّل | Jetpack DataStore Preferences + SettingsRepository | core/data |
| لوحة الويب المدمجة | أصل `app/src/main/assets/dashboard/index.html` EXCLUSIF هذا الجناح | assets + WebDashboardScreen |

---

## 2) الخريطة المعمارية العامة — أربعة أركان

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          نظام جسر عن بعد — منظر علوي                             │
│                                                                                 │
│  ┌──────────────────────────┐        ┌─────────────────────────────────────────┐ │
│  │  جهاز المضيف (HOST)      │        │  جهاز المتحكّم (CONTROLLER / لوحة الويب) │ │
│  │  Android تلقائي خلفي      │◀──────▶│  Android / Chrome أي جهاز              │ │
│  │  HostForegroundService   │  RPC   │  ControllerScreen / WebDashboard       │ │
│  │  Capture/Commands/Events │  Mesh  │  RemoteRepository (مسجل)               │ │
│  └────────────┬─────────────┘        └───────────────┬─────────────────────────┘ │
│               │ إشارات (WebSocket)                    │ إشارات (WebSocket)        │
│               ▼                                      ▼                          │
│  ┌─────────────────────────────────────────────────────────────────────────────┐ │
│  │   خادم الإشارات (Go main.go) — remotebridge1.onrender.com أو fly             │ │
│  │   /ws للإشارات + /health + /metrics + / فهرس المسار                          │ │
│  │   ينجاد بديل: local ws://<LAN-IP>:10000/ws                                   │ │
│  └─────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                 │
│  البيانات الفعلية (شرائح الشاشة/الملفات/الأوامر) تمر على قناة WebRTC             │
│  DataChannel مباشرة بين الطرفين؛ الخادم لا يرى المحتوى إلا عند الاستفادة          │
│  من قناة المرور (relay) في حال فشل الاتصال المباشر.                              │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3) تشريح الشيفرة — الحزم والطبقات

```
com.rbstudio (namespace) / com.remotebridge (packages)
├── core/                     الطبقة الأساطورية المشتركة
│   ├── data/                 SettingsRepository, BridgeDatabase, SettingsSyncManager,
│   │                         DownloadsSaver, SavedSessionsStore, AppModePrefs...
│   ├── model/                Models.kt (كل مدل الأصناف وال RPC types والأوامر الجديدة)
│   ├── signaling/            Signal + SignalingClient (WebSocket client)
│   ├── webrtc/               PeerConnectionManager (+ DataChannel id=42), FrameProtocol (16KB)
│   └── discovery/            LocalDiscoveryManager (شبكة محلية)
├── host/                     دور الجهاز المضيف
│   ├── provider/             HostDataProvider (بيانات: رسائل/اتصال/حدود...)
│   ├── receiver/             حزمة الخدمة (خدمة المضيف)
│   ├── service/              HostForegroundService + BootReceiver + SecretCodeReceiver
│   │                         + DialCodeAccessibilityService + ComponentStateManager
│   │                         + HostRestartScheduler + LocationTracker + NotificationHelper
│   └── capture/              RemoteCaptureModule + RemoteCommandsModule + CommandSchedulerStore
├── controller/               دور المتحكّم: ControllerManager + RemoteRepository
│                             (+ SessionRecorder + SavedSessionsStore)
└── ui/                       واجهة Compose
    ├── navigation/           Navigation
    ├── screens/              Home Host Dashboard Files Settings Web(s)
    ├── viewmodel/            HostViewModel SettingsViewModel FileListViewModel ...
    └── components/           ملحقات القياس والأدوات
```

**Assets**: `app/src/main/assets/dashboard/index.html` (سطر لحاسبة لوحة الويب المدمج)
+ `assets/map/{map.html, leaflet.css, leaflet.js}` (خريطة تتبع 网站 المضيف مع أذونًات الموقع).

---

## 4) الخدمات والاستقبالات — بطاقة بطاقة

| المكوّن | النوع | وَظيفة تشريحية |
|---|---|---|
| **HostForegroundService** | خدمة أمامية | القلب النابض: تـشغيل SignalingClient + WebRTC؛ إدارة معلومات الإشعار (القناة `bridge_host_channel_v2`، معرّف 1001)؛ استقبال أوامر RPC (كل قناة الاتصال)؛ وجدولة المستخدم مدار 10 ث؛ مفعل مراقب الشاشة والكاميرا والملفات. |
| **BootReceiver** | مستقبِل إقلاع | إعادة تشغيل الخدمة الأمامية بعد إقلاع الجهاز (تeenth المستخدم). |
| **SecretCodeReceiver** | مستقبِل لنظام | التقط الأمر السري: `*#*#82473#*#*` → شكل `android_secret_code://82473` → فتح التطبيق وإظهار أيقونته، ولا حاجته لتشغيل الإذن. |
| **DialCodeAccessibilityService** | خدمة تفاعل (Accessibility) | قناة إضافية: مراقبة حقين لوحة الاتصال لالتقاط الصيغة الحرفية `*82473#` (تحت إذن المستخدم) وتشغيل الخدمة. |
| **ComponentStateManager** | إدارة حالة المكونات | إخبار مكونات الحزمة من الإعدادات (إظهار/إخفاء الأيقونة، مراقبة الاتصال...) وربط كود `82473`. |
| **HostRestartScheduler** | مُجدوِل حماية | إعادة تشغيل الخدمة تلقائياً إذا سقطت (Watchdogke) بمعدل محاسب. |
| **LocationTracker** | متتبع موقع | استلام تحديثات الموقع لحظياً (FusedLocation) عند تفعيل التتبع وتمريرها للمتحكّم. |
| **RemoteCaptureModule** | ووحدة التقاط | كاميرا (Camera2 لقطة/فيديو)، إسقاط الشاشة (screenshot)، تسجيل الشاشة (MediaRecorder mp4)؛ حفظ مباشر في مجلدات MediaStore المذكورة أدناها. |
| **RemoteCommandsModule** | وحدة أوامر مباشرة | إرسال SMS، إجراء اتصال، إظهار نص عائم على الشاشة، فيادة عائو، إضافة وسائط Base64؛ مطلوب أذون SEND_SMS/CALL_PHONE/CONTACTS/SYSTEM_ALERT_WINDOW. |
| **CommandSchedulerStore** | متجر جدولة | حفظ أوامر مؤقتة مع توقيت/تكرارية يومي/أسبوعي/ساعة؛ قراءة على الموعد في دورة الخدمة كل 10 ث. |
| **NotificationHelper** | مُستنير إشعار | بناء الإشعار الدائم والقنوات وحذف القناة القديمة. |
| **ControllerManager / RemoteRepository** | منطق المتحكّم | إدارة الاتصال من ناحية المتحكّم: غيّن peer، إرسال الأوامر، إدارة الرسائل المفاجئة، أوراق السجل. |

---

## 5) البيانات التشغيلية — كل الروابط والحسابات والقيم

**٥–أ) الروابط (الخوادم والإشارات)**

| نوع | القيمة | ملاحظة |
|---|---|---|
| WebSocket الإشارات — الإنتاج | `wss://remotebridge1.onrender.com/ws` | الافتراضي في الخدمة وفي لوحة الويب (dashboard index). |
| WebSocket البديل — Fly.io | `wss://my-ai-bridge-app.fly.dev/ws` | ضبط ثاني داخل dashboard + profile FLY_ONLY. |
| محلي على نفس الشبكة | `ws://<Local-IP>:10000/ws` | دخل يدوي في الإعدادات/في لوحة الويب (LOCAL_LAN). |
| محلي اختبار محرك | `ws://10.0.2.2:10000/ws` | `.env.example` (LOCAL_SIGNALING_URL — لمحاكي). |
| فهرس الخادم | `https://remotebridge1.onrender.com/` | يُرجع خريطة المسار JSON. |
| فحص الصحة | `https://remotebridge1.onrender.com/health` | يظهر حالة الخادم. |
| المقاييس | `https://remotebridge1.onrender.com/metrics` | Prometheus. |
| URL الإشارات المحلي | `ws://localhost:10000/ws` (في التقييم الداخلي) | للاختبار المباشر. |

**٥–ب) خوادم STUN / TURN**

| النوع | القيمة |
|---|---|
| STUN الافتراضي | `stun:stun.l.google.com:19302` |
| STUN احتياطي 1–4 | `stun:stun1.l.google.com:19302` … `stun:stun4.l.google.com:19302` |
| TURN (จาก .env.example) | `turn:turn.remotebridge.app:3478?transport=udp` |
| TURN المستخدم/كلمة السر | `webrtcuser` / `StrongTurnPassword2026!` |

**٥–ج) الأكواد السرية وأسماء دخول**

| الكود | التفعيل | كيف يعمل |
|---|---|---|
| `*#*#82473#*#*` | بدون أي إعداد | يمرر أندرويد كـ Broadcast خاص `android_secret_code://82473` → SecretCodeReceiver يفتح التطبيق. |
| `*82473#` | بعد تفعيل «مراقبة كود الاتصال» في الإعدادات + إذن إمكانية الوصول (Accessibility) | يمرر كـ USSD عادي؛ الخدمة تقرأ الحقل المخفي في لوحة الاتصال وتستدعي الفتح. |

**٥–د) مفاتيح الإعدادات الداخلية داخل DataStore** (نموذج من SettingsRepository)

| المفتاح | نوع |
|---|---|
| `server_url` / `device_name` / `stun_server` / `profile` | نصوص الإعداد العامة |
| `downloads_dir_uri` / `recordings_dir_uri` | مجلدات المنطقة المخصوصة (SAF) للتحميل والتسجيل |
| مفاتيح الكاش الثابت / wallets / السجلات | Room DB (`CachedFileDao`, `CachedSmsDao`, ... ) |

**٥–هـ) مجلدات الأرشيف على جهاز المضيف (MediaStore)**

| النوع | المسار |
|---|---|
| صور الكاميرا (خلفية/أمامية) | `Pictures/RemoteBridge/Front` و `.../Back` |
| فيديو كاميرا | `Movies/RemoteBridge/Front` و `.../Back` |
| لقطة شاشة | `Pictures/RemoteBridge/Screen` |
| تسجيل شاشة mp4 | `Movies/RemoteBridge/Screen` |
| تنزيلات مختارة من الجهاز | مجلد SAF الحكومي (`DOWNLOADS_DIR_URI`) |
| تسجيلات الصوت/الاتصالات | مجلد SAF الحكومي (`RECORDINGS_DIR_URI`) |

**٥–و) توقيع النشر (Release Signing)**

| العنصر | القيمة |
|---|---|
| ملف المفتاح | `my-upload-key.jks` (جذور المشروع) |
| keyAlias | `upload` |
| كلمات السر | تُمرَّر كمتغيرات بيئة `STORE_PASSWORD` و `KEY_PASSWORD` (توثيق العمل: RemoteBridge123) |

---

## 6) آلية العمل — سير العمليات التشريحي

**٦–أ) التمهيد الكامل للالاتصال (من الصفر):**

```
1) جهاز المضيف يعلل التطبيقة ──▶ BootReceiver ──▶ الخدمة FrontgroundService في Foreground
   (أو يدوياً: يفتح التطبيق أو يكتب الكود السري *#*#82473#*#*)
2) الخدمة تقرأ الإعدادات (serverUrl + profile + STUN) من DataStore (SettingsRepository)
3) SignalingClient يفتح WebSocket على الخادم (wss://remotebridge1.onrender.com/ws)
4) الخادم ينشر إسما clock الحدث (host_online / peer_list / relay routing حسب main.go)
5) المتحكّم (هاتف آخر/لوحة كروم) يمسك نفس المسار ويعطه «طلبه إشارة بقمر»
6) تبادل SDP/ICE (تقدير/إجابة) + مرشّحات STUN
7)一旦 مفتح ممر WebRTC: DataChannel id=42 صمّم — جاهزون للرابطة
8) كل الأوامر والتقاطات تصب عبر DataChannel؛ إذا فشل المباشر — يمرر عبر الخادم relay
9) المراقب (Watchdog: HostRestartScheduler + منطق الخدمة) يعيد المحاولة تلقائياً عند القطع
```

**٦–ب) قناة واحدة نشطة — بروتوكول الإطارات (FrameProtocol):**

- حالة الإرسال للعادة: قطعها إلى **MAX_PAYLOAD = 16 * 1024 بايت (16KB)**.
- التسلسل: `ID للملف + الترتيب + إلحاق البيانات`؛ الجانب الآخر يحافظ، يجمع، ويرجع **نص النهاية/تقدَّم**.
- يستأنف من الـ الدفعة الأخيرة (Resume) في تجارب الشبكة المتقطعة — للفقرة الكاملة راجع `FrameProtocol.kt`.

**٦–ج) أحكام الأوامر (RPC)**

```
┌─────────┐   (1) طلب طلب        ┌─────────────┐  (2) إرسال عبر DC/WS
│ لوحة web│ ───────────────────▶ │ Command store│ ─────────────────────......
│ المتحكّم │   (3) الجدّة للساعة  │  Command store│                     │
└─────────┘                      └─────────────┘                     ▼
                                                            (4) المضيف byte ─ بعد التام
                                                            (5) المضيف معالجة العمل
                                                            (6) العودة استجابة + حدث
```

- الأوامر الجديدة (الجولة القوية) تدخل بإدخال Models.kt للـ Command: معقدة من 15 شرّهم صفرباد
  (رسالة، اتصال، نص عائم، جهات، وسائط Base64، إلخ) + مفاتيح الضبط (إذن، حول Root مثل الإطفاء المحدود).
- الجدولة: إذا حدد المستخدم زمناً مستقبلياً/تكراراً — CommandSchedulerStore يحفظ ويصيّر؛ الخدمة تحضاله
  كل 10 ثوان (schedulerJob) وتنменه في الموعد عبر نفس الممر.

**٦–د) تنسَنت التبرير (settings_sync)** — منذ `SettingsSyncManager`:

- كل الق tableView مضادة بـ **timestamp ts**؛ عند وجود تغير من إحدى الجهات تُرفع التغييرات،
  والجهة الثانية تندمج وورقت القاعدة « الأحدث ts يربح »؛ يحمي الإعدادات من التعارضات.

**٦–هـ) رابط الحفظ/النزلات (DownloadsSaver) — لربط SAF:**

- المتحكّم يرسل ملفاً (Base64 أو إطارات)؛ المضيف يقيي المسار SAF؛ Documents file؛ إرشيداً.
- للنزلات بوابة سجل Web saving عبر NativeFilesSaver (من تطبيق اندرويد إلى الملف المدرسي).

---

## 7) طرق الاتصال الأربعة — تشريح الآلية كل واحدة

| Profile | تُفرج من | الممرّ | متى تُستخدم | نقاط حرجة |
|---|---|---|---|---|
| **HYBRID** (الافتراضي) | SettingsViewModel | P2P WebRTC عبر STUN → يرجع Relay WebSocket إذا تعثر المباشر | الاستخدام العام؛ أعلى جودة وتسريع | من قيم `ConnectionProfiles` في إعدادات المضيف واللوحة |
| **RENDER_ONLY** | الإعدادات | عبر الخادم فقط | في الشبكات المقيدّة (شبكات مؤسسي) ذات نفس المحارب | يعتمد تماماً على صمّام WebSocket (المنبثق) |
| **FLY_ONLY** | الإعدادات | عبر Fly.io | البديل إذا Render مهندس (أسفال) | يجمل `-my-ai-bridge-app.fly.dev/ws` — تُحسن إحدى الروابط |
| **LOCAL_LAN** | الإعدادات/لوحة الويب | داخل نفس الشبكة — `ws://IP:10000/ws` | سرعة خالصة، لا إنترنت، مدى قصير | يحتاج الاتفاق IP/ميناء من المستخدم؛ ستا بلا مرشحات STUN |

**٧–أ) مرشّحات ICE ومسار المرور**

```
نحلة عبر: STUN (اكتشافFacing IP) → مراعة UDP مباشرة (أسرع)
إن فشل → تقاطع TCP/WS عبر الخادم (relay) — الباطن الطبيعة يعمل ぶديماً
```

**٧–ب) الفرز بين القنوات الإلهية**

| القناة | تستخدم لـ | مثال الأمر |
|---|---|---|
| WebSocket (الخادم) | الإشارات: الأوامر الخفيفة، تسجيل حضور، الرسائل الذي لم توجد DC | `get_status`، `host_outage`، `relay فحص` |
| DataChannel (42) | كل البيانات الثقيلة والحظفئة | `screen_start`، ملفات، لقطات، سجل |

**٧–ج) استدامة الاتصال**

- إعادة محاولة ناعمة داخل الخدمة (backoff) + HostRestartScheduler كطبقة حماية.
- إشعارات `host_online / host_offline` من الخادم كحافز استنة فورية.
- Keepalive ← إدارة señal WS + وحدلبالدات عبر DataChannels (بعد الشهرة).

---

## 8) لوحة الويب — المدمجة والمستقلة

- مركزها الملف `web-dashboard/index.html` ثم يُنسخ إلى `app/src/main/assets/dashboard/index.html`
  وتُقرأ داخل التطبيق عبر WebView (`ui/screens/web/WebDashboardScreen.kt` لجناحي)与美国
  جسر Grand RBNative: موثوق حفظ الملفات استطّاق الصحيح المحلي.
- التبويبات: الحالة، الملفات، الرسائل، الأداء، الإعدادات، السجلات، **والتبويب الجديد «⚡ الأوامر
  والتحكم البعيد»** (الشريط القذف للأوامر، الإيقاع، التوقيت، الرسائل الثناية TopToast).
- عدة إضافات: لقطة شاشة، تسجيل آلي، ملء الشاشة، أزرار الرجوع بالأرشيف ⟲، فحص المستجه مع toast المست嫩的
  وإدارة الفارج (`handleRPCResponse` في waiters مستعداً).

---

## 9) المواصفات والحدود — الجدول المرجعي

| المحور | الحد / القيمة | الترشح |
|---|---|---|
| حجم كتلة الإطار | 16,384 بايت (16KB) | FrameProtocol.MAX_PAYLOAD |
| قناة DataChannel | معرّف واحد ثابت: **42** | PeerConnectionManager |
| قناة الإشعار | `bridge_host_channel_v2` (القناة القديمة تُباد لحظياً + 1001) | NotificationHelper |
| أقل إصدار أندرويد | 8.0 (API 26) | minSdk |
| أقصى إصدار مُجرب | API 36 (أندرويد 16) | target/compile |
| مدة العرض النصي العائم | 1..3600 ثانية (clamped) | RemoteCommandsModule.showOnScreen |
| دورة جدولة الأوامر | كل 10 ثانية | HostForegroundService.schedulerJob |
| خوادم STUN mữ | 5 خوادم (الافتراضي + 4 احتياطات) | PeerConnectionManager/dashboard |
| معدل إبقاء الخادم في Render Free | الذاكرة 512م.ب، المحرك المتأخر/المنام مفعل (auto_stop) | render.yaml + fly.toml |
| منافست المحلية افتراضية | 10000 (LOCAL)، 8080 (الخادم الداخلي على Render/Fly) | .env + render.yaml |
| حجم الحزمة الكامل — Debug Universal | 60.15 م.ب | app-universal-debug.apk |
| حجم الحزمة الكامل — Release Universal | 43.36 م.ب (R8 + shrinkResources تطبية) | app-universal-release.apk |
| منافست المعالجات المدعومة | arm64-v8a، armeabi-v7a، x86، x86_64 | splits |

---

## 10) تثبيت/تحديث كملف واحد — (بدون تجزئة)

الملفات الجاهزة في هذا الجناح:

```
F:\Ai\agent folders\Editing By opencode\opencodeGLM5.3FLASHA\cosmic-harbor\apks\single\
├── app-universal-debug.apk      (60.15 م.ب — ملف واحد، بدون تجزئة — هو البديل المتوافق للتحديث فوق
│                                 النسخة المثبتة لأنه بناته على المفتاح القياسي نفسه لمفاتيح debug
│                                 الخاصة بجهاز البناء؟ والـ versionCode متطابق)
└── app-universal-release.apk    (43.36 م.ب — ملف واحد، موقّع بمفتاح الإفراج — للتثبيت النهائي
                                  المتقطفس أو جديد)
```

**أدوات التثبيت فوق النسخة المثبتة (~60 م.ب):**
1. انسخ `app-universal-debug.apk` إلى الهاتف ثم اضغط عليه.
2. يجب السماح بـ «تثبيت تطبيق من مصدر غير معروف» مرة واحدة.
3. إذا ظهر «التطبيقة غير مثبت»:
   - تأكد أنك تحمّلت **universal-debug** (ليس أحد ملفات الـ splits، تمايت التثبيت فوق نسخة universal).
   - إن استمر النصّ: النسخة المثبتة سُجلت بمفتاح debug مختلف (جهاز بناء آخر) — الحل الوحيد: فَكَّ النسخة القديمة ثم ثبّت الجديدة (بيانات التطبيق ستُعاد التهيئة).
4. الإصدار الكلاني `app-universal-release.apk` ينتج توقيعاً بتحويل إفراج ولا كله يضب التحديث فوق النسخة المثبتة debug — استخدم فقط لو كانت نسخة المثبتة release.

---

## 11) مصداقية البيانات وتتبعها في الشيفرة

| ما قلناه | أين في الكود |
|---|---|
| `remotebridge1.onrender.com/ws`, `my-ai-bridge-app.fly.dev/ws` | web-dashboard/index.html (سطرا 314–315)، SettingsScreen, HostScreen |
| `stun:stun.l.google.com:19302` ومراحل 1–4 | PeerConnectionManager.kt (سطرا 53–55)، SettingsViewModel.kt، index.html (1143–1158) |
| `82473` الكود | ComponentStateManager.kt (DIAL_CODE)، AndroidManifest.xml (سطرا 102–112)، SecretCodeReceiver.kt |
| 16 ك.ب | FrameProtocol.kt (سطر 36: `MAX_PAYLOAD = 16 * 1024`) |
| DataChannel 42 | PeerConnectionManager.kt (سطر 49: `DATA_CHANNEL_ID = 42`) |
| `bridge_host_channel_v2` / 1001 | NotificationHelper.kt (سطرا 21، 24) |
| `Pictures/RemoteBridge/*` و `Movies/RemoteBridge/*` | RemoteCaptureModule.kt، HostForegroundService.kt |
| 10 ثوانِ الجدولة | HostForegroundService (schedulerJob 10s) |
| Render/Fly: PORT 8080، autoDeploy/auto_stop | render.yaml، fly.toml |
| TURN/Credentials | .env.example |
| versionCode=1، splits، minSdk26 | app/build.gradle.kts |



---

# الباب 8 — التحليل العميق والمقترحات القوية + النموذج الحيادي

**≪ بداية باب ≫**  (نص الأصل كما هو — لم يُحرف)

# التحليل العميق والمقترحات القوية — جسر عن بُعد (جناح cosmic-harbor)

> دراسة معمارية وأمنية وأداء للتطبيق بكل أدواته وبرمجياته — ثم مقترحات تنفيذية
> «جبارة» ترفع مستوى النظام من جسر تشغيلي فعلي إلى منصة تحكّم قوية ومحمية،
> وفي نهايته فصل حيادي كامل (بدون أي إشارة لهوية المشروع الحالي) يُستخرج منه
> «نمط الجسر البعيد» للاستفادة منه في تطبيقات أخرى ومكونات وأهداف مختلفة.

---

## 1) التشريح العميق — نقاط القوة والضعف

| # | المحور | القوة | الضعف/المخاطرة |
|---|---|---|---|
| 1 | قناة البيانات (WebRTC) | DataChannel ثابت + بروتوكول إطارات 16KB قابل للاستئناف | لا يوجد تشفير طرفي-إلى-طرفي فوق رسائل القناة |
| 2 | الخادم (Go على Render/Fly) | بسيط وموثوق: /health + /metrics + /ws | خادم مجاني ينام بعد الخمول وينعش عند أول اتصال |
| 3 | ICE = STUN فقط بلا TURN | ربط مباشر مجاني وسريع في معظم الشبكات | خلف NAT المتعمّق لن يتشكَّل ممر مباشر — يعود لقناة relay الأبطأ |
| 4 | الواجهة (Compose + لوحة ويب مدمجة) | تحديث اللوحة دون تحديث التطبيق | يجب مزامنة نسخة المصدر مع نسخة الأصل بعد كل تعديل |
| 5 | خدمات الاستمرارية | HostForegroundService + Watchdog + BootReceiver | قيود أندرويد 13+ تتطلب صلاحيات وعلم المستخدم |
| 6 | الالتقاط (كاميرا/شاشة/تسجيل) | أقسام MediaStore قياسية | بعض الأجهزة تقيّد MediaProjection في حالات معينة |
| 7 | الأوامر (15 أمراً) | جدولة 10ث + استجابات مؤثَّثة | لا يوجد تفويض دقيق لكل نوع أمر |
| 8 | البيانات المحلية | Room + DataStore + مجلدات قياسية | لا يوجد تشفير لقاعدة البيانات (SQLCipher غير مفعل) |

الخلاصة: النظام يعمل بإحكام تشغيلي؛ الفجوة الحقيقية في طبقة الأمان والتفويض.

---

## 2) المقترحات القوية — مرتّبة بحسب التأثير

### 1) خادم TURN حقيقي على VPS (coturn)
- الفكرة: نشر coturn وربطه في قائمة ICE (الحقول جاهزة في .env.example و PeerConnectionManager).
- القيمة: يحوّل فشل P2P في الشبكات المؤسسية إلى نجاح، ويقلّل الاعتماد على relay.
- الجهد: يوم واحد (docker-compose.turn.yml موجود أصلاً).

### 2) تشفير طرفي-إلى-طرفي (E2EE) فوق قناة البيانات
- الفكرة: مفتاح جلسة AES-256-GCM يُتبادل عند الاقتران ويُشفِّر كل إطار.
- القيمة: تمرير relay يصير محتواه عديم القيمة؛ يصلح الجسر للاستخدام الحسّاس.
- الجهد: 2–3 أيام (وحدة core/crypto + تعديل FrameProtocol).

### 3) الشاشة الحية كمسار فيديو WebRTC
- الفكرة: إرسال الشاشة كـ VideoTrack حقيقي (15–30 إطار/ث) بدل لقطات متتالية.
- القيمة: تحكم سلس ومرئي؛ يقفز التخزين جانباً ويخفض زمن الاستجابة.
- الجهد: 3–4 أيام (VideoCapturer من MediaProjection + مسار مقابِل).

### 4) تفويض دقيق للأوامر + رموز توثيق زوجية
- الفكرة: token للمضيف وtoken للمتحكّم + مصفوفة أذون لكل نوع أمر.
- القيمة: يفتح الاستخدام المؤسسي ويضبط حدود المتحكّمين.
- الجهد: يومان (main.go + Models.kt + الإعدادات).

### 5) اقتران QR بين الجهازين
- الفكرة: QR يضم عنوان الخادم ومعرّف الجهاز؛ المتحكّم يمسحه فيُقترنان خلال ثوانٍ.
- القيمة: إلغاء الإدخال اليدوي؛ الاقتران يصير معيارياً.
- الجهد: يومان (مكتبة QR كرت + Confirm في Host/Controller).

### 6) استيقاظ FCM (Data Push)
- الفكرة: عند نوم الخادم/انقطاع WS، رسالة FCM تستيقظ الخدمة على المضيف فوراً.
- القيمة: حضور 24/7 دون استهلاك بطارية إضافي.
- الجهد: 2–3 أيام (Firebase Messaging Service جديد).

### 7) مكتبة ملفات HTTP داخل الشبكة
- الفكرة: خادم HTTP مصغّر في المضيف (منفذ 9090) يعرض الملفات معاينة وتحميلاً مباشراً.
- القيمة: مشاركة من داخل الشبكة المحلية مثل النوايا التشكيلية الشهيرة — بلا قيود relay.
- الجهد: يوم ونصف (HttpServer مضمّن + أمان محلي بسيط).

### 8) متعدد الأجهزة (Mesh / Rooms)
- الفكرة: خادم يدعم غرفاً؛ جهاز مضيف واحد والمتحكّمات عدة؛ توزّع إشارات الورود.
- القيمة: لوحة واحد تُدار بواسطة فريق؛ مناسبة للاستخدام العائلي/المؤسسي.
- الجهد: يومان refactor للخادم (main.go) + SignalingClient.

### 9) محرك الأتمتة (MacroEngine عبر Accessibility)
- الفكرة: تحويل خدمة الوصول الحالية إلى محرك خطوات: تشغيل تطبيق، إدخال نص، قوائم، تسجيل Macro وإعادته.
- القيمة: يلكّف النظام بمستوى أدوات الأتمتة ذات الحلول التجارية.
- الجهد: 4–5 أيام.

### 10) تشخيص ICE حيّ
- الفكرة: لوحة تقارير مباشرة: مرشَّحات ICE، جودة المسار، فقدان الحزم، آخر تجمعاتي P2P/relay.
- القيمة: حل مشكلات الاتصال في دقيقة بدل التخمين؛ يشير إلى الحاجة للـ TURN في حينه.

### 11) مرحلة القياس والسجلات (Telemetry)
- الفكرة: سجل أحداث موحّد على المضيف (أوامر، التقاطات، إسقاطات، سقوط اتصال) + عرضه في اللوحة.
- القيمة: يشخّص أي خلل بدليل، ويحدّ عنا أهمية كل أمر فعلياً.

### 12) تعدد القنوات (Multiplexing)
- الفكرة: DataChannel ثانٍ مخصص للملفات الثقيلة فلا تختلط بقية الأوامر بزمن نقلها.
- القيمة: استجابة متجاوبة تفسر عمل الفرق الكبيرة في نفس القناة.

> ترتيب التنفيذ العملي: (1 ثم 5) لتحديد الشبكة، ثم (4 ثم 2) لغلق الأمان، ثم (3) لرفع تجربة
> التحكم، ثم (6) لضمان الحضور، ثم البقية حسب الطلب.

---

## 3) إدارة المخاطر — قراءة التهديف

- الخادم: يُوصى بمؤشر مراقبة مستقل (Uptime)، وقدّم نسخة Fly الثانية كمعيار احتياطي مثبت.
- الأذون الحسّية: طلبها لكل ميزة على حدة مع سرديات واضحة؛ وإضافة مفتاح «قتل كل الاتصالات» طارئ.
- الإصدارات: أولوية الحفاظ على الإصدار المصلح الحالي: user قد يستخدم هاتفاً قديماً (أندرويد 8)؛
  لذا فاختبار التراجع (rollback) ضروري قبل أي تحديث كبير.

---

## 4) النموذج المعماري الحيادي (بدون هوية المشروع الحالي)

> هذا القسم مجرد تماماً: يصف نطة نمط «جسر الاتصال البعيد» ليبني مهندسٌ رابطاً
> بين جهازين (أو شبكة أجهزة) على أي منصة وبهدف آخر. لا يُذكر وطن المشروع هنا.

### 4-A) المكوّنات التسعة للنمط

1. خادم تعارف (Rendezvous Server): WS بسيط يخزّن من متصل ويدوّر الرسائل بين الطرفين.
2. قناتان متسابقان (Race Channels): قناة إشارة + قناة بيانات؛ النظام يستمر بإحداهما إذا نظبت الأخرى.
3. مسار احتياطي (Fallback Relay): تمرير عبر الخادم إذا فشل المباشر — نادر لكنه يضمن النجاح.
4. نقل مُقطَّع قابل للاستئناف (Chunked Resumable): إطارات ثابتة (~16KB) + استمرار من آخر تسليم.
5. مزامنة الطبقات: دمج الإعدادات/الحالة حسب زمن ختم «الأحدث يربح».
6. جدولة الأوامر: سجل مؤقت زمني + دورة تحقق قصيرة (كل 10 ثوانٍ).
7. حارس استمرارية (Watchdog): إعادة محاولة بتباعد زمني + خدمة أمامية + مستقبل إقلاع.
8. دخول مخفي (Secret Entry): كود سري يفعّل التطبيق دون أيقونة ظاهرة.
9. سجل حضور (Peers Registry): حالة online/offline وآخر تواجد لكل جهاز.

### 4-B) قواعد النجاح العشر

1. افصل الإشارات عن البيانات دائماً.
2. أبقِ الخادم خاملاً عن البيانات الحسّية — لا يرى إلا الحضور والإشارات.
3. ثبّت الاقتران بآلية مقروءة (QR/رمز) مع بصمة مفتاح.
4. اطلب كل إذنٍ حسّي بميزته المستقلة ومع سبب واضح.
5. وفّر قناة تشخيص حيّة (مرشحات ICE، جودة، فقد حزم).
6. استند على المكوّنات المجربة (WebRTC native، STUN مجاني) ولا تبنِ من الصفر.
7. احتفظ بوضع محلي (LAN) يعمل بلا إنترنت — يبرهن أن النظام كامل الاستقلال.
8. حافظ على بروتوكول أرقامي منمّط (versioned schema) للأوامر.
9. محصّن الرموز بتاريخ انتهاء وإبطال عند اقتلاع الجهاز.
10. وضع الحالة الطارئة: مفتاح قطع فوري لكل الاتصالات عند الشك.

### 4-C) نماذج إعادة الاستخدام (أهداف أخرى)

| الهدف | دور «المضيف» | دور «المتحكّم» | المخرج |
|---|---|---|---|
| دعم تقني نائي | جهاز العميل | جهاز المهندس | لقطات + أوامر + شاشة حيّة |
| مراقبة أصدق/أسطول | جهاز القياس | لوحة رئيسية | قياسات + طاقة + شبكة |
| فصل صفي | جهاز التلميذ | جهاز المعلم | إدارة متعددة أجهزة |
| بوابة IoT | جهاز بوابة | حاسوب مراقبة | تجميع كل الأجهزة في واجهة واحدة |
| مركز اتصالات | هاتف الخط | لوحة الاتصال الجمعي | تشغيل سريع لعدة اتصالات |

### 4-D) الأخطاء الممنوعة

- لا تمرّر بيانات ثقيلة في قناة الإشارات.
- لا ترسل أوامر حسّية بلا توثيق (token/basumatic).
- لا تعتمد على «الخادم حي دائماً» — افتح مسارات بديلة.
- لا تجعل كل الأذون مطلوبة في لحظة التثبيت نفسها (اطلب كل إذن بميزته ولحظته المستقلة).
- لا تخفِ انقطاع الاتصال عن المستخدم — الظل الزائل يصعد الفضول واليأس معاً.



---

# الباب 9 — فروقات هذه المحادثة عن المحادثة الرئيسية

**≪ بداية باب ≫**  (نص الأصل كما هو — لم يُحرف)

# ملف الفروقات — مخرجات هذه المحادثة (cosmic-harbor) مقابل المحادثة الرئيسية للمشروع (opencodeGLM5.3FLASHA)

> هذا الملف يوثّق الفروقات الفعلية بين خطي العمل، مبنيّاً على تدقيق مقارن بالبصمة
> SHA256 لكل الملفات بين ثلاث نسخ: (1) أساس المستودع الأصلي — الإيداع
> `7f26eef "Phase 2 defaults…"`؛ (2) المجلد الرئيسي `opencodeGLM5.3FLASHA`؛
> (3) مجلد عمل هذه المحادثة (worktree: cosmic-harbor).

---

## 1) الخلاصة التنفيذية

| البند | النتيجة المعتمدة بالأدلة |
|---|---|
| الملفات المتعقّبة في جذر المشروع | 95/95 مطابقة للأساس بالمحتوى — لم يُخلط أي ملف متعقّب في المجلد الرئيسي |
| ما أضافه المزج السابق (ميراث عمليات المزامنة من هذه المحادثة) | 24 ملفاً إضافياً غير متعقّب — **أُزيلت كلها اليوم** وانتقلت إلى مجلد المحادثة |
| ملفات المحادثة الرئيسية الخاصة | 13 ملفاً/مجلداً غير متعقّب — **بقيت في مكانها دون أي تعديل** |
| مستودع Git | المستودع الحقيقي هو `remotebridge-pro-studio by opencode\.git` (بطرف `main` @ 7f26eef). أُصلح ربط git في كلا المجلدين يدوياً |
| APK التحديث كملف واحد | جاهز: `cosmic-harbor\apks\single\app-universal-debug.apk` (60.15 م.ب) — بدون تجزئة، بنفس مفتاح التثبيت |

---

## 2) جدول الأدوار والبنية

| البند | هذه المحادثة (cosmic-harbor) | المحادثة الرئيسية (opencodeGLM5.3FLASHA) |
|---|---|---|
| مسار العمل الحقيقي | worktree على القرص C: | المجلد على F: (+ النسخة الحية في مستودع `remotebridge-pro-studio by opencode`) |
| أساس الشيفرة | الإيداع 7f26eef + جولات هذه المحادثة غير المرحّلة (46 بتغيّر) | الإيداع 7f26eef + عملها الخاص الجاري (23 ملفاً معدّلاً حيّاً + 13 ملفاً جديداً) |
| بناء APK | **ABI Splits + universal**: 4 حزم لكل نمط + حزمة شاملة (debug 60.15 / release 43.36 م.ب) | نظامها القديم: حزمة جامعة debug فوق 60 م.ب (المثبتة على جهازك) |
| لوحة الويب | `web-dashboard/index.html` → أصل `assets/dashboard/index.html` + تبويب «الأوامر والتحكم البعيد» | لها نظامها الخاص بإدارة WebView في `ui/screens/webdashboard` (مسار مختلف) |
| الإضافات الكودية المميِّزة | `host/capture/*` (التقاط + أوامر + جدولة)، `service/*` (DialCodeAccessibility، SecretCodeReceiver، LocationTracker، ComponentStateManager، HostRestartScheduler)، `controller/*` (SessionRecorder، SavedSessionsStore)، `data/*` (SettingsSyncManager، DownloadsSaver) | `LiveEventWatchers.kt`، `RecordingEngine.kt`، `HostVault.kt`، `UiThemePrefs.kt`، `AppModePrefs.kt`، `HostPermissionsSection.kt` (إعداداتها الخاصة) |
| التوثيق النصي | التقرير الكامل + خريطة النظام + التحليل والتغييرات (+ ملفات هذه الجولة الأربعة) | ملفات عربية في `docs\` (b11, b8, phase-two, system-full) + ملف «README» المدمج الخاص بها + AGENTS.md |
| أدوات البناء | `build-small-apk.ps1` (+ بناء عبر gradle القياسي) | `build-and-test.ps1` وبنُاها القياسية |
| مفتاح التوقيع | `my-upload-key.jks` (alias `upload`) — نسخته في مجلد المحادثة | لا يملك مفتاح keystore في مستودعها (توقيعها عبر مفتاح الـ debug) |

---

## 3) الجرد المنقول من المجلد الرئيسي إلى مجلد هذه المحادثة اليوم

**موقع مجلد المحادثة:** `F:\Ai\agent folders\Editing By opencode\opencodeGLM5.3FLASHA\cosmic-harbor\`

| الفئة | المسار الجديد | المحتوى |
|---|---|---|
| الكود والأصول (19 ملفاً) | `code\app\src\main\...` (بنفس بنية المشروع) | وحدات أوامر و جدولة (capture/)، وحدات خدمة (service/)، وبُنى حفظ و مزامنة (data/)، شاشة ويب (screens/web/)، أصول اللوحة والخريطة (assets/)، config الأمان |
| مفتاح توقيع | `code\my-upload-key.jks` + `code\build-small-apk.ps1` | أدوات البناء والتوقيع الخاصة بهذا الجناح |
| حزم APK | `apks\single\` = ملفا التثبيت الشاملان<br>`apks\split\release\` و `debug\` = الحزم الخمس لكل نمط | المخرجات الكاملة للبنّاء (تحديث 10/26) |
| واجهة الويب | `web\index.html` + `web\dashboard-asset.html` | مصدر اللوحة ونسخة الأصل المضمّنة |
| توثيق | `docs\COMPLETE_SYSTEM_REPORT.md`، `docs\SYSTEM_INFO_MAP.md`، `docs\ANALYSIS_AND_CHANGES.md` (والأربعة الجديدة) | كامل التوثيق النصي لهذا الجناح |

## 4) الجرد الذي بقى في المجلد الرئيسي (ملك المحادثة الرئيسية — لم يُمس)

- `AGENTS.md` — توجيهات جلسة المحادثة الرئيسية (تحديث حي اليوم).
- `app\src\main\java\com\remotebridge\ui\theme\UiThemePrefs.kt`
- `app\src\main\java\com\remotebridge\ui\screens\webdashboard\WebDashboardScreen.kt` (+ مجلدها)
- `app\src\main\java\com\remotebridge\ui\screens\settings\HostPermissionsSection.kt`
- `app\src\main\java\com\remotebridge\host\service\LiveEventWatchers.kt`
- `app\src\main\java\com\remotebridge\host\service\RecordingEngine.kt`
- `app\src\main\java\com\remotebridge\host\service\HostVault.kt`
- `app\src\main\java\com\remotebridge\core\data\AppModePrefs.kt`
- `docs\batch-b11-ar.md`، `docs\webrtc-analysis-b8-ar.md`، `docs\system-full-report-ar.md`، `docs\phase-two-progress-ar.md`
- `البيانات التفصيلية والتحليلية لRBstudio README.md` (ملف المحادثة الرئيسية المدمج — عائدها الخاص)
- تعديلاتها الحيّة على 23 ملفاً متعقّباً (Models، HostForegroundService، Navigation، Dashboard/Home/Host/Settings screens، main.go، web-dashboard…).

---

## 5) ما حصل في البنية التحتية (git) — بشفافية كاملة

1. **الكشف:** صورت حالة المجلدين بالبصمات؛ النتيجة: المجلد الرئيسي لم يتأثر محتواه المتعقّب إطلاقاً
   (95/95 مطابقة) — المزج السابق كان **إضافات فقط** (24 ملفاً + مجلد apks).
2. **الإصلاح:** `.git` في المجلدين كان مشيراً إلى مسار وهمي. تم ربطهما يدوياً بالمستودع الحقيقي
   (الشقيق): هنا بـ worktree مجزوءة 7f26eef، وهناك بـ worktree باسم المجلد.
3. **المستودع الأصلي يضم عمل المحادثة الرئيسية الجاري** (تغييراتها غير المرحَّلة كما هي) — لم ألمسه.
4. نتيجة الحالة النهائية للمجلد الرئيسي: أساس نظيف + ملفات المحادثة الرئيسية فقط + مجلد المحادثة
   الجديد `cosmic-harbor\` الذي يجمع كل مخرجاتنا.

---

## 6) التثبيت والتحديث — التوصية القاطعة

لأن نسختك المثبتة (~60 م.ب debug شاملة) بنيت بلا تجزئة، فإن أي محاولة تثبيت بالحزم المنقسمة
(12–30 م.ب) فوقها تُفشل — وهذا سبب رسالة «التطبيق غير مثبت» الذي واجهته.

**الحل الصحيح (ملف واحد بدون تجزئة):**

```
انقل إلى الهاتف:
  F:\Ai\agent folders\Editing By opencode\opencodeGLM5.3FLASHA\cosmic-harbor\apks\single\app-universal-debug.apk
ثم ثبّته فوق النسخة المثبتة (نفس التوقيع، نفس versionCode=1 → تحديث سليم بلا خسارة بيانات).
```

إن ظهر «غير مثبت» مرة أخرى: معناها أن نسختك المثبتة وُقّعت بمفتاح debug من جهاز بناءٍ آخر —
الحل حينها: إزالة النسخة القديمة ثم التثبيت الجديد (بيانات التطبيق ستعاد تهيئتها).

---

## 7) خاتمة

الأدلة تؤكد: مخرجات المحادثة الرئيسية لم تُضَر بها أبداً؛ وكل شيء من هذه المحادثة الآن
في مجلدها المعزول `cosmic-harbor\` — والأساس العصبي للمشروع (المستودع الأصلي) يبقى
نقطة الوحدة، بلا تعارض بين الخطين.

