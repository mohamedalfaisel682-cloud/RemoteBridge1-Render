# التحقق من جهاز Android وتثبيت نسخة التسليم المعتمدة
param(
    [string]$ApkPath = "",
    [string]$Package = "com.rbstudio"
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if (-not $ApkPath) { $ApkPath = Join-Path $root "RBstudioPRO.apk" }
if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK not found: $ApkPath" }

function Get-SdkDir {
    $local = Join-Path $root "local.properties"
    if (-not (Test-Path -LiteralPath $local)) { return $null }
    $line = Get-Content -LiteralPath $local |
        Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
        Select-Object -First 1
    if (-not $line) { return $null }
    $value = ($line -split '=', 2)[1].Trim()
    return ($value -replace '\\\\', '\' -replace '\\:', ':' -replace '\\=', '=')
}

function Test-AdbWorks {
    param([string]$Path)
    try {
        $output = & $Path version 2>&1
        return ($LASTEXITCODE -eq 0 -and (($output | Out-String) -match 'Android Debug Bridge'))
    } catch {
        return $false
    }
}

$candidates = @()
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCommand) { $candidates += $adbCommand.Source }
if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe") }
$sdk = Get-SdkDir
if ($sdk) { $candidates += (Join-Path $sdk "platform-tools\adb.exe") }
$candidates = $candidates | Select-Object -Unique | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

$adb = $null
foreach ($candidate in $candidates) {
    if (Test-AdbWorks $candidate) { $adb = $candidate; break }
    Write-Host "ADB_INVALID=$candidate" -ForegroundColor Yellow
}
if (-not $adb) {
    Write-Host "ADB_NOT_FOUND_OR_INVALID" -ForegroundColor Red
    Write-Host "ثبّت platform-tools سليماً أو أضف adb عاملاً إلى PATH." -ForegroundColor Yellow
    exit 1
}

Write-Host "ADB=$adb"
& $adb start-server | Out-Null
$deviceLines = & $adb devices | Select-String -Pattern "\tdevice$"
if (-not $deviceLines) {
    Write-Host "NO_DEVICE" -ForegroundColor Yellow
    Write-Host "وصّل جهاز Android وفعّل USB Debugging ثم أعد المحاولة." -ForegroundColor Yellow
    exit 2
}
$serial = ($deviceLines[0].Line -split "\t")[0]
Write-Host "DEVICE_SERIAL=$serial"
Write-Host "DEVICE_MODEL=$(& $adb -s $serial shell getprop ro.product.model)"
Write-Host "ANDROID_VERSION=$(& $adb -s $serial shell getprop ro.build.version.release)"
Write-Host "APK=$ApkPath"
Write-Host "APK_SHA256=$((Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash)"

& $adb -s $serial install -r $ApkPath
if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }

Write-Host "INSTALLED_PACKAGE_METADATA:" -ForegroundColor Cyan
& $adb -s $serial shell dumpsys package $Package | Select-String -Pattern "versionName|versionCode"

& $adb -s $serial shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "DEVICE_VERIFY=PASS" -ForegroundColor Green
