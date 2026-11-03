FROM golang:1.22-alpine AS builder
<<<<<<< HEAD
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -o signaling-server main.go

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /root/
COPY --from=builder /app/signaling-server .
EXPOSE 10000 9090
CMD ["./signaling-server"]
=======

WORKDIR /app

# نسخ ملفات الوحدات
COPY go.mod ./

# نسخ ملف الكود الرئيسي
COPY main.go ./

# تحميل الاعتماديات الناقصة وإنشاء go.sum تلقائياً
RUN go mod tidy

# بناء الملف التنفيذي
RUN CGO_ENABLED=0 GOOS=linux go build -o /server main.go

FROM alpine:3.20
WORKDIR /app

# تصحيح مسار النسخ ليتطابق مع أمر التشغيل
COPY --from=builder /server /server

EXPOSE 10000
CMD ["/server"]
>>>>>>> origin/main
