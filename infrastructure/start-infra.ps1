# =====================================================================
#  AISafe - arranque da infraestrutura (Keycloak + API Gateway NGINX)
#  Uso (PowerShell, na pasta infrastructure):   .\start-infra.ps1
#  Parar:                                        .\start-infra.ps1 -Stop
#
#  Pre-requisitos (uma vez): Keycloak em C:\keycloak, NGINX em C:\nginx,
#  Git for Windows (traz o openssl). Ver README.md.
# =====================================================================
param(
    [string]$KeycloakHome = "C:\keycloak",
    [string]$NginxHome    = "C:\nginx",
    [string]$KeyStore     = "$PSScriptRoot\..\Services\Flighting Operations\src\main\resources\keystore.p12",
    [string]$KeyStorePass = "password",
    [switch]$Stop
)

$ErrorActionPreference = "Stop"

if ($Stop) {
    Write-Host "A parar o NGINX..."
    Push-Location $NginxHome; & .\nginx.exe -s stop; Pop-Location
    Write-Host "Fecha a janela do Keycloak (Ctrl+C) para o parar."
    exit 0
}

# 1) Realm do Keycloak (importado no arranque se ainda nao existir)
$import = Join-Path $KeycloakHome "data\import"
New-Item -ItemType Directory -Force -Path $import | Out-Null
Copy-Item "$PSScriptRoot\keycloak\aisafe-realm.json" $import -Force
Write-Host "[1/4] Realm aisafe copiado para $import"

# 2) Configuracao da gateway
Copy-Item "$PSScriptRoot\gateway\nginx.conf" (Join-Path $NginxHome "conf\nginx.conf") -Force
Write-Host "[2/4] nginx.conf copiado para $NginxHome\conf"

# 3) Certificado do grupo em PEM (o NGINX nao le .p12). So gera se ainda nao existir.
$certs = Join-Path $NginxHome "conf\certs"
if (-not (Test-Path "$certs\aisafe.pem")) {
    $openssl = "C:\Program Files\Git\usr\bin\openssl.exe"
    if (-not (Test-Path $openssl)) { throw "openssl nao encontrado em $openssl (instala o Git for Windows)" }
    New-Item -ItemType Directory -Force -Path $certs | Out-Null
    & $openssl pkcs12 -in $KeyStore -nocerts -nodes -passin "pass:$KeyStorePass" -out "$certs\aisafe.key"
    & $openssl pkcs12 -in $KeyStore -clcerts -nokeys -passin "pass:$KeyStorePass" -out "$certs\aisafe.pem"
    Write-Host "[3/4] Certificados PEM gerados em $certs"
} else {
    Write-Host "[3/4] Certificados PEM ja existem em $certs"
}

# 4) Arrancar Keycloak (janela propria) e NGINX
Start-Process -FilePath (Join-Path $KeycloakHome "bin\kc.bat") `
    -ArgumentList "start-dev", "--http-port=8180", "--import-realm" `
    -WorkingDirectory (Join-Path $KeycloakHome "bin")
Push-Location $NginxHome
& .\nginx.exe -t
Start-Process -FilePath ".\nginx.exe" -WorkingDirectory $NginxHome
Pop-Location
Write-Host "[4/4] Keycloak a arrancar em http://localhost:8180 e gateway em https://localhost:8443"
Write-Host "Agora arranca os servicos no IntelliJ (FO-1..3, Airports, stub do Aircraft)."
