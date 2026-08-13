# SPDX-License-Identifier: Apache-2.0
# Pulse — Windows code-signing helper.
#
# We sign the jpackage-produced .exe with signtool.exe. The cert is
# a standard Authenticode Code Signing certificate (.pfx) — purchase
# from any CA (Sectigo / DigiCert / SSL.com are the main ones), and
# store the .pfx somewhere safe on this machine. NEVER commit the
# .pfx or its password to git.
#
# Usage:
#   1. Set env vars (or call this script with -Thumbprint if you have
#      the cert in the Windows certificate store already):
#       $env:PULSE_SIGN_PFX     = 'C:\keys\pulse-2026.pfx'
#       $env:PULSE_SIGN_PWD     = (Read-Host -AsSecureString) | ConvertFrom-SecureString
#       $env:PULSE_SIGN_TSA     = 'http://timestamp.digicert.com'
#   2. Run from project root:
#       .\tools\sign-windows.ps1
#
# If the env vars are not set, the script prints a "skipping" notice
# and exits 0 — local dev builds remain unsigned.
param(
    [string]$ExePath = "build\compose\binaries\main-release\exe\Pulse-1.0.0.exe",
    [string]$MsiPath = "build\compose\binaries\main-release\msi\Pulse-1.0.0.msi"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ExePath)) {
    Write-Host "sign-windows: $ExePath not found. Run 'gradle packageReleaseExe' first." -ForegroundColor Yellow
    exit 1
}

if (-not $env:PULSE_SIGN_PFX -or -not $env:PULSE_SIGN_PWD) {
    Write-Host "sign-windows: PULSE_SIGN_PFX / PULSE_SIGN_PWD not set. Skipping (unsigned build)." -ForegroundColor Yellow
    exit 0
}

# Resolve signtool.exe — newer Windows SDKs ship it under
# "Windows Kits\10\bin\<sdk-version>\x64\signtool.exe". The wildcard
# finds the highest installed version automatically.
$sdkRoot = "${env:ProgramFiles(x86)}\Windows Kits\10\bin"
$signtool = Get-ChildItem -Path $sdkRoot -Recurse -Filter "signtool.exe" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "x64" } |
    Sort-Object { [version]($_.Directory.Name -replace "[^\d.]", "") } -Descending |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $signtool) {
    Write-Host "sign-windows: signtool.exe not found in $sdkRoot" -ForegroundColor Red
    exit 1
}

Write-Host "sign-windows: using $signtool"

function Sign-File($Path) {
    if (-not (Test-Path $Path)) { return }
    $securePwd = ConvertTo-SecureString $env:PULSE_SIGN_PWD -Force -AsPlainText
    Write-Host "sign-windows: signing $Path"
    & $signtool sign /f $env:PULSE_SIGN_PFX /p $env:PULSE_SIGN_PWD `
        /fd SHA256 /tr $env:PULSE_SIGN_TSA /td SHA256 `
        /d "Pulse Desktop" /du "https://ownlocalml.com" `
        $Path
    if ($LASTEXITCODE -ne 0) { throw "signtool failed for $Path" }
    & $signtool verify /pa $Path
    if ($LASTEXITCODE -ne 0) { throw "signature verification failed for $Path" }
}

Sign-File $ExePath
Sign-File $MsiPath
Write-Host "sign-windows: done" -ForegroundColor Green
