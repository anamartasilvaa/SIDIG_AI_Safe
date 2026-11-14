param(
    [string]$OutDir = "$PSScriptRoot\certs",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$infra      = Split-Path $PSScriptRoot -Parent
$envFile    = Join-Path $infra ".env"
$envExample = Join-Path $infra ".env.example"

if ((Test-Path "$OutDir\aisafe.pem") -and -not $Force) {
    Write-Host "Os certificados ja existem em $OutDir (use -Force para os regenerar)."
    exit 0
}

function New-Secret([int]$Length) {
    $chars = [char[]]"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

# ---------- 1) .env com segredos aleatorios ----------
if (-not (Test-Path $envFile)) { Copy-Item $envExample $envFile }
$values  = @{}
$updated = foreach ($line in (Get-Content $envFile)) {
    if ($line -match '^\s*([A-Z_]+)=(.*)$') {
        $key = $Matches[1]
        $val = $Matches[2]
        if ($val -eq 'CHANGE_ME') {
            $len = 24
            if ($key -eq 'JWT_SECRET') { $len = 48 }     # HS256 exige pelo menos 32 bytes
            $val  = New-Secret $len
            $line = "$key=$val"
            Write-Host "[1/3] $key gerado em $envFile"
        }
        $values[$key] = $val
    }
    $line
}
Set-Content -Path $envFile -Value $updated -Encoding ASCII
$pw = $values['KEYSTORE_PASSWORD']
if (-not $pw) { throw "KEYSTORE_PASSWORD nao definido no .env" }

# ---------- 2) certificado com SAN ----------
$keytool = "keytool"
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) { $keytool = "$env:JAVA_HOME\bin\keytool.exe" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Remove-Item "$OutDir\*" -Force -ErrorAction SilentlyContinue

$san = "SAN=dns:localhost,dns:gateway,dns:keycloak," +
       "dns:aircraft-1,dns:aircraft-2,dns:maintenance-1,dns:maintenance-2," +
       "dns:airports-routes-1,dns:airports-routes-2,dns:flight-operations-1,dns:flight-operations-2," +
       "dns:rabbitmq-1,dns:rabbitmq-2,dns:rabbitmq-3,ip:127.0.0.1"

& $keytool -genkeypair -alias aisafe-key -keyalg RSA -keysize 2048 -validity 365 `
    -storetype PKCS12 -keystore "$OutDir\keystore.p12" -storepass $pw `
    -dname "CN=localhost, O=AISafe, C=PT" -ext $san
if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair falhou" }

& $keytool -exportcert -alias aisafe-key -keystore "$OutDir\keystore.p12" -storepass $pw -rfc -file "$OutDir\aisafe.pem"
if ($LASTEXITCODE -ne 0) { throw "keytool -exportcert falhou" }

& $keytool -importcert -alias aisafe -file "$OutDir\aisafe.pem" -keystore "$OutDir\truststore.p12" `
    -storetype PKCS12 -storepass $pw -noprompt
if ($LASTEXITCODE -ne 0) { throw "keytool -importcert falhou" }
Write-Host "[2/3] keystore.p12, truststore.p12 e aisafe.pem criados em $OutDir"

# ---------- 3) chave privada em PEM para o NGINX ----------
$openssl = "C:\Program Files\Git\usr\bin\openssl.exe"
if (-not (Test-Path $openssl)) {
    $found = Get-Command openssl -ErrorAction SilentlyContinue
    if ($found) { $openssl = $found.Source } else { throw "openssl nao encontrado (instale o Git for Windows)" }
}
& $openssl pkcs12 -in "$OutDir\keystore.p12" -nocerts -nodes -passin "pass:$pw" -out "$OutDir\aisafe.key"
if ($LASTEXITCODE -ne 0) { throw "openssl falhou" }
Write-Host "[3/3] aisafe.key criada. Pronto: docker compose -f ..\docker-compose.secure.yml up --build"
Write-Host "      (Em Linux/macOS, os ficheiros em certs\ precisam de ser legiveis pelos containers: chmod 644.)"
