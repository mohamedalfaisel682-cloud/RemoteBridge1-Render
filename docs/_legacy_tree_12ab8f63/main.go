package main

import (
<<<<<<< HEAD
	"crypto/rand"
	"encoding/base64"
=======
>>>>>>> origin/main
	"encoding/json"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
<<<<<<< HEAD
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
=======
>>>>>>> origin/main
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

type Client struct {
<<<<<<< HEAD
	ID      string          `json:"deviceId"`
	Role    string          `json:"role"`
	Name    string          `json:"deviceName"`
	Token   string          `json:"-"`
	Socket  *websocket.Conn `json:"-"`
	ConnectedAt time.Time   `json:"-"`
=======
	ID     string          `json:"deviceId"`
	Role   string          `json:"role"`
	Name   string          `json:"deviceName"`
	Socket *websocket.Conn `json:"-"`
>>>>>>> origin/main
}

type HostInfo struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	Online     bool   `json:"online"`
}

<<<<<<< HEAD
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
=======
type Hub struct {
	mu      sync.Mutex
	clients map[string]*Client
}

var hub = Hub{
	clients: make(map[string]*Client),
>>>>>>> origin/main
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "10000"
	}

<<<<<<< HEAD
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
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"service": "RemoteBridge Signaling Server",
			"status":  "active",
			"version": "2.0.0",
			"endpoints": map[string]string{
				"/health": "Health check",
				"/metrics": "Prometheus metrics",
				"/ws":     "WebSocket signaling",
			},
		})
=======
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("RemoteBridge Signaling Server is Active & Healthy!"))
>>>>>>> origin/main
	})

	http.HandleFunc("/ws", handleWebSocket)

	addr := "0.0.0.0:" + port
<<<<<<< HEAD
	log.Printf("RemoteBridge Signaling Server starting on %s", addr)
	log.Printf("Health: http://%s/health", addr)
	log.Printf("Metrics: http://%s/metrics", addr)

=======
	log.Printf("Server starting on %s", addr)
	
>>>>>>> origin/main
	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatalf("ListenAndServe error: %v", err)
	}
}

<<<<<<< HEAD
var startTime = time.Now()

=======
>>>>>>> origin/main
func handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println("Upgrade error:", err)
		return
	}
	defer conn.Close()

	var currentDeviceID string
<<<<<<< HEAD
	var authToken string
	var currentRole string
=======
>>>>>>> origin/main

	defer func() {
		if currentDeviceID != "" {
			hub.mu.Lock()
			delete(hub.clients, currentDeviceID)
<<<<<<< HEAD
			delete(hub.clientTokens, currentDeviceID)
			hub.mu.Unlock()
			if currentRole == "host" {
				broadcastHostOffline(currentDeviceID)
			}
			wsConnections.Dec()
		}
	}()

	wsConnections.Inc()

=======
			hub.mu.Unlock()
			broadcastHostOffline(currentDeviceID)
		}
	}()

>>>>>>> origin/main
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

<<<<<<< HEAD
		// المصادقة عند التسجيل
		if msgType == "register" {
=======
		switch msgType {
		case "register":
>>>>>>> origin/main
			var reg struct {
				DeviceID   string `json:"deviceId"`
				Role       string `json:"role"`
				DeviceName string `json:"deviceName"`
<<<<<<< HEAD
				Token      string `json:"token,omitempty"`
=======
>>>>>>> origin/main
			}
			if err := json.Unmarshal(msgBytes, &reg); err != nil {
				continue
			}

			currentDeviceID = reg.DeviceID
<<<<<<< HEAD
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
=======
			client := &Client{
				ID:     reg.DeviceID,
				Role:   reg.Role,
				Name:   reg.DeviceName,
				Socket: conn,
>>>>>>> origin/main
			}

			hub.mu.Lock()
			hub.clients[reg.DeviceID] = client
<<<<<<< HEAD
			hub.clientTokens[reg.DeviceID] = authToken
			hub.mu.Unlock()

			resp, _ := json.Marshal(map[string]interface{}{
				"type":  "registered",
				"ok":    true,
				"token": authToken,
=======
			hub.mu.Unlock()

			resp, _ := json.Marshal(map[string]interface{}{
				"type": "registered",
				"ok":   true,
>>>>>>> origin/main
			})
			conn.WriteMessage(websocket.TextMessage, resp)

			if reg.Role == "host" {
				broadcastHostOnline(reg.DeviceID, reg.DeviceName)
			} else if reg.Role == "controller" {
				sendHostList(conn)
			}
<<<<<<< HEAD
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
=======

		case "offer", "answer", "ice", "reject":
			var meta struct {
				To string `json:"to"`
			}
			if err := json.Unmarshal(msgBytes, &meta); err != nil {
				continue
			}

			hub.mu.Lock()
			targetClient, ok := hub.clients[meta.To]
			hub.mu.Unlock()

			if ok && targetClient.Socket != nil {
				targetClient.Socket.WriteMessage(websocket.TextMessage, msgBytes)
			}
>>>>>>> origin/main

		case "ping":
			resp, _ := json.Marshal(map[string]interface{}{
				"type": "pong",
				"t":    time.Now().UnixMilli(),
			})
			conn.WriteMessage(websocket.TextMessage, resp)
<<<<<<< HEAD

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
=======
>>>>>>> origin/main
		}
	}
}

<<<<<<< HEAD
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
=======
func sendHostList(conn *websocket.Conn) {
	hub.mu.Lock()
>>>>>>> origin/main
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
<<<<<<< HEAD
	hub.mu.RUnlock()
=======
	hub.mu.Unlock()
>>>>>>> origin/main

	msg, _ := json.Marshal(map[string]interface{}{
		"type":  "hosts",
		"hosts": hosts,
<<<<<<< HEAD
		"count": len(hosts),
		"ts":    time.Now().UnixMilli(),
=======
>>>>>>> origin/main
	})
	conn.WriteMessage(websocket.TextMessage, msg)
}

func broadcastHostOnline(deviceID, deviceName string) {
<<<<<<< HEAD
	hub.mu.RLock()
=======
	hub.mu.Lock()
	defer hub.mu.Unlock()

>>>>>>> origin/main
	msg, _ := json.Marshal(map[string]interface{}{
		"type": "host_online",
		"host": HostInfo{
			DeviceID:   deviceID,
			DeviceName: deviceName,
			Online:     true,
		},
<<<<<<< HEAD
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
=======
	})

	for _, c := range hub.clients {
		if c.Role == "controller" && c.Socket != nil {
			c.Socket.WriteMessage(websocket.TextMessage, msg)
>>>>>>> origin/main
		}
	}
}

func broadcastHostOffline(deviceID string) {
<<<<<<< HEAD
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
=======
	hub.mu.Lock()
	defer hub.mu.Unlock()

	msg, _ := json.Marshal(map[string]interface{}{
		"type":     "host_offline",
		"deviceId": deviceID,
	})

	for _, c := range hub.clients {
		if c.Role == "controller" && c.Socket != nil {
			c.Socket.WriteMessage(websocket.TextMessage, msg)
		}
	}
}
>>>>>>> origin/main
