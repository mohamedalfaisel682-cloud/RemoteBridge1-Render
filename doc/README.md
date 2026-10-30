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