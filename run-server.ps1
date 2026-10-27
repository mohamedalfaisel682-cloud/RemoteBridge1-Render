# تشغيل خادم الإشارات المحلي
Write-Host "Starting RemoteBridge Signaling Server on port 8080..." -ForegroundColor Green
$env:PORT="8080"
go run main.go
