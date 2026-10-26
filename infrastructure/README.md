# AISafe – Infraestrutura (Keycloak + API Gateway)

```
Cliente (Postman/curl)
   │  HTTPS :8443
   ▼
API Gateway (NGINX) ──► /realms/...                Keycloak :8180 (login, emite JWT RS256)
   │                ──► /api/scheduled-flights    Flight Operations :8081/:8082/:8083 (round-robin)
   │                ──► /api/airports, /api/routes Airports & Routes :8091(/:8092/:8093)
   │                ✖   /internal/, /actuator/, /h2-console/  (bloqueados)
   ▼
Cada serviço valida o JWT com a chave pública do Keycloak (JWKS) e aplica RBAC.
```

| Pasta | Conteúdo |
|---|---|
| `keycloak/aisafe-realm.json` | Realm `aisafe`: 5 perfis, 5 utilizadores de teste, cliente `aisafe-client` |
| `gateway/nginx.conf` | Gateway: HTTPS, routing, round-robin, failover, rate limiting (10 req/s por IP, burst 20), bloqueio de endpoints internos |
| `start-infra.ps1` | Copia as configurações, gera os certificados PEM e arranca Keycloak + NGINX |

## Instalação (uma vez)
1. **Keycloak**: descarregar o zip em keycloak.org/downloads e descomprimir em `C:\keycloak` (precisa de Java 21+).
2. **NGINX**: descarregar *nginx/Windows* (Stable) em nginx.org/en/download.html e descomprimir em `C:\nginx`.
3. **Git for Windows** (traz o `openssl`, usado para converter o `keystore.p12` em PEM).

## Arrancar
```powershell
cd <repo>\infrastructure
.\start-infra.ps1          # Keycloak + gateway
```
Depois arrancar no IntelliJ: FO-1, FO-2, FO-3, Airports & Routes e o stub do Aircraft (`StubExternalServices`).

Parar a gateway: `.\start-infra.ps1 -Stop` (o Keycloak para com Ctrl+C na janela dele).

## Utilizadores de teste (realm `aisafe`)
| Utilizador | Password | Perfil |
|---|---|---|
| admin.user | admin123 | ADMIN |
| atcc.user | atcc123 | ATCC |
| backoffice.user | backoffice123 | BACKOFFICE |
| tech.user | tech123 | MAINTENANCE_TECHNICIAN |
| supervisor.user | supervisor123 | MAINTENANCE_SUPERVISOR |

Consola de administração do Keycloak: http://localhost:8180 (admin/admin, só em desenvolvimento).

## Exemplo (PowerShell)
```powershell
$gw = "https://localhost:8443"
$token = (curl.exe -k -s -X POST "$gw/realms/aisafe/protocol/openid-connect/token" `
  -d "grant_type=password&client_id=aisafe-client&username=atcc.user&password=atcc123" | ConvertFrom-Json).access_token
curl.exe -k -i "$gw/api/scheduled-flights/aircraft/CS-TTA" -H "Authorization: Bearer $token"
```

## Configuração dos serviços
Cada serviço valida os tokens com estas propriedades (valores por omissão já apontam para o Keycloak local):
```properties
app.security.jwt.jwk-set-uri=${JWT_JWK_SET_URI:http://localhost:8180/realms/aisafe/protocol/openid-connect/certs}
app.security.jwt.issuer=${JWT_ISSUER:http://localhost:8180/realms/aisafe}
app.security.jwt.accept-dev-tokens=${ACCEPT_DEV_TOKENS:true}   # tokens HS256 de teste; false em produção
```
Dependência Maven: `spring-boot-starter-oauth2-resource-server`.
Novo serviço (Aircraft, Maintenance): copiar `security/TokenDecoders.java` e `security/JwtAuthenticationFilter.java`
e acrescentar o `upstream` e a `location` respetivos no `gateway/nginx.conf` (já lá estão comentados).

## O que NÃO vai para o Git
Chaves e certificados (`*.key`, `*.pem`, pastas `certs/`) – são gerados localmente a partir do `keystore.p12` de desenvolvimento.
