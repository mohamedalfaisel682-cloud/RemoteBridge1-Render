package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
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
