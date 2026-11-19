# ==============================================================================
# STOCKGUARD — Development Self-Signed TLS Certificate Generator (PowerShell)
# ==============================================================================
# Generates local self-signed TLS certificates for development and local testing.
# NEVER use in production environments. Production requires valid CA-issued certs.
# ==============================================================================

param(
    [switch]$Force,
    [string]$TargetDir = ""
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir

if (-not $TargetDir) {
    $TargetDir = Join-Path $RepoRoot "nginx\ssl"
}

if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}

$CertFile = Join-Path $TargetDir "cert.pem"
$KeyFile = Join-Path $TargetDir "key.pem"

if ((Test-Path $CertFile) -or (Test-Path $KeyFile)) {
    if (-not $Force) {
        Write-Warning "Existing TLS certificate files detected in ${TargetDir}:"
        if (Test-Path $CertFile) { Write-Host "  - $CertFile" }
        if (Test-Path $KeyFile) { Write-Host "  - $KeyFile" }
        Write-Error "Refusing to overwrite existing certificates without -Force parameter."
        exit 1
    } else {
        Write-Host "Notice: Overwriting existing development TLS certificates (-Force specified)." -ForegroundColor Yellow
    }
}

# Check for OpenSSL
$opensslCmd = Get-Command openssl -ErrorAction SilentlyContinue
if (-not $opensslCmd -and (Test-Path "C:\Program Files\Git\usr\bin\openssl.exe")) {
    $opensslCmd = Get-Command "C:\Program Files\Git\usr\bin\openssl.exe" -ErrorAction SilentlyContinue
}

if ($opensslCmd) {
    Write-Host "Generating development self-signed TLS certificates using OpenSSL..." -ForegroundColor Cyan

    $tempConfigFile = [System.IO.Path]::GetTempFileName()
    $configContent = @"
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
x509_extensions = v3_req

[dn]
C = US
ST = Development
L = Local
O = StockGuard Development
OU = Engineering
CN = localhost

[v3_req]
subjectAltName = @alt_names
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth

[alt_names]
DNS.1 = localhost
DNS.2 = *.localhost
DNS.3 = stockguard.local
DNS.4 = *.stockguard.local
IP.1 = 127.0.0.1
IP.2 = ::1
"@
    Set-Content -Path $tempConfigFile -Value $configContent -Encoding Ascii

    $oldEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $opensslCmd.Source req -x509 -nodes -days 365 -newkey rsa:2048 `
            -keyout "$KeyFile" `
            -out "$CertFile" `
            -config "$tempConfigFile" 2>$null
    } finally {
        $ErrorActionPreference = $oldEap
        if (Test-Path $tempConfigFile) {
            Remove-Item -Path $tempConfigFile -Force -ErrorAction SilentlyContinue
        }
    }
} else {
    Write-Error "'openssl' executable not found in PATH. Please install OpenSSL or Git for Windows to generate PEM certificates."
    exit 1
}

Write-Host "================================================================================" -ForegroundColor Green
Write-Host "DEVELOPMENT TLS CERTIFICATES SUCCESSFULLY GENERATED:" -ForegroundColor Green
Write-Host "  Certificate : $CertFile"
Write-Host "  Private Key : $KeyFile"
Write-Host "  Subject     : CN=localhost (StockGuard Development)"
Write-Host "  SAN Entries : localhost, *.localhost, stockguard.local, 127.0.0.1, ::1"
Write-Host "================================================================================" -ForegroundColor Green
Write-Host "CAUTION: This certificate is strictly for local development and testing." -ForegroundColor Yellow
Write-Host "Browsers will show a self-signed security warning until trusted." -ForegroundColor Yellow
Write-Host "DO NOT USE THIS CERTIFICATE IN PRODUCTION." -ForegroundColor Red
