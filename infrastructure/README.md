# AISafe – Infraestrutura (Keycloak + API Gateway)

## Docker Compose Services

The service-only Compose stack is in `docker-compose.services.yml`. It starts
two containers for each service and intentionally does not include the API
gateway or Keycloak for testing purposes.

```bash
docker compose -f infrastructure/docker-compose.services.yml up --build
```

Every container listens on port `80` internally. Services call one another by
hostname without ports, for example `http://aircraft-1` and
`http://aircraft-2`. The host mappings are only for direct local testing:

| Service           |        Replica 1 |        Replica 2 |
|-------------------|-----------------:|-----------------:|
| Aircraft          | `localhost:8071` | `localhost:8072` |
| Maintenance       | `localhost:8075` | `localhost:8076` |
| Flight Operations | `localhost:8081` | `localhost:8082` |
| Airports & Routes | `localhost:8091` | `localhost:8092` |

Check all replicas with:

```bash
docker compose -f infrastructure/docker-compose.services.yml ps
curl http://localhost:8071/actuator/health
curl http://localhost:8072/actuator/health
curl http://localhost:8075/actuator/health
curl http://localhost:8076/actuator/health
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8091/actuator/health
curl http://localhost:8092/actuator/health
```

Stop the stack with:

```bash
docker compose -f infrastructure/docker-compose.services.yml down
```

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

| Pasta                        | Conteúdo                                                                                                                  |
|------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `keycloak/aisafe-realm.json` | Realm `aisafe`: 5 perfis, 5 utilizadores de teste, cliente `aisafe-client`                                                |
| `gateway/nginx.conf`         | Gateway: HTTPS, routing, round-robin, failover, rate limiting (10 req/s por IP, burst 20), bloqueio de endpoints internos |
| `start-infra.ps1`            | Copia as configurações, gera os certificados PEM e arranca Keycloak + NGINX                                               |

## Instalação (uma vez)
1. **Keycloak**: descarregar o zip em keycloak.org/downloads e descomprimir em `C:\keycloak` (precisa de Java 21+).
2. **NGINX**: descarregar *nginx/Windows* (Stable) em nginx.org/en/download.html e descomprimir em `C:\nginx`.
3. **Git for Windows** (traz o `openssl`, usado para converter o `keystore.p12` em PEM).

## Arrancar
```powershell
cd <repo>\infrastructure
.\start-infra.ps1          # Keycloak + gateway
```
Depois arrancar no IntelliJ os serviços. Antes de testar, tem de estar tudo isto a correr:

| # | O quê                          | Onde arrancar                      | Porta            |
|---|--------------------------------|------------------------------------|------------------|
| 1 | Keycloak                       | `start-infra.ps1` (janela própria) | 8180 (HTTP)      |
| 2 | API Gateway (NGINX)            | `start-infra.ps1`                  | 8443 (HTTPS)     |
| 3 | Flight Operations FO-1         | IntelliJ                           | 8081             |
| 4 | Flight Operations FO-2         | IntelliJ                           | 8082             |
| 5 | Flight Operations FO-3         | IntelliJ                           | 8083             |
| 6 | Airports & Routes              | IntelliJ                           | 8091             |
| 7 | Aircraft (AC-1, AC-2, AC-3)    | IntelliJ                           | 8071, 8072, 8073 |
| 8 | Maintenance (MT-1, MT-2, MT-3) | IntelliJ                           | 8075, 8076, 8077 |

Erros típicos:
- `503 "No replica of the requested service is available."` → a gateway não chega a nenhuma réplica desse serviço (estão paradas).
- `503 "Service airports-routes/aircraft unavailable"` → o FO respondeu, mas a dependência está parada; depois de a arrancar, esperar ~10 s (circuit breaker).

Parar a gateway: `.\start-infra.ps1 -Stop` (o Keycloak para com Ctrl+C na janela dele).

## Utilizadores de teste (realm `aisafe`)
| Utilizador      | Password      | Perfil                 |
|-----------------|---------------|------------------------|
| admin.user      | admin123      | ADMIN                  |
| atcc.user       | atcc123       | ATCC                   |
| backoffice.user | backoffice123 | BACKOFFICE             |
| tech.user       | tech123       | MAINTENANCE_TECHNICIAN |
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
