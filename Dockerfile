FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -o signaling-server main.go

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /root/
COPY --from=builder /app/signaling-server .
# B13 — تضمين لوحة المتحكم الويبنية في عبوة التشغيل حتى يخدم الجذر '/' الواجهة فعلياً
COPY --from=builder /app/web-dashboard ./web-dashboard
EXPOSE 10000 9090
CMD ["./signaling-server"]