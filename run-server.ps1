# تشغيل خادم الإشارات المحلي على المنفذ الافتراضي 10000
param(
    [int]$Port = 10000
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location -LiteralPath $root

$env:PORT = "$Port"
Write-Host "Starting RemoteBridge Signaling Server on port $Port..." -ForegroundColor Green
Write-Host "Health:  http://localhost:$Port/health"
Write-Host "Metrics: http://localhost:$Port/metrics"
Write-Host "UI:      http://localhost:$Port/"
Write-Host "Press Ctrl+C to stop." -ForegroundColor Yellow
go run main.go
