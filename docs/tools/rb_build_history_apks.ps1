$ErrorActionPreference = 'Continue'
# rb_build_history_apks.ps1 — بناء APK لأي التزام تاريخي في المستودع الرئيسي
# يعمل في شجرة عمل مؤقتة تُنشأ وتُحذف في نفس العملية (لا مساحات دائمة).
# الاستخدام: .\rb_build_history_apks.ps1 -Tag B17 -Sha f06963d
param(
  [string]$Tag = 'B17',
  [string]$Sha = '',
  [string]$OutDir = 'rb_build_out'
)
$repo = 'F:\Ai\agent folders\Editing By opencode\opencodeGLM5.3FLASHA'
$wt = Join-Path $env:TEMP ("rb_wt_" + $Tag + '_' + (Get-Date -Format 'HHmmss'))
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Set-Location $repo
if (-not $Sha) { $Sha = git rev-parse HEAD }
Write-Output ("===== BUILD " + $Tag + " @ " + $Sha + " START " + (Get-Date -Format 'HH:mm:ss') + " =====")
$addOut = git worktree add --detach $wt $Sha 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { Write-Output ("BUILD " + $Tag + " ADD-FAILED: " + $addOut); exit 1 }
Push-Location $wt
& .\gradlew.bat :app:assembleDebug --console=plain 2>&1 | Out-File -FilePath (Join-Path $OutDir ('built_' + $Tag + '_gradle.log')) -Encoding utf8
$rc = $LASTEXITCODE
Pop-Location
Write-Output ("GRADLECODE " + $Tag + " = " + $rc)
if ($rc -eq 0) {
  $apk = Join-Path $wt 'app\build\outputs\apk\debug\app-debug.apk'
  $dest = Join-Path $OutDir ($Tag + '-app-debug.apk')
  Copy-Item $apk $dest -Force
  Write-Output ("BUILD " + $Tag + " OK size=" + (Get-Item $dest).Length)
} else {
  Write-Output ("BUILD " + $Tag + " FAIL rc=" + $rc)
}
git worktree remove --force $wt 2>&1 | Out-Null
git worktree prune 2>&1 | Out-Null
Write-Output "===== BUILD DONE ====="
