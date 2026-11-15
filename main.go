package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
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
	ID          string          `json:"deviceId"`
	Role        string          `json:"role"`
	Name        string          `json:"deviceName"`
	Token       string          `json:"-"`
	Socket      *websocket.Conn `json:"-"`
	ConnectedAt time.Time       `json:"-"`
	// writeMu — كتابة واحدة لكل عميل في الوقت الواحد. Gorilla WebSocket لا يسمح
	// بكتابات متزامنة على نفس الاتصال؛ كانت البثوث (host_online/offline) والردود
	// المباشرة تتزاحم على المقبس نفسه فتفسد الإطارات وتقطع الاتصال.
	writeMu sync.Mutex
}

// send يرسل رسالة نصية واحدة بأمان على مقبس العميل.
func (c *Client) send(msg []byte) error {
	if c == nil || c.Socket == nil {
		return nil
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	return c.Socket.WriteMessage(websocket.TextMessage, msg)
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
		"locations_archive_list", "locations_archive_points",
		"device_info", "storage_buckets", "storage_explore", "file_list",
		"schedule_list", "stats", "statistics", "device_stats",
		"summary", "summaries", "commands", "commands_list", "system_info":
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

const upstashConfigPath = "cache_store/upstash_config.json"

var (
	upstashEnvURL    = strings.TrimRight(strings.TrimSpace(os.Getenv("UPSTASH_REDIS_REST_URL")), "/")
	upstashEnvToken  = strings.TrimSpace(os.Getenv("UPSTASH_REDIS_REST_TOKEN"))
	upstashMu        sync.RWMutex
	upstashRESTURL   = upstashEnvURL
	upstashRESTToken = upstashEnvToken
	upstashSource    = "none"
	cloudSyncMu      sync.Mutex
	cloudDirty       = true
	cloudSyncTimer   *time.Timer
	cloudStatusMu    sync.Mutex
	cloudLastSync    time.Time
	cloudLastError   string
	cloudLastAction  string
)

type upstashDiskConfig struct {
	URL     string `json:"url"`
	Token   string `json:"token"`
	SavedAt string `json:"savedAt"`
}

func upstashCreds() (string, string) {
	upstashMu.RLock()
	defer upstashMu.RUnlock()
	return upstashRESTURL, upstashRESTToken
}

func upstashSourceValue() string {
	upstashMu.RLock()
	defer upstashMu.RUnlock()
	return upstashSource
}

func setUpstashCreds(rawURL, token, source string) {
	upstashMu.Lock()
	upstashRESTURL = strings.TrimRight(strings.TrimSpace(rawURL), "/")
	upstashRESTToken = strings.TrimSpace(token)
	upstashSource = source
	upstashMu.Unlock()
}

func upstashEnabled() bool {
	u, t := upstashCreds()
	return u != "" && t != ""
}

func validateUpstashURL(raw string) (string, error) {
	raw = strings.TrimRight(strings.TrimSpace(raw), "/")
	if raw == "" {
		return "", fmt.Errorf("رابط Upstash REST مطلوب")
	}
	parsed, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("رابط Upstash غير صالح")
	}
	if strings.ToLower(parsed.Scheme) != "https" {
		return "", fmt.Errorf("يجب أن يبدأ رابط Upstash بـ https")
	}
	host := strings.ToLower(parsed.Hostname())
	if host != "upstash.io" && !strings.HasSuffix(host, ".upstash.io") {
		return "", fmt.Errorf("مضيف Upstash يجب أن يكون ضمن *.upstash.io")
	}
	if parsed.User != nil || parsed.Port() != "" {
		return "", fmt.Errorf("رابط Upstash يجب أن يكون بلا بيانات دخول أو منفذ مخصص")
	}
	if parsed.Path != "" && parsed.Path != "/" {
		return "", fmt.Errorf("رابط Upstash يجب أن يكون بلا مسار إضافي")
	}
	if parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", fmt.Errorf("رابط Upstash يجب أن يكون بلا معاملات أو Fragment")
	}
	return raw, nil
}

func upstashPing(rawURL, token string) error {
	u, err := validateUpstashURL(rawURL)
	if err != nil {
		return err
	}
	token = strings.TrimSpace(token)
	if token == "" {
		return fmt.Errorf("REST Token مطلوب")
	}
	req, err := http.NewRequest(http.MethodGet, u+"/ping", nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Upstash أعاد HTTP %d", resp.StatusCode)
	}
	var parsed struct {
		Result string `json:"result"`
		Error  string `json:"error"`
	}
	if len(body) > 0 {
		_ = json.Unmarshal(body, &parsed)
	}
	if parsed.Error != "" {
		return fmt.Errorf("%s", parsed.Error)
	}
	return nil
}

func upstashSet(key, value string) error {
	u, token := upstashCreds()
	if u == "" || token == "" {
		return fmt.Errorf("Upstash غير مهيأ")
	}
	req, err := http.NewRequest(http.MethodPost, u+"/set/"+key, strings.NewReader(value))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
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
	u, token := upstashCreds()
	if u == "" || token == "" {
		return "", fmt.Errorf("Upstash غير مهيأ")
	}
	req, err := http.NewRequest(http.MethodGet, u+"/get/"+key, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+token)
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

func loadUpstashConfig() {
	if upstashEnvURL != "" && upstashEnvToken != "" {
		setUpstashCreds(upstashEnvURL, upstashEnvToken, "env")
		log.Printf("Upstash: مفعّل من Environment Variables")
		return
	}
	b, err := os.ReadFile(upstashConfigPath)
	if err != nil {
		setUpstashCreds("", "", "none")
		return
	}
	var cfg upstashDiskConfig
	if err := json.Unmarshal(b, &cfg); err != nil {
		log.Printf("Upstash: ملف الإعداد المحفوظ غير سليم — يُتجاهل")
		setUpstashCreds("", "", "none")
		return
	}
	u, err := validateUpstashURL(cfg.URL)
	if err != nil || strings.TrimSpace(cfg.Token) == "" {
		log.Printf("Upstash: إعداد محفوظ غير صالح — يُتجاهل")
		setUpstashCreds("", "", "none")
		return
	}
	setUpstashCreds(u, cfg.Token, "file")
	log.Printf("Upstash: مفعّل من إعداد محفوظ على القرص")
}

func saveUpstashConfig(rawURL, token string) error {
	cfg := upstashDiskConfig{
		URL:     strings.TrimRight(strings.TrimSpace(rawURL), "/"),
		Token:   strings.TrimSpace(token),
		SavedAt: time.Now().Format(time.RFC3339),
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(upstashConfigPath), 0o755); err != nil {
		return err
	}
	tmp := upstashConfigPath + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, upstashConfigPath)
}

func markCloudResult(action string, err error) {
	cloudStatusMu.Lock()
	cloudLastAction = action
	if err != nil {
		cloudLastError = err.Error()
	} else {
		cloudLastError = ""
		cloudLastSync = time.Now()
	}
	cloudStatusMu.Unlock()
}

func cloudStatusSnapshot() map[string]interface{} {
	u, t := upstashCreds()
	host := ""
	if parsed, err := url.Parse(u); err == nil {
		host = parsed.Host
	}
	cloudStatusMu.Lock()
	lastSync := cloudLastSync
	lastErr := cloudLastError
	lastAction := cloudLastAction
	cloudStatusMu.Unlock()
	lastSyncMs := int64(0)
	if !lastSync.IsZero() {
		lastSyncMs = lastSync.UnixMilli()
	}
	return map[string]interface{}{
		"ok":             true,
		"configured":     u != "" && t != "",
		"source":         upstashSourceValue(),
		"url":            u,
		"urlHost":        host,
		"tokenSet":       t != "",
		"cacheKey":       cacheCloudKey,
		"adminProtected": strings.TrimSpace(os.Getenv("RB_ADMIN_TOKEN")) != "",
		"lastSync":       lastSyncMs,
		"lastError":      lastErr,
		"lastAction":     lastAction,
	}
}

func cloudAdminAllowed(r *http.Request, bodyKey string) bool {
	expected := strings.TrimSpace(os.Getenv("RB_ADMIN_TOKEN"))
	if expected == "" {
		return true
	}
	got := strings.TrimSpace(r.Header.Get("X-RB-Admin"))
	if got == "" {
		got = strings.TrimSpace(bodyKey)
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(expected)) == 1
}

func writeCloudJSON(w http.ResponseWriter, status int, payload interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func handleCloudAPI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-RB-Admin")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.URL.Path == "/api/cloud/status" {
		if r.Method != http.MethodGet {
			writeCloudJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"ok": false, "error": "روشت غير مدعوم"})
			return
		}
		writeCloudJSON(w, http.StatusOK, cloudStatusSnapshot())
		return
	}
	if r.Method != http.MethodPost {
		writeCloudJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"ok": false, "error": "روشت غير مدعوم"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	var req struct {
		URL      string `json:"url"`
		Token    string `json:"token"`
		AdminKey string `json:"adminKey"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeCloudJSON(w, http.StatusBadRequest, map[string]interface{}{"ok": false, "error": "JSON غير صالح"})
		return
	}
	if !cloudAdminAllowed(r, req.AdminKey) {
		writeCloudJSON(w, http.StatusUnauthorized, map[string]interface{}{"ok": false, "error": "مفتاح الإدارة مطلوب أو غير صحيح"})
		return
	}

	switch r.URL.Path {
	case "/api/cloud/test":
		u, t := upstashCreds()
		if strings.TrimSpace(req.URL) != "" {
			u = req.URL
		}
		if strings.TrimSpace(req.Token) != "" {
			t = req.Token
		}
		u, err := validateUpstashURL(u)
		if err != nil {
			writeCloudJSON(w, http.StatusBadRequest, map[string]interface{}{"ok": false, "error": err.Error()})
			return
		}
		if err := upstashPing(u, t); err != nil {
			writeCloudJSON(w, http.StatusBadRequest, map[string]interface{}{"ok": false, "error": "فشل الاتصال: " + err.Error()})
			return
		}
		writeCloudJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "message": "PONG", "urlHost": hostOfURL(u)})

	case "/api/cloud/config":
		u, err := validateUpstashURL(req.URL)
		if err != nil {
			writeCloudJSON(w, http.StatusBadRequest, map[string]interface{}{"ok": false, "error": err.Error()})
			return
		}
		t := strings.TrimSpace(req.Token)
		if t == "" {
			writeCloudJSON(w, http.StatusBadRequest, map[string]interface{}{"ok": false, "error": "REST Token مطلوب للحفظ"})
			return
		}
		if err := upstashPing(u, t); err != nil {
			writeCloudJSON(w, http.StatusBadRequest, map[string]interface{}{"ok": false, "error": "فشل اختبار الاتصال قبل الحفظ: " + err.Error()})
			return
		}
		setUpstashCreds(u, t, "ui")
		saveErr := saveUpstashConfig(u, t)
		go func() {
			loadCacheFromCloud()
			syncCacheToCloud(true)
		}()
		payload := cloudStatusSnapshot()
		payload["message"] = "حُفظت إعدادات Upstash وفُعّل الكاش السحابي"
		if saveErr != nil {
			payload["warning"] = "فُعّل الإعداد في الذاكرة، لكن تعذّر حفظه على القرص: " + saveErr.Error()
			log.Printf("Upstash: تعذّر حفظ الإعداد على القرص: %v", saveErr)
		}
		writeCloudJSON(w, http.StatusOK, payload)

	case "/api/cloud/sync":
		if !upstashEnabled() {
			writeCloudJSON(w, http.StatusBadRequest, map[string]interface{}{"ok": false, "error": "Upstash غير مهيأ — احفظ الرابط والتوكن أولاً"})
			return
		}
		go syncCacheToCloud(true)
		writeCloudJSON(w, http.StatusAccepted, map[string]interface{}{"ok": true, "message": "بدأت مزامنة الكاش السحابي"})

	default:
		writeCloudJSON(w, http.StatusNotFound, map[string]interface{}{"ok": false, "error": "نهاية غير معروفة"})
	}
}

func hostOfURL(raw string) string {
	if parsed, err := url.Parse(raw); err == nil {
		return parsed.Host
	}
	return ""
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
		markCloudResult("sync", err)
		return
	}
	if err := upstashSet(cacheCloudKey, string(b)); err != nil {
		log.Printf("تعذّر رفع كاش القوائم إلى التخزين السحابي (يعاد في الدورة القادمة): %v", err)
		markCloudResult("sync", err)
		cloudSyncMu.Lock()
		cloudDirty = true
		cloudSyncMu.Unlock()
		return
	}
	markCloudResult("sync", nil)
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
		markCloudResult("restore", err)
		return
	}
	if raw == "" {
		markCloudResult("restore", nil)
		log.Printf("لا نسخة سحابية للكاش بعد")
		return
	}
	var loaded map[string]map[string]*cacheEntry
	if err := json.Unmarshal([]byte(raw), &loaded); err != nil || loaded == nil {
		log.Printf("نسخة الكاش السحابية غير سليمة")
		markCloudResult("restore", fmt.Errorf("نسخة الكاش السحابية غير سليمة"))
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
	markCloudResult("restore", nil)
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
			"status":      "healthy",
			"timestamp":   time.Now().UnixMilli(),
			"hosts":       hostCount,
			"controllers": controllerCount,
			"total":       len(hub.clients),
			"uptime_ms":   time.Since(startTime).Milliseconds(),
		})
	})

	// Metrics endpoint (Prometheus)
	http.HandleFunc("/metrics", promhttp.Handler().ServeHTTP)

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		// B24 — استضافة أي ملف ثابت موجود داخل web-dashboard (الواجهة النهائية indexMergeFull.html
		// والأساس index.html وأصول أخرى)، مع تحبيب المسار ومنع تجاوز الجذر ثم الاحتياط إلى اللوحة الأساس.
		rel := strings.TrimPrefix(r.URL.Path, "/")
		rel = strings.TrimSpace(rel)
		target := ""
		if rel != "" && rel != "." {
			candidate := filepath.Clean(filepath.Join("web-dashboard", filepath.FromSlash(rel)))
			root, rootErr := filepath.Abs("web-dashboard")
			abs, absErr := filepath.Abs(candidate)
			if rootErr == nil && absErr == nil &&
				(abs == root || strings.HasPrefix(abs, root+string(os.PathSeparator))) {
				if fi, statErr := os.Stat(candidate); statErr == nil && !fi.IsDir() {
					target = candidate
				}
			}
		}
		if target == "" {
			// اعتماد الواجهة الموحدة indexMergeFull.html كالواجهة الحصرية والوحيدة للجذر
			for _, fallback := range []string{"indexMergeFull.html"} {
				index := filepath.Join("web-dashboard", fallback)
				if _, err := os.Stat(index); err == nil {
					target = index
					break
				}
			}
		}
		if target != "" {
			w.Header().Set("Access-Control-Allow-Origin", "*")
			// واجهة النشر تتبدل عبر الدفعات فلا يُغتَّضع نسخة عتيقة عند المتصفح
			w.Header().Set("Cache-Control", "no-cache, must-revalidate")
			http.ServeFile(w, r, target)
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
	http.HandleFunc("/api/blob/status", handleBlobStatusAPI)
	// B45 — إدارة Upstash من واجهة المتحكم: حالة + اختبار + حفظ + مزامنة
	http.HandleFunc("/api/cloud/status", handleCloudAPI)
	http.HandleFunc("/api/cloud/test", handleCloudAPI)
	http.HandleFunc("/api/cloud/config", handleCloudAPI)
	http.HandleFunc("/api/cloud/sync", handleCloudAPI)
	loadBlobStore()

	// B12 — البيانات المخزنة على الخادم: كاش القوائم التلقائي (تحميل من القرص + تفريغ دوري)
	loadCacheFromDisk()
	loadUpstashConfig()
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
	var currentRole string
	var clientRef *Client

	wsConnections.Inc()
	defer wsConnections.Dec()

	defer func() {
		if clientRef == nil {
			return
		}
		// لا نحذف هوية الجهاز إلا إذا كان هذا الاتصال هو صاحبها الحالي.
		// الاتصال القديم الذي حلّ محله اتصال أحدث لا يجوز أن يمسح التوكن
		// الجديد ولا أن يبثّ host_offline للمتحكمين.
		hub.mu.Lock()
		stillRegistered := hub.clients[currentDeviceID] == clientRef
		if stillRegistered {
			delete(hub.clients, currentDeviceID)
			delete(hub.clientTokens, currentDeviceID)
		}
		hub.mu.Unlock()
		if stillRegistered && currentRole == "host" {
			broadcastHostOffline(currentDeviceID)
		}
	}()

	// نبض من الخادم + مهلة قراءة: يكشف الاتصالات الميتة خلال ~70 ثانية
	// حتى لو لم يرسل العميل أي شيء (كانت الاتصالات المعلقة تبقى في hub للأبد).
	_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	})
	pingDone := make(chan struct{})
	defer close(pingDone)
	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if err := conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second)); err != nil {
					return
				}
			case <-pingDone:
				return
			}
		}
	}()

	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			break
		}
		_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))

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

			currentDeviceID = strings.TrimSpace(reg.DeviceID)
			currentRole = reg.Role
			if currentDeviceID == "" {
				_ = conn.WriteMessage(websocket.TextMessage, []byte(`{"type":"error","message":"deviceId مطلوب في رسالة التسجيل"}`))
				break
			}

			// تدوير التوكن عند كل تسجيل: يعالج إعادة التسجيل بعد إعادة تشغيل
			// الخادم (التي تفقد التوكنات) ويعالج عودة المضيف قبل كشف الاتصال
			// القديم الميت. لا نرفض التسجيل بتوكن قديم — العميل يحتفظ بالأحدث.
			newToken := generateToken()
			if newToken == "" {
				newToken = base64.URLEncoding.EncodeToString([]byte(reg.DeviceID + time.Now().String()))
			}

			client := &Client{
				ID:          reg.DeviceID,
				Role:        reg.Role,
				Name:        reg.DeviceName,
				Token:       newToken,
				Socket:      conn,
				ConnectedAt: time.Now(),
			}

			hub.mu.Lock()
			oldClient := hub.clients[reg.DeviceID]
			hub.clients[reg.DeviceID] = client
			hub.clientTokens[reg.DeviceID] = newToken
			hub.mu.Unlock()

			// إغلاق الجلسة القديمة لنفس الجهاز بعد تبديل المرجع. إغلاقها
			// يجعل defer الخاص بها يرى أنه لم يعد صاحب الهوية فلا يحذف الجديدة.
			if oldClient != nil && oldClient.Socket != nil && oldClient.Socket != conn {
				_ = oldClient.Socket.Close()
			}

			clientRef = client

			resp, _ := json.Marshal(map[string]interface{}{
				"type":  "registered",
				"ok":    true,
				"token": newToken,
			})
			_ = client.send(resp)

			if reg.Role == "host" {
				broadcastHostOnline(reg.DeviceID, reg.DeviceName)
			} else if reg.Role == "controller" {
				sendHostList(client)
			}
			continue
		}

		// لا تُقبل أي رسالة قبل التسجيل (عدا ping التشخيصي).
		if clientRef == nil {
			if msgType == "ping" {
				resp, _ := json.Marshal(map[string]interface{}{"type": "pong", "t": time.Now().UnixMilli()})
				_ = conn.WriteMessage(websocket.TextMessage, resp)
			}
			continue
		}

		// التحقق من أن هذا الاتصال ما زال صاحب الهوية، ثم التحقق من التوكن.
		hub.mu.RLock()
		stillOwner := hub.clients[currentDeviceID] == clientRef
		expectedToken := hub.clientTokens[currentDeviceID]
		hub.mu.RUnlock()
		if !stillOwner {
			break // حلّ اتصال أحدث محل هذا الاتصال
		}
		if msgType != "ping" {
			var msgEnvelope struct {
				Token string `json:"token"`
			}
			_ = json.Unmarshal(msgBytes, &msgEnvelope)
			// تساهل مقصود: يُرفض فقط التوكن الحاضر والخاطئ — العميلات القديمة بلا توكن تنجح.
			// عند الرفض نُبلّغ العميل صراحةً ونغلق الاتصال ليعيد التسجيل بتوكن جديد،
			// بدل بقائه متصلاً في حلقة رسائل مُهملة بلا نهاية.
			if expectedToken != "" && msgEnvelope.Token != "" && msgEnvelope.Token != expectedToken {
				errResp, _ := json.Marshal(map[string]interface{}{
					"type":    "error",
					"message": "Invalid authentication token",
				})
				_ = clientRef.send(errResp)
				break
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
				_ = clientRef.send(rejectResp)
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
				_ = clientRef.send(errResp)
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
			_ = clientRef.send(resp)

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
		err := target.send(msgBytes)
		if err != nil {
			log.Printf("Failed to send to %s: %v", to, err)
			return false
		}
		return true
	}
	return false
}

func sendHostList(client *Client) {
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
	_ = client.send(msg)
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
			if err := c.send(msg); err != nil {
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
			if err := c.send(msg); err != nil {
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
func handleStateAPI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
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

// 📦 B41 — تخزين دائم للبلوبات على القرص مع فهرس JSON، بدل الذاكرة وحدها.
// الافتراضي داخل حاوية Render: ./data/blobs (يمكن تغييره بـ RB_BLOB_DIR أو قرص دائم).
func blobRootDir() string {
	if v := strings.TrimSpace(os.Getenv("RB_BLOB_DIR")); v != "" {
		return v
	}
	return filepath.Join("data", "blobs")
}

func blobIndexPath() string { return filepath.Join(blobRootDir(), "index.json") }

func blobSafeSegment(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return "_"
	}
	var b strings.Builder
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') ||
			r == '-' || r == '_' || r == '.' || r == '@' {
			b.WriteRune(r)
		} else {
			b.WriteRune('_')
		}
	}
	out := b.String()
	if out == "." || out == ".." {
		out = "_" + out
	}
	if len(out) > 120 {
		sum := sha256.Sum256([]byte(s))
		out = out[:80] + "_" + fmt.Sprintf("%x", sum[:8])
	}
	return out
}

func blobFilePath(deviceID, kind, name string) string {
	return filepath.Join(blobRootDir(), blobSafeSegment(deviceID), blobSafeSegment(kind), blobSafeSegment(name))
}

func blobTempPath(deviceID, kind, name, sha string) string {
	key := blobSafeSegment(deviceID + "__" + kind + "__" + name + "__" + sha)
	return filepath.Join(blobRootDir(), ".uploads", key+".part")
}

// يجب استدعاؤها وقفل blobMu ممسوك.
func saveBlobIndexLocked() {
	out := make(map[string]map[string]map[string]*BlobItem)
	for d, kinds := range blobData {
		for k, items := range kinds {
			for n, it := range items {
				if it == nil {
					continue
				}
				if out[d] == nil {
					out[d] = map[string]map[string]*BlobItem{}
				}
				if out[d][k] == nil {
					out[d][k] = map[string]*BlobItem{}
				}
				out[d][k][n] = &BlobItem{Name: it.Name, Kind: it.Kind, Sha256: it.Sha256, Size: it.Size, Ts: it.Ts}
			}
		}
	}
	b, err := json.Marshal(out)
	if err != nil {
		return
	}
	p := blobIndexPath()
	_ = os.MkdirAll(filepath.Dir(p), 0o755)
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err == nil {
		_ = os.Rename(tmp, p)
	}
}

// استعادة فهرس البلوبات من القرص عند الإقلاع (Render أو أي استضافة).
func loadBlobStore() {
	b, err := os.ReadFile(blobIndexPath())
	if err != nil {
		return
	}
	var idx map[string]map[string]map[string]*BlobItem
	if err := json.Unmarshal(b, &idx); err != nil {
		log.Printf("فهرس البلوبات تالف — تجاهل: %v", err)
		return
	}
	blobMu.Lock()
	defer blobMu.Unlock()
	count := 0
	for d, kinds := range idx {
		for k, items := range kinds {
			for n, it := range items {
				if it == nil {
					continue
				}
				st, err := os.Stat(blobFilePath(d, k, n))
				if err != nil || st.IsDir() {
					continue
				}
				it.Data = nil
				it.Size = st.Size()
				if blobData[d] == nil {
					blobData[d] = map[string]map[string]*BlobItem{}
				}
				if blobData[d][k] == nil {
					blobData[d][k] = map[string]*BlobItem{}
				}
				blobData[d][k][n] = it
				blobBytes[d] += it.Size
				count++
			}
		}
	}
	log.Printf("استُعيد %d ملف بلوب من القرص (%s)", count, blobRootDir())
}

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
		_ = os.Remove(blobFilePath(deviceID, oldestKind, oldestName))
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
		// B41 — الرفع المجزأ الاستئنافي يُفعَّل عند وجود offset أو size في الرابط.
		if r.URL.Query().Get("offset") != "" || r.URL.Query().Get("size") != "" {
			handleBlobChunkUpload(w, r, deviceID, kind, name)
			return
		}
		handleBlobSingleUpload(w, r, deviceID, kind, name)

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
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", it.Name))
		// B41 — خدمة من القرص عبر http.ServeContent لدعم Range واستئناف التنزيل.
		f, err := os.Open(blobFilePath(deviceID, kind, name))
		if err == nil {
			defer f.Close()
			http.ServeContent(w, r, it.Name, time.UnixMilli(it.Ts), f)
			return
		}
		if len(it.Data) > 0 {
			http.ServeContent(w, r, it.Name, time.UnixMilli(it.Ts), bytes.NewReader(it.Data))
			return
		}
		writeStateJSONError(w, http.StatusNotFound, "محتوى الملف غير متاح على الخادم")

	default:
		writeStateJSONError(w, http.StatusMethodNotAllowed, "طريقة غير مدعومة")
	}
}

// رفع ملف واحد كامل (الوضع القديم، ويبقى مدعوماً للتوافق).
func handleBlobSingleUpload(w http.ResponseWriter, r *http.Request, deviceID, kind, name string) {
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
	if clientSha != "" && clientSha != sum {
		writeStateJSONError(w, http.StatusBadRequest, "بصمة SHA-256 المرسلة لا تطابق المحتوى")
		return
	}
	finalPath := blobFilePath(deviceID, kind, name)
	if err := os.MkdirAll(filepath.Dir(finalPath), 0o755); err != nil {
		writeStateJSONError(w, http.StatusInternalServerError, "تعذر إنشاء مجلد التخزين")
		return
	}
	if err := os.WriteFile(finalPath, data, 0o644); err != nil {
		writeStateJSONError(w, http.StatusInternalServerError, "تعذر كتابة الملف على القرص")
		return
	}
	blobMu.Lock()
	prev, existed := blobData[deviceID][kind][name]
	if existed && prev.Sha256 == sum && clientSha == sum {
		blobMu.Unlock()
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
	item := &BlobItem{Name: name, Kind: kind, Sha256: sum, Size: int64(len(data)), Ts: time.Now().UnixMilli()}
	if existed {
		item.Ts = prev.Ts
	}
	blobData[deviceID][kind][name] = item
	blobTrack(deviceID, item, prev)
	saveBlobIndexLocked()
	blobMu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "dedup": false, "sha256": sum, "size": item.Size, "received": item.Size, "complete": true})
}

// رفع مجزأ استئنافي: كل طلب يضيف جزءاً في offset محدد، والخادم يحتفظ بالجزء الناقص.
func handleBlobChunkUpload(w http.ResponseWriter, r *http.Request, deviceID, kind, name string) {
	offset, err := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("offset")), 10, 64)
	if err != nil || offset < 0 {
		offset = 0
	}
	total, _ := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("size")), 10, 64)
	finalFlag := r.URL.Query().Get("final") == "1"
	clientSha := strings.TrimSpace(r.Header.Get("X-Blob-Sha"))
	if clientSha == "" {
		clientSha = strings.TrimSpace(r.URL.Query().Get("sha"))
	}

	r.Body = http.MaxBytesReader(w, r.Body, blobMaxItem)
	chunk, err := io.ReadAll(r.Body)
	if err != nil {
		writeStateJSONError(w, http.StatusBadRequest, "فشل قراءة جزء الرفع")
		return
	}
	if total <= 0 {
		total = offset + int64(len(chunk))
	}
	if total > blobMaxItem {
		writeStateJSONError(w, http.StatusRequestEntityTooLarge, "حجم الملف يتجاوز الحد الأقصى (32MB)")
		return
	}
	if offset > total || offset+int64(len(chunk)) > total {
		writeStateJSONError(w, http.StatusBadRequest, "نطاق الجزء غير صالح")
		return
	}

	tmp := blobTempPath(deviceID, kind, name, clientSha)
	if err := os.MkdirAll(filepath.Dir(tmp), 0o755); err != nil {
		writeStateJSONError(w, http.StatusInternalServerError, "تعذر إنشاء مجلد الرفع المؤقت")
		return
	}
	cur := int64(0)
	if st, err := os.Stat(tmp); err == nil {
		cur = st.Size()
	}
	if cur > total {
		_ = os.Remove(tmp)
		cur = 0
	}
	if cur != offset {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"ok": false, "received": cur, "complete": false,
			"error": "اختلاف موضع الاستئناف — استكمل من received",
		})
		return
	}
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		writeStateJSONError(w, http.StatusInternalServerError, "تعذر فتح ملف الرفع المؤقت")
		return
	}
	if _, err = f.Seek(offset, io.SeekStart); err == nil {
		_, err = f.Write(chunk)
	}
	_ = f.Close()
	if err != nil {
		writeStateJSONError(w, http.StatusInternalServerError, "تعذر كتابة جزء الرفع")
		return
	}
	received := offset + int64(len(chunk))
	if received < total && !finalFlag {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "received": received, "complete": false, "size": total})
		return
	}

	st, err := os.Stat(tmp)
	if err != nil || st.Size() != total {
		writeStateJSONError(w, http.StatusBadRequest, "حجم الملف المجمّع لا يطابق الحجم المعلن")
		return
	}
	sum, err := sha256File(tmp)
	if err != nil {
		writeStateJSONError(w, http.StatusInternalServerError, "تعذر حساب بصمة الملف")
		return
	}
	if clientSha != "" && sum != clientSha {
		_ = os.Remove(tmp)
		writeStateJSONError(w, http.StatusBadRequest, "بصمة SHA-256 النهائية لا تطابق المحتوى — أُعيد الرفع من البداية")
		return
	}
	finalPath := blobFilePath(deviceID, kind, name)
	if err := os.MkdirAll(filepath.Dir(finalPath), 0o755); err != nil {
		writeStateJSONError(w, http.StatusInternalServerError, "تعذر إنشاء مجلد التخزين")
		return
	}
	_ = os.Remove(finalPath)
	if err := os.Rename(tmp, finalPath); err != nil {
		// احتياطي: نسخ ثم حذف (بعض أنظمة الملفات تمنع النقل عبر أقراص مختلفة)
		data, rerr := os.ReadFile(tmp)
		if rerr != nil || os.WriteFile(finalPath, data, 0o644) != nil {
			writeStateJSONError(w, http.StatusInternalServerError, "تعذر تثبيت الملف النهائي")
			return
		}
		_ = os.Remove(tmp)
	}

	blobMu.Lock()
	prev, existed := blobData[deviceID][kind][name]
	if blobData[deviceID] == nil {
		blobData[deviceID] = map[string]map[string]*BlobItem{}
	}
	if blobData[deviceID][kind] == nil {
		blobData[deviceID][kind] = map[string]*BlobItem{}
	}
	item := &BlobItem{Name: name, Kind: kind, Sha256: sum, Size: total, Ts: time.Now().UnixMilli()}
	if existed {
		item.Ts = prev.Ts
	}
	blobData[deviceID][kind][name] = item
	blobTrack(deviceID, item, prev)
	saveBlobIndexLocked()
	blobMu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"ok": true, "dedup": false, "sha256": sum, "size": total,
		"received": received, "complete": true,
	})
}

func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return fmt.Sprintf("%x", h.Sum(nil)), nil
}

// GET /api/blob/status?device=&kind=&name=&sha= — حالة الرفع لاستئناف آمن.
func handleBlobStatusAPI(w http.ResponseWriter, r *http.Request) {
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
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	sha := strings.TrimSpace(r.URL.Query().Get("sha"))
	if deviceID == "" || kind == "" || name == "" {
		writeStateJSONError(w, http.StatusBadRequest, "device و kind و name مطلوبة")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if st, err := os.Stat(blobFilePath(deviceID, kind, name)); err == nil && !st.IsDir() {
		blobMu.RLock()
		it := blobData[deviceID][kind][name]
		blobMu.RUnlock()
		if sha != "" && it != nil && it.Sha256 != "" && it.Sha256 != sha {
			json.NewEncoder(w).Encode(map[string]interface{}{
				"ok": true, "complete": false, "offset": 0, "size": st.Size(), "conflict": true,
			})
			return
		}
		out := map[string]interface{}{"ok": true, "complete": true, "offset": st.Size(), "size": st.Size()}
		if it != nil {
			out["sha256"] = it.Sha256
		}
		json.NewEncoder(w).Encode(out)
		return
	}
	off := int64(0)
	if st, err := os.Stat(blobTempPath(deviceID, kind, name, sha)); err == nil {
		off = st.Size()
	}
	json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "complete": false, "offset": off})
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
