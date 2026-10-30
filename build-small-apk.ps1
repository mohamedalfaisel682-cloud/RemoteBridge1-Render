# ============================================================================
#  build-small-apk.ps1 — بناء APK صغير الحجم معزّم وموقّع (R8 + ABI Splits)
# ----------------------------------------------------------------------------
#  ما يفعله:
#   1. توليد ملف توقيع محلي my-upload-key.jks إن لم يوجد (keytool من JDK).
#   2. ضبط متغيرات البيئة التي تقرأها signingConfigs في app/build.gradle.kts.
#   3. تنفيذ :app:assembleRelease مع R8 (Minify) و ShrinkResources
#      وتقسيم المعالجات (arm64-v8a / armeabi-v7a / x86 / x86_64 / universal).
#   4. طباعة مسارات وأحجام APK الناتج من app/build/outputs/apk/release.
#
#  الاستخدام (PowerShell):
#      .\build-small-apk.ps1            # بناء موقّع كامل
#      .\build-small-apk.ps1 -Bundle    # منتج AAB (خيار النشر على Play)
#  ملاحظة: يغيّر متغيرات البيئة للجلسة الحالية فقط — لا يعدّل أي ملف مشروع.
# ============================================================================
param(
    [switch]$Bundle,
    [string]$KeystorePath = (Join-Path $PSScriptRoot "my-upload-key.jks"),
    [string]$StorePassword = "",
    [int]$ValidityDays = 10000
)

$ErrorActionPreference = "Continue"  # أوامر native تُقيَّم عبر LASTEXITCODE أدناه (منع موت الscript عند stderr لـkeytool/gradle)

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host " بناء APK مصغّر (R8 + ShrinkResources + ABI Splits)" -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

# ----------------------------------------------------------------------------
# 1) البحث عن keytool في JDK المستخدم (JAVA_HOME أو gradle-java أو PATH)
# ----------------------------------------------------------------------------
function Find-Keytool {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\keytool.exe") }
    $javaDir = Split-Path -Parent (Get-Command java -ErrorAction SilentlyContinue).Source
    if ($javaDir) { $candidates += (Join-Path $javaDir "keytool.exe") }
    # تثبيتات JDK الشائعة
    $candidates += Get-ChildItem "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft\jdk*" -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "bin\keytool.exe" }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    throw "لم أجد keytool. ثبّت JDK أو اضبط JAVA_HOME."
}

# ----------------------------------------------------------------------------
# 2) توليد المفتاح إن لم يوجد
# ----------------------------------------------------------------------------
# كلمة سر موقّعة ثابتة للاستخدام المحلي — تُضبط في كل الحالات (وجود المفتاح أو
# توليده)، وإلا بقي STORE_PASSWORD فارغاً ورفض Gradle استكمال التوقيع.
if (-not $StorePassword -or $StorePassword.Length -lt 6) {
    # قابل للتغيير بالمعامل: -StorePassword كلمة_السر
    $StorePassword = "RemoteBridge123"
    Write-Host "!" -NoNewline -ForegroundColor Yellow
    Write-Host " استُخدمت كلمة سر موقّعة افتراضية: $StorePassword (يمكن تغييرها بـ -StorePassword)"
}

if (-not (Test-Path $KeystorePath)) {
    $keytool = Find-Keytool
    Write-Host "🔑 توليد مفتاح التوقيع المحلي: $KeystorePath"
    & $keytool -genkeypair `
        -keystore $KeystorePath `
        -storetype JKS `
        -keyalg RSA -keysize 2048 `
        -validity $ValidityDays `
        -alias upload `
        -dname "CN=RemoteBridge, OU=Personal, O=RemoteBridge, L=_local, S=local, C=SA" `
        -storepass $StorePassword -keypass $StorePassword
    if ($LASTEXITCODE -ne 0) { throw "فشل توليد المفتاح (keytool)." }
}
else {
    Write-Host "🔑 المفتاح موجود مسبقاً: $KeystorePath"
}

# ملاحظة: gradle يقرأ STORE_PASSWORD/KEY_PASSWORD من البيئة — نضبطها للجلسة
$env:KEYSTORE_PATH   = $KeystorePath
$env:STORE_PASSWORD  = $StorePassword
$env:KEY_PASSWORD    = $StorePassword

# ----------------------------------------------------------------------------
# 3) البناء (infer إذا لم تضبط بيئة Gradle في جلسة أخرى)
# ----------------------------------------------------------------------------
$gradleArgs = @(":app:assembleRelease", "--console=plain")
if ($Bundle) { $gradleArgs = @(":app:bundleRelease", "--console=plain") }

Write-Host ""
Write-Host "🚀 تشغيل: .\gradlew $($gradleArgs -join ' ')" -ForegroundColor Green
& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ فشل البناء — راجع رسائل التدرّج أعلاه" -ForegroundColor Red
    exit 1
}

# ----------------------------------------------------------------------------
# 4) تقرير الأحجام
# ----------------------------------------------------------------------------
Write-Host ""
Write-Host "📦 المخرجات:" -ForegroundColor Cyan
$apkDir = Join-Path $PSScriptRoot "app\build\outputs\apk\release"
if (-not (Test-Path $apkDir)) { $apkDir = Join-Path $PSScriptRoot "app\build\outputs\bundle\release" }
Get-ChildItem $apkDir -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -in @(".apk", ".aab") } |
    Sort-Object Length |
    ForEach-Object {
        $sizeMB = [math]::Round($_.Length / 1MB, 2)
        Write-Host ("  {0,8} MB  {1}" -f $sizeMB, $_.FullName.Replace($PSScriptRoot, "."))
    }
