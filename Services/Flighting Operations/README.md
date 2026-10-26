# AISafe – Flight Operations Service

Serviço do sistema distribuído **AISafe Flight Management System** (Assignment 1) responsável pelo
agendamento de voos, pela atribuição de aviões a rotas (com restrições de alcance e capacidade), pela consulta de
disponibilidade, pelo ciclo de vida dos voos, pela **reatribuição de voos quando um avião é imobilizado (grounding)** e
pelo relatório de utilização de rotas.

O serviço corre em **3 réplicas**, cada uma com a sua própria base de dados. Os voos são **replicados de forma
assíncrona** entre réplicas (consistência eventual). Quando uma réplica ainda não tem os dados pedidos, consulta as
outras (*forwarding peer-to-peer*) e agrega as respostas. Os clientes entram pela **API Gateway (NGINX)** e
autenticam-se no **Keycloak**. Cada réplica valida o JWT e aplica as regras de acesso por perfil (**RBAC**). Toda a
comunicação usa **HTTPS**.

---

## 1. Arquitetura

| Componente                                  | Responsabilidade                                                                                                                                           |
|---------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ScheduledFlightController`                 | API pública (`/api/scheduled-flights`). Leituras distribuídas, respostas HATEOAS, auditoria das escritas                                                   |
| `InternalScheduledFlightController`         | API interna (`/internal/scheduled-flights`), usada **apenas** entre réplicas e pelo serviço Aircraft. Responde só com dados locais e **nunca reencaminha** |
| `ScheduledFlightService`                    | Orquestração: local vs distribuído, agregação, AP/CP, replicação, sincronização no arranque, lock por avião, **grounding** (`groundAircraft`)              |
| `ScheduledFlight` + value objects           | Agregado: `FlightId` (UUID), `RouteId`, `RegistrationNumber`, máquina de estados `FlightStatus`, `revision` para convergência, `reassignAircraft()`        |
| `FlightSchedulingPolicy`                    | Domain service: rota ativa, avião `ACTIVE`, alcance, capacidade, conflitos de horário (2 h)                                                                |
| `FlightAssignmentValidator`                 | Obtém a rota e o avião dos outros serviços e traduz o JSON (`RouteInfo` / `AircraftInfo`, *Anti-Corruption Layer*)                                         |
| `FlightReplicator`                          | Replicação assíncrona dos voos, com cópias pendentes e reenvio a cada 5 s                                                                                  |
| `PeerClient`                                | Comunicação com as outras réplicas **em paralelo** (`findFirst`, `collectAll`, `forwardPut`)                                                               |
| `ReplicatedServiceClient`                   | Cliente para os outros serviços: round-robin + failover entre réplicas                                                                                     |
| `RemoteCaller` + `CircuitBreaker`           | Timeouts, retry com backoff exponencial e circuit breaker por destino                                                                                      |
| `HttpClientConfig`                          | `RestTemplate` HTTPS: envia `X-API-Key` e `X-Origin-Instance` e **reencaminha o JWT** do utilizador aos outros serviços                                    |
| `JwtAuthenticationFilter` + `TokenDecoders` | Valida o JWT: **RS256 do Keycloak** (chave pública via JWKS, emissor, expiração) ou HS256 de desenvolvimento                                               |
| `SecurityConfig`                            | Regras RBAC por endpoint e método, sem sessão, *deny by default*                                                                                           |
| `SecurityProblemHandler`                    | Respostas 401 / 403 em `ProblemDetail` e registo `DENIED` na auditoria                                                                                     |
| `ApiKeyFilter`                              | Exige `X-API-Key` em `/internal/**` e regista cada acesso (logger `AUDIT`)                                                                                 |
| `AuditLog`                                  | Regista quem criou ou alterou cada voo                                                                                                                     |
| `GlobalExceptionHandler`                    | Traduz exceções para códigos HTTP (`ProblemDetail`)                                                                                                        |

Infraestrutura comum (pasta `infrastructure/` na raiz do repositório): **API Gateway NGINX** (`https://localhost:8443`)
e **Keycloak** (`http://localhost:8180`, realm `aisafe`). Ver `infrastructure/README.md`.

Serviços com que este serviço colabora:

| Serviço            | Portas      | Utilização                                                                                           |
|--------------------|-------------|------------------------------------------------------------------------------------------------------|
| Airports & Routes  | 8091        | Valida a rota (ativa, capacidade mínima)                                                             |
| Aircraft           | 8071–8073   | Valida o avião (`GET /api/aircraft/{reg}`: estado, alcance, capacidade) e dispara o grounding        |
| Maintenance        | 8075–8077   | Não é chamado diretamente; atualiza o estado do avião no Aircraft, que o FO consulta                 |

Diagramas (pasta `Documentos/`):
- `01-c4-context`, `02-c4-container`: sistema, gateway, Keycloak e os 4 serviços
- `03-c4-component-flight-operations`: C4 nível 3 deste serviço
- `04-physical-view`: vista física (portas, réplicas, BDs)
- `05-class-diagram-flight-operations`: modelo de domínio
- `06-sd-schedule-flight`: agendamento de ponta a ponta (login → gateway → validação → replicação)
- `07-sd-replication`: replicação assíncrona, reenvio e sincronização no arranque
- `08-sd-change-flight-status`: mudança de estado
- `09-sd-route-utilization`: US229, agregação sem duplicados e resposta parcial
- `sd-get-flight-forwarding`, `sd-check-availability`: forwarding de leitura e disponibilidade

---

## 2. Pré-requisitos

- JDK 17 ou superior (o projeto compila com Java 17; foi testado com o JDK 25) e Maven (ou IntelliJ IDEA)
- **Keycloak** e **NGINX** a correr (ver `infrastructure/README.md`). O Keycloak exige Java 21 ou superior
- Certificado de desenvolvimento do grupo (`keystore.p12`) e a respetiva truststore (secção 4.1)
- Serviços **Airports & Routes** e **Aircraft** a correr (secção 4.2). O Maintenance só é necessário para o fluxo de
  manutenção completo
- Postman (testes distribuídos)

---

## 3. Configuração

Tudo em `src/main/resources/application.properties`, alterável por **variáveis de ambiente** (sem valores fixos no código).

| Variável                                                 | Omissão                                                                | Descrição                                                                    |
|----------------------------------------------------------|------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `PORT`                                                   | `8081`                                                                 | Porta da réplica                                                             |
| `INSTANCE_ID`                                            | `fo-1`                                                                 | Identificador da réplica (logs e nome da BD)                                 |
| `PEERS`                                                  | *(vazio)*                                                              | URLs **HTTPS** das **outras** réplicas, separadas por vírgulas               |
| `ROUTES_URLS`                                            | `https://localhost:8091`                                               | Réplicas do Airports & Routes Service                                        |
| `AIRCRAFT_URLS`                                          | `https://localhost:8071,https://localhost:8072,https://localhost:8073` | Réplicas do Aircraft Service                                                 |
| `JWT_JWK_SET_URI`                                        | `http://localhost:8180/realms/aisafe/protocol/openid-connect/certs`    | Chave pública do Keycloak usada para validar os tokens                       |
| `JWT_ISSUER`                                             | `http://localhost:8180/realms/aisafe`                                  | Emissor esperado no token (`iss`)                                            |
| `ACCEPT_DEV_TOKENS`                                      | `true`                                                                 | Aceita tokens HS256 de desenvolvimento; **`false` em produção**              |
| `JWT_SECRET`                                             | `change-this-development-secret-key-with-at-least-32-bytes`            | Segredo dos tokens HS256 de desenvolvimento (JUnit, `DevTokenGenerator`)     |
| `API_KEY`                                                | `sidis-dev-key`                                                        | Chave partilhada entre réplicas e com o Aircraft (`/internal/**`)            |
| `SSL_ENABLED` / `SSL_KEYSTORE` / `SSL_KEYSTORE_PASSWORD` | `true` / `classpath:keystore.p12` / `password`                         | HTTPS da réplica                                                             |
| `REPLICATION_ENABLED`                                    | `true`                                                                 | `false`: cada voo fica só na réplica onde foi criado (demonstrar forwarding) |
| `REQUIRE_COMPLETE_CONFLICT_CHECK`                        | `false`                                                                | `false` = AP (aceita com aviso); `true` = CP (recusa com 503)                |

Outras propriedades:

| Propriedade                                  | Valor         | Descrição                                                   |
|----------------------------------------------|---------------|-------------------------------------------------------------|
| `app.http.connect-timeout` / `read-timeout`  | `1s` / `2s`   | Timeouts das chamadas remotas                               |
| `app.http.max-attempts` / `initial-backoff`  | `2` / `200ms` | Retry com backoff exponencial                               |
| `app.http.circuit-breaker.failure-threshold` | `3`           | Falhas seguidas até o circuito abrir                        |
| `app.http.circuit-breaker.open-duration`     | `10s`         | Tempo com o circuito aberto                                 |
| `app.replication.retry-interval`             | `5s`          | Reenvio das cópias a réplicas que estavam em baixo          |
| `app.scheduling.min-gap-hours`               | `2`           | Intervalo mínimo entre voos do mesmo avião                  |
| `app.security.jwt.jwk-set-uri` / `issuer`    | ver acima     | Configuração do Keycloak (`JWT_JWK_SET_URI`, `JWT_ISSUER`)  |
| `app.security.jwt.accept-dev-tokens`         | `true`        | Tokens HS256 de desenvolvimento (`ACCEPT_DEV_TOKENS`)       |

---

## 4. Como executar

### 4.1 Certificados (uma vez só)

Copiar o `keystore.p12` do grupo para `src/main/resources/` e para a pasta `certs/` do serviço. Na pasta `certs/`:

```powershell
keytool -exportcert -alias aisafe-key -keystore keystore.p12 -storetype PKCS12 -storepass password -file aisafe.crt
keytool -importcert -alias aisafe -file aisafe.crt -keystore truststore.p12 -storetype PKCS12 -storepass password -noprompt
```

**Os ficheiros de `certs/` não são submetidos para o repositório.**

### 4.2 Infraestrutura e serviços externos

Ordem de arranque:

1. Keycloak e gateway: `cd infrastructure` → `.\start-infra.ps1`
   (o Keycloak precisa de Java 21+: `$env:JAVA_HOME = "<caminho do JDK 21+>"` antes de arrancar)
2. **Airports & Routes** (porta 8091). Com uma só réplica, usar o *Program argument* `--peer.urls=`
3. **Aircraft** (portas 8071–8073). No arranque cria o modelo A320 e os aviões `CS-TTA` (`ACTIVE`, 180 lugares),
   `CS-TTB` (`ACTIVE`, 120 lugares) e `CS-TTM` (`UNDER_MAINTENANCE`, 180 lugares)
4. **Maintenance** (portas 8075–8077)
5. As 3 réplicas do Flight Operations (secção 4.3)

Todos os serviços usam o mesmo `keystore.p12` e as mesmas VM options da truststore.

### 4.3 Três réplicas no IntelliJ

Três *Run Configurations* de `FlightOperationsApplication`.

**VM options** (iguais nas três):
```
-Djavax.net.ssl.trustStore="<pasta do serviço>\certs\truststore.p12" -Djavax.net.ssl.trustStorePassword=password -Djavax.net.ssl.trustStoreType=PKCS12
```

| Réplica | Environment variables                                                            |
|---------|----------------------------------------------------------------------------------|
| FO-1    | `PORT=8081;INSTANCE_ID=fo-1;PEERS=https://localhost:8082,https://localhost:8083` |
| FO-2    | `PORT=8082;INSTANCE_ID=fo-2;PEERS=https://localhost:8081,https://localhost:8083` |
| FO-3    | `PORT=8083;INSTANCE_ID=fo-3;PEERS=https://localhost:8081,https://localhost:8082` |

### 4.4 Verificar

- No log de cada réplica:
    - `Tomcat started on port 808x (https)`;
    - `Replicacao ATIVA`;
    - `Peers configurados: [...]`;
    - `JWT: Keycloak RS256 = http://localhost:8180/...`.
- `GET https://localhost:808x/actuator/health` (direto, sem token) devolve `"status": "UP"`.

### 4.5 Usar a API através da gateway (PowerShell)

```powershell
$gw = "https://localhost:8443"
$token = (curl.exe -k -s -X POST "$gw/realms/aisafe/protocol/openid-connect/token" `
  -d "grant_type=password&client_id=aisafe-client&username=atcc.user&password=atcc123" | ConvertFrom-Json).access_token
'{"routeId":"RT-OPOLIS","registrationNumber":"CS-TTA","scheduledDateTime":"2027-03-15T10:00:00"}' | Out-File -Encoding ascii voo.json
curl.exe -k -i -X POST "$gw/api/scheduled-flights" -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d "@voo.json"
```

Utilizadores de teste (realm `aisafe`):

| Utilizador      | Password      | Perfil                 |
|-----------------|---------------|------------------------|
| admin.user      | admin123      | ADMIN                  |
| atcc.user       | atcc123       | ATCC                   |
| backoffice.user | backoffice123 | BACKOFFICE             |
| tech.user       | tech123       | MAINTENANCE_TECHNICIAN |
| supervisor.user | supervisor123 | MAINTENANCE_SUPERVISOR |

Para testar sem Keycloak, `src/test/java/.../DevTokenGenerator.java` gera tokens HS256 de desenvolvimento
(aceites só com `ACCEPT_DEV_TOKENS=true`).

---

## 5. API

### 5.1 Pública – `/api/scheduled-flights` (exige `Authorization: Bearer <JWT>`)

| Método  | Endpoint                                      | Descrição                                                                    | Perfis                  | Respostas                         |
|---------|-----------------------------------------------|------------------------------------------------------------------------------|-------------------------|-----------------------------------|
| `POST`  | `/`                                           | US212 – agenda um voo (`routeId`, `registrationNumber`, `scheduledDateTime`) | ATCC, ADMIN             | 201, 400, 401, 403, 409, 502, 503 |
| `GET`   | `/{id}`                                       | Voo por id (cópia local ou via peers)                                        | ATCC, BACKOFFICE, ADMIN | 200, 400, 401, 403, 404, 503      |
| `PATCH` | `/{id}/status`                                | Muda o estado (`{"status": "DELAYED"}`), em qualquer réplica                 | ATCC, ADMIN             | 200, 400, 401, 403, 404, 409      |
| `GET`   | `/aircraft/{registrationNumber}`              | US213 – voos de um avião, agregados de todas as réplicas                     | ATCC, ADMIN             | 200, 204, 401, 403                |
| `GET`   | `/availability?registrationNumber=&dateTime=` | US205 – disponibilidade de um avião numa data/hora                           | ATCC, ADMIN             | 200, 400, 401, 403                |
| `GET`   | `/route-utilization`                          | US229 – rotas mais voadas, sem contar cópias repetidas                       | BACKOFFICE, ADMIN       | 200, 204, 401, 403                |

- Qualquer endpoint não previsto é negado (*deny by default*).
- **401**: sem token, token inválido, forjado ou expirado. **403**: perfil sem permissão. Ambos em `ProblemDetail`.
- As respostas agregadas e os agendamentos incluem `X-Partial-Response-Unreachable-Peers` quando alguma réplica não
  respondeu.
- Através da gateway, os links HATEOAS e o `Location` apontam para `https://localhost:8443`.

### 5.2 Interna – `/internal/scheduled-flights` (exige `X-API-Key`, sem JWT; bloqueada na gateway; nunca reencaminha)

| Método | Endpoint                                   | Descrição                                                                                                       |
|--------|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| `GET`  | `/`                                        | Todos os voos locais (sincronização no arranque e US229)                                                        |
| `GET`  | `/{id}`                                    | Voo local                                                                                                       |
| `PUT`  | `/{id}/status`                             | Mudança de estado local (forwarding de escrita), depois replicada                                               |
| `PUT`  | `/{id}/replica`                            | Recebe a cópia de um voo enviada por outra réplica (idempotente)                                                |
| `GET`  | `/aircraft/{registrationNumber}`           | Voos locais de um avião                                                                                         |
| `GET`  | `/route-utilization`                       | Contagens locais por rota                                                                                       |
| `POST` | `/aircraft/{registrationNumber}/grounding` | **Grounding:** reatribui os voos futuros do avião a um dos candidatos (secção 5.4); devolve o relatório em JSON |

### 5.3 Ciclo de vida do voo

```
SCHEDULED → DELAYED | IN_FLIGHT | CANCELED
DELAYED   → IN_FLIGHT | CANCELED
IN_FLIGHT → COMPLETED
COMPLETED, CANCELED → estados finais (um voo cancelado deixa de ocupar o avião)
```

### 5.4 Grounding (reatribuição de aviões)

Quando um avião fica imobilizado (por exemplo, entra em manutenção), os voos futuros que lhe estavam atribuídos têm de
passar para outro avião. O serviço Aircraft chama o endpoint interno de grounding do Flight Operations:

```
POST /internal/scheduled-flights/aircraft/{registrationNumber}/grounding
X-API-Key: <API_KEY>
Content-Type: application/json

{ "candidateRegistrationNumbers": ["CS-TTA", "CS-TTB"] }
```

Regras:

- Só são reatribuídos os voos **futuros** em estado `SCHEDULED` ou `DELAYED`. Voos `IN_FLIGHT`, `COMPLETED`,
  `CANCELED` ou já passados não são alterados.
- A lista de candidatos é limpa: ignora valores vazios, repetidos e o próprio avião imobilizado.
- Cada voo passa para o **primeiro candidato livre**, isto é, o primeiro sem conflito de horário (intervalo de 2 h) com
  os voos desse candidato, vistos em todas as réplicas. Se nenhum estiver livre, o voo **mantém o avião imobilizado** e
  isso fica no relatório.
- A troca é feita na cópia local da réplica que recebeu o pedido e incrementa a `revision`
  (`ScheduledFlight.reassignAircraft()`), pelo que é replicada às outras réplicas como qualquer outra alteração. A fusão
  de cópias (`mergeReplica`) copia também o avião. Se o voo ainda não estiver guardado nessa réplica (replicação em
  atraso ou desligada), não é reatribuído e o relatório di-lo.
- O endpoint pertence à API interna: exige `X-API-Key`, responde apenas com os dados da réplica e nunca reencaminha, e a
  gateway bloqueia-o (403).

Resposta: `200` com um **array JSON de frases**, uma por voo afetado (ou uma só, se não houver voos futuros):

```json
[
  "Flight 3ea741e6-... (RT-OPOLIS, 2027-03-15T20:00) reassigned from CS-TTB to CS-TTA.",
  "Flight 91c0b7aa-... (RT-LISLHR, 2027-03-16T09:00) kept on CS-TTB: no candidate aircraft is free at that time."
]
```

Fluxo ponta a ponta: o ATCC ou ADMIN muda o estado do avião no Aircraft
(`PATCH /api/aircraft-models/instances/{registo}/status`) e, se o avião deixou de estar `ACTIVE`, o Aircraft chama este
endpoint no FO. No log do FO aparecem `ALLOWED POST /internal/.../grounding`, `GROUNDING <avião>: [...]` e, por cada
voo, `Voo <id> reatribuido ao aviao <avião> (rev N)`.

Limitações do grounding: só verifica **conflitos de horário** no candidato. A capacidade e o alcance da rota não são
revalidados, pelo que a escolha de candidatos adequados (mesmo modelo, `ACTIVE`, lugares suficientes) é
responsabilidade do Aircraft.

---

## 6. Decisões de arquitetura

1. **Base de dados por réplica, com replicação assíncrona.** O voo é gravado na réplica que recebe o pedido, que
   responde logo. Em segundo plano, a cópia vai para as outras réplicas (`PUT /internal/.../replica`). Se uma estiver
   em baixo, a cópia fica pendente e é reenviada a cada 5 s.
2. **Convergência por versão (*last writer wins*).** Cada voo tem uma `revision`, incrementada em cada mudança de
   estado ou de avião. Uma réplica só aplica cópias mais recentes (em empate, ganha o `lastModifiedAt` mais recente).
3. **Sincronização no arranque.** Uma réplica que reinicia vai buscar os voos às outras.
4. **`FlightId` UUID gerado no domínio**, único entre réplicas sem coordenação.
5. **API interna que nunca reencaminha.** Os peers chamam sempre `/internal/**` e o `/replica` nunca volta a propagar.
   Isto impede, por construção, ciclos de forwarding e de replicação.
6. **Forwarding peer-to-peer como complemento da replicação**, em paralelo (o tempo de resposta é o do peer mais lento).
7. **Resiliência:** timeouts, retry com backoff, circuit breaker por destino, failover entre réplicas dos outros
   serviços e failover na gateway.
8. **CAP: AP, como pede o enunciado.**
    - Leituras e agendamentos respondem mesmo com réplicas em baixo e avisam o cliente.
    - Um conflito raro entre réplicas é detetado quando convergem (`CONFLITO detetado apos replicacao`).
    - Com `REQUIRE_COMPLETE_CONFLICT_CHECK=true`, o agendamento passa a CP.
9. **Segurança em camadas.** A gateway é o único ponto de entrada, mas **cada réplica valida o token e aplica o RBAC**
   (defesa em profundidade): mesmo um pedido direto a uma réplica é autenticado e autorizado.
10. **DDD.** Referências a rotas e aviões só por identidade, regras no domínio e *Anti-Corruption Layer* para os
    modelos externos.
11. **Grounding no agregado.** A mudança de avião é uma operação do próprio `ScheduledFlight` (`reassignAircraft()`),
    que valida o estado e incrementa a revisão. Assim a alteração segue o mesmo caminho de replicação e convergência
    das mudanças de estado, sem mecanismos especiais.

---

## 7. Tolerância a falhas

| Cenário                                                      | Comportamento                                                                       |
|--------------------------------------------------------------|-------------------------------------------------------------------------------------|
| Uma réplica FO em baixo (pedido pela gateway)                | A gateway tenta a seguinte (failover) e salta-a durante 10 s                        |
| Voo ainda não replicado para esta réplica                    | Forwarding em paralelo; 404 só se **todos** responderem que não existe              |
| Voo não encontrado e algum peer sem resposta                 | 503: o voo pode existir nessa réplica (nunca um 404 falso)                          |
| Réplica onde o voo foi criado em baixo                       | As outras têm a cópia: o voo continua disponível                                    |
| Uma réplica em baixo (leitura agregada)                      | Resposta com os restantes dados + header de resposta parcial                        |
| Uma réplica em baixo (agendamento)                           | AP: 201 com header de resposta parcial; a cópia é entregue quando ela voltar        |
| Réplica reinicia (BD em memória vazia)                       | Sincronização no arranque com as outras réplicas                                    |
| Réplica falha repetidamente                                  | Circuit breaker: deixa de ser contactada durante 10 s                               |
| Uma réplica de outro serviço em baixo                        | Failover para a seguinte                                                            |
| Todas as réplicas de outro serviço em baixo                  | Agendamento → 503; disponibilidade → 200 com `available: false` e o motivo          |
| Dois pedidos simultâneos para o mesmo avião (mesma réplica)  | Lock por avião: só um é aceite, o outro recebe 409                                  |
| Conflito entre réplicas diferentes durante uma partição      | Ambos aceites (AP); conflito detetado e registado quando convergem                  |
| Alterações concorrentes ao mesmo voo em réplicas diferentes  | Convergem para a mais recente (`revision`, `lastModifiedAt`)                        |
| Grounding aplicado numa réplica                              | A revisão incrementada e o novo avião são replicados às outras réplicas             |

---

## 8. Segurança

Perfis do AISafe: **Admin, Backoffice, ATCC, Maintenance Technician, Maintenance Supervisor**.

- **Autenticação (Keycloak):**
    - o utilizador faz login no Keycloak através da gateway e recebe um **JWT assinado com RS256**;
    - cada réplica valida a assinatura com a **chave pública do realm** (JWKS, atualizada automaticamente), o emissor e
      a expiração;
    - o utilizador e os perfis vêm do próprio token (`roles` ou `realm_access.roles`);
    - não há sessão no servidor;
    - são recusados (401) os tokens em falta, expirados, sem assinatura (`alg: none`), com o conteúdo alterado ou
      assinados com outra chave.
- **Autorização (RBAC):** regras por endpoint e método (tabela 5.1); tudo o resto é negado.
- **Gateway:**
    - ponto de entrada único, em HTTPS;
    - bloqueia `/internal/**`, `/actuator/**` e `/h2-console/**`;
    - responde 404 a caminhos sem serviço;
    - aplica rate limiting (10 pedidos/s por IP, burst 20 → 429).
- **Proteção do canal (TLS):** HTTPS em todas as ligações (cliente → gateway → réplicas → outros serviços). A gateway
  valida o certificado das réplicas e as réplicas usam a truststore do grupo.
- **Propagação da identidade:** o JWT do utilizador é reencaminhado nas chamadas aos outros serviços, que aplicam as
  suas próprias regras.
- **Entre réplicas e com o Aircraft:** header `X-API-Key` em `/internal/**`, comparado em tempo constante. Inclui o
  endpoint de grounding.
- **Auditoria (logger `AUDIT`):**
    - operações: `User: atcc.user - Action: CREATE - Target ID: <id>` (também `UPDATE_STATUS ...`);
    - acessos negados: `DENIED 401 ... reason=...` e `DENIED 403 ... user=... roles=[...]`;
    - API interna: `ALLOWED` / `DENIED` com a réplica de origem;
    - os tokens nunca são escritos nos logs.
- **Erros sem detalhes internos:** `ProblemDetail` normalizado.

---

## 9. Testes

### 9.1 JUnit – 77 testes

`src/test/java`:
- **domínio:** value objects, máquina de estados, agregado, regras de agendamento e fusão de cópias (`ReplicaMergeTest`);
- **grounding:** `AircraftReassignmentTest` (reatribuição só em `SCHEDULED`/`DELAYED`, incremento da revisão);
- **infraestrutura:**
    - circuit breaker;
    - `PeerClient` (paralelismo, peers em falha, resposta parcial, 503 em vez de 404);
    - `FlightReplicator` (envio, cópias pendentes, replicação desligada);
- **segurança:**
    - `JwtAuthenticationFilterTest`: tokens do Keycloak RS256 (chave errada, outro emissor) e HS256 de desenvolvimento
      (assinatura, expiração, sem utilizador, modo de produção);
    - `SecurityRulesTest`: regras RBAC com o Spring Security real;
    - `HttpClientConfigTest`: propagação do JWT e da API key.

Executar: botão direito em `src/test/java` → **Run 'All Tests'**, ou `mvn test`.

### 9.2 Postman – `tests/AISafe-Gateway-Keycloak.postman_collection.json`

São 123 pedidos, todos através da gateway e com tokens do Keycloak, e **todos com testes**. Os tokens são obtidos e
renovados automaticamente, e cada execução usa datas únicas.

| Pasta | Conteúdo                                                                                                                     |
|-------|------------------------------------------------------------------------------------------------------------------------------|
| 00    | Login dos 5 perfis, password errada, JWKS                                                                                    |
| 01    | Gateway: round-robin, bloqueio de endpoints internos, 404, HTTPS e cabeçalhos, rate limiting                                 |
| 02    | Autenticação: sem token, mal formado, forjado, `alg: none`, expirado, outro segredo, sem perfis                              |
| 03    | RBAC: 6 endpoints × 5 perfis                                                                                                 |
| 04    | Regras de negócio: conflitos de 2 h, manutenção, capacidade, rota inativa, validações, disponibilidade, ciclo de vida, US229 |
| 05    | Replicação: cópia local em cada réplica, propagação de alterações, API interna protegida                                     |
| 06    | Airports & Routes através da gateway                                                                                         |
| 07    | **Manual:** falhas (FO-2, Airports, Aircraft), forwarding com replicação desligada, modo CP                                  |

Executar:
1. Importar a coleção.
2. Em **Settings → General**, desligar **SSL certificate verification**.
3. Abrir o **Collection Runner**, selecionar as pastas **00–06** e pôr **Delay 100 ms**.
4. A pasta 07 corre-se pedido a pedido: o nome de cada pedido diz o que parar ou ligar antes.

---

## 10. Limitações conhecidas e trabalho futuro

| Limitação                                                       | Impacto                                                                             | Trabalho futuro                                           |
|-----------------------------------------------------------------|-------------------------------------------------------------------------------------|-----------------------------------------------------------|
| Conflitos entre réplicas durante uma partição (AP)              | Dois voos do mesmo avião podem ser aceites; detetados, mas não resolvidos           | Particionar agendamentos por matrícula ou quórum          |
| *Last writer wins*                                              | Em alterações concorrentes ao mesmo voo, uma perde-se                               | Relógios vetoriais ou resolução no domínio                |
| Grounding sem candidato livre                                   | O voo mantém o avião imobilizado (fica no relatório)                                | Notificar o operador / cancelar o voo de forma controlada |
| Grounding só valida conflitos de horário                        | Um candidato com menos lugares ou alcance pode receber um voo que não cumpre a rota | Revalidar rota e capacidade do candidato na reatribuição  |
| BDs H2 em memória                                               | Mitigado pela sincronização; se as 3 réplicas reiniciarem, os dados perdem-se       | Base de dados persistente                                 |
| Cópias pendentes em memória                                     | Se a origem reiniciar antes de entregar, a cópia só chega por sincronização         | Fila persistente / broker de mensagens                    |
| Uma só instância da gateway e do Keycloak                       | Pontos únicos de falha da infraestrutura                                            | Várias instâncias (Docker, BD partilhada para o Keycloak) |
| Keycloak local em HTTP; tokens HS256 de desenvolvimento aceites | Adequado só a desenvolvimento                                                       | Keycloak em HTTPS; `ACCEPT_DEV_TOKENS=false`              |
| Certificado partilhado; API key entre réplicas                  | Sem identidade própria por serviço                                                  | Certificados por serviço emitidos por uma CA; mTLS        |
| `minimumRange` não exposto pelo Airports & Routes               | Regra do alcance não é verificada com o serviço real                                | Acrescentar o campo ao `RouteViewDTO`                     |
| `X-Request-Id` da gateway não aparece nos logs do serviço       | Correlação gateway ↔ serviço feita à mão                                            | Incluir o id no MDC dos logs                              |
| Datas sem fuso horário; lista de peers estática                 | Ambiguidade entre fusos; reconfigurar para mudar réplicas                           | `OffsetDateTime`/UTC; *service discovery*                 |

---

## 11. Estrutura do projeto

```
src/main/java/pt/isep/psoft/aisafe/flight_operations/
├── api/              controllers (público e interno) e tratamento de erros
├── application/      ScheduledFlightService, FlightAssignmentValidator, DTOs
├── domain/           agregado, value objects, FlightStatus, FlightSchedulingPolicy
├── infrastructure/   PeerClient, FlightReplicator, RemoteCaller, CircuitBreaker, ReplicatedServiceClient
├── repositories/     ScheduledFlightRepository
├── security/         JwtAuthenticationFilter, TokenDecoders, SecurityProblemHandler, AuditLog, Roles
└── config/           SecurityConfig, HttpClientConfig, ApiKeyFilter, beans de domínio
src/main/resources/   application.properties, keystore.p12
src/test/java/...     testes JUnit (incl. AircraftReassignmentTest), DevTokenGenerator
tests/                coleção Postman
certs/                truststore.p12 (local, não submetido)
```