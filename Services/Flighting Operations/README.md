# AISafe – Flight Operations Service

Serviço do sistema distribuído **AISafe Flight Management System** (Assignment 1) responsável pelo
agendamento de voos, pela atribuição de aviões a rotas (com restrições de alcance e capacidade), pela consulta de
disponibilidade e pelo relatório de utilização de rotas (US229).

O serviço corre em **3 réplicas**, cada uma com a sua própria base de dados. Os voos são **replicados de forma
assíncrona** entre réplicas (consistência eventual) e, quando uma réplica ainda não tem os dados pedidos, consulta as
outras (*forwarding peer-to-peer*) e agrega as respostas. Toda a comunicação usa **HTTPS** e a API pública exige
**JWT** com controlo de acesso por perfil (**RBAC**).

---

## 1. Arquitetura

| Componente                          | Responsabilidade                                                                                                                        |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `ScheduledFlightController`         | API pública (`/api/scheduled-flights`). Leituras distribuídas, respostas HATEOAS, auditoria das escritas                                |
| `InternalScheduledFlightController` | API interna (`/internal/scheduled-flights`), usada **apenas** entre réplicas. Responde só com dados locais e **nunca reencaminha**      |
| `ScheduledFlightService`            | Orquestração: consultas locais vs distribuídas, agregação, replicação, sincronização no arranque, lock por avião                        |
| `ScheduledFlight` + value objects   | Agregado: `FlightId` (UUID), `RouteId`, `RegistrationNumber`, máquina de estados `FlightStatus`, `revision` para convergência           |
| `FlightSchedulingPolicy`            | Domain service (Java puro): rota ativa, avião `ACTIVE`, alcance, capacidade, conflitos de horário                                       |
| `FlightAssignmentValidator`         | Obtém a rota e o avião dos outros serviços e traduz o JSON recebido (`RouteInfo` / `AircraftInfo`, *Anti-Corruption Layer*)             |
| `FlightReplicator`                  | Replicação assíncrona dos voos para as outras réplicas, com cópias pendentes e reenvio periódico                                        |
| `PeerClient`                        | Comunicação com as outras réplicas **em paralelo** (`findFirst`, `collectAll`, `forwardPut`)                                            |
| `ReplicatedServiceClient`           | Cliente para os outros serviços: round-robin + failover entre réplicas                                                                  |
| `RemoteCaller` + `CircuitBreaker`   | Chamadas HTTP com timeouts, retry com backoff exponencial e circuit breaker por destino                                                 |
| `HttpClientConfig`                  | `RestTemplate` partilhado: envia `X-API-Key` e `X-Origin-Instance` e **reencaminha o JWT** do utilizador aos outros serviços            |
| `SecurityConfig`                    | Regras RBAC por endpoint, sem sessão (*stateless*), *deny by default*                                                                   |
| `JwtAuthenticationFilter`           | Valida o JWT (assinatura HS256, expiração, utilizador, perfis)                                                                          |
| `SecurityProblemHandler`            | Respostas 401 / 403 em `ProblemDetail` e registo `DENIED` na auditoria                                                                  |
| `ApiKeyFilter`                      | Exige `X-API-Key` em `/internal/**` e regista cada acesso (logger `AUDIT`)                                                              |
| `AuditLog`                          | Regista quem criou ou alterou cada voo (`User - Action - Target ID`)                                                                    |
| `GlobalExceptionHandler`            | Traduz exceções para códigos HTTP (`ProblemDetail`)                                                                                     |

Diagramas (pasta `Documentos/`):
- `c4-component-flight-operations` – C4 nível 3 (componentes)
- `class-diagram-flight-operations` – modelo de domínio do serviço
- `sd-schedule-flight` – agendamento com validação nos outros serviços
- `sd-get-flight-forwarding` – leitura com forwarding peer-to-peer
- `sd-route-utilization` – US229, agregação com resposta parcial
- `sd-change-flight-status` – mudança de estado e forwarding de escrita
- `sd-check-availability` – consulta de disponibilidade

---

## 2. Pré-requisitos

- Java 17 ou superior
- Maven (ou IntelliJ IDEA)
- Postman (para os testes distribuídos)
- Certificado de desenvolvimento do grupo (`keystore.p12`) e a respetiva truststore (ver secção 4.1)
- Serviços **Airports & Routes** e **Aircraft** a correr. Para testar o serviço isoladamente, usar o stub
  `src/test/java/.../StubExternalServices.java` (ver secção 4.2)

---

## 3. Configuração

Toda a configuração está em `src/main/resources/application.properties` e pode ser alterada por **variáveis de ambiente**,
sem valores fixos no código.

| Variável                          | Omissão                                                     | Descrição                                                                    |
|-----------------------------------|-------------------------------------------------------------|------------------------------------------------------------------------------|
| `PORT`                            | `8081`                                                      | Porta da réplica                                                             |
| `INSTANCE_ID`                     | `fo-1`                                                      | Identificador da réplica (aparece nos logs e no nome da BD)                  |
| `PEERS`                           | *(vazio)*                                                   | URLs **HTTPS** das **outras** réplicas, separadas por vírgulas               |
| `ROUTES_URLS`                     | `https://localhost:8091`                                    | Réplicas do Airports & Routes Service                                        |
| `AIRCRAFT_URLS`                   | `http://localhost:8071,http://localhost:8072`               | Réplicas do Aircraft Service (stub enquanto não existir)                     |
| `API_KEY`                         | `sidis-dev-key`                                             | Chave partilhada para chamadas entre réplicas (`/internal/**`)               |
| `JWT_SECRET`                      | `change-this-development-secret-key-with-at-least-32-bytes` | Segredo HS256 do JWT, igual em todos os serviços e na gateway                |
| `SSL_ENABLED`                     | `true`                                                      | HTTPS ligado                                                                 |
| `SSL_KEYSTORE`                    | `classpath:keystore.p12`                                    | Certificado apresentado pela réplica                                         |
| `SSL_KEYSTORE_PASSWORD`           | `password`                                                  | Password do keystore                                                         |
| `REPLICATION_ENABLED`             | `true`                                                      | `false`: cada voo fica só na réplica onde foi criado (demonstrar forwarding) |
| `REQUIRE_COMPLETE_CONFLICT_CHECK` | `false`                                                     | `false` = AP (aceita com aviso); `true` = CP (recusa com 503)                |

Outras propriedades relevantes:

| Propriedade                                      | Valor         | Descrição                                                         |
|--------------------------------------------------|---------------|-------------------------------------------------------------------|
| `app.http.connect-timeout` / `read-timeout`      | `1s` / `2s`   | Timeouts das chamadas remotas                                     |
| `app.http.max-attempts` / `initial-backoff`      | `2` / `200ms` | Retry com backoff exponencial                                     |
| `app.http.circuit-breaker.failure-threshold`     | `3`           | Falhas seguidas até o circuito abrir                              |
| `app.http.circuit-breaker.open-duration`         | `10s`         | Tempo com o circuito aberto antes de testar de novo               |
| `app.replication.retry-interval`                 | `5s`          | Intervalo de reenvio das cópias a réplicas que estavam em baixo   |
| `app.scheduling.min-gap-hours`                   | `2`           | Intervalo mínimo entre voos do mesmo avião                        |

---

## 4. Como executar

### 4.1 Certificados (uma vez só)

Copiar o `keystore.p12` do grupo para `src/main/resources/` e para a pasta `certs/` do serviço. Depois, na pasta
`certs/`, criar a truststore:

```powershell
keytool -exportcert -alias aisafe-key -keystore keystore.p12 -storetype PKCS12 -storepass password -file aisafe.crt
keytool -importcert -alias aisafe -file aisafe.crt -keystore truststore.p12 -storetype PKCS12 -storepass password -noprompt
```

A truststore permite que cada réplica confie nas outras réplicas e nos outros serviços do grupo.
**Os ficheiros de `certs/` não são submetidos para o repositório.**

### 4.2 Stub dos outros serviços (só para testes isolados)

No IntelliJ: abrir `src/test/java/.../StubExternalServices.java` e clicar ▶ em `main`.
Responde em `:8071` (aviões: `CS-TTA`, `CS-TTB`, `CS-TTM`) e, se a linha das rotas estiver ativa, em `:8091`
(rotas: `OPO-LIS`, `OPO-JFK`, `OPO-FNC`). Com o serviço real Airports & Routes a correr na `8091`, a linha das rotas
tem de ficar comentada.

### 4.3 Três réplicas no IntelliJ

Criar três *Run Configurations* da classe `FlightOperationsApplication`.

**VM options** (iguais nas três):
```
-Djavax.net.ssl.trustStore="<pasta do serviço>\certs\truststore.p12" -Djavax.net.ssl.trustStorePassword=password -Djavax.net.ssl.trustStoreType=PKCS12
```

**Environment variables:**

| Réplica | Environment variables                                                            |
|---------|----------------------------------------------------------------------------------|
| FO-1    | `PORT=8081;INSTANCE_ID=fo-1;PEERS=https://localhost:8082,https://localhost:8083` |
| FO-2    | `PORT=8082;INSTANCE_ID=fo-2;PEERS=https://localhost:8081,https://localhost:8083` |
| FO-3    | `PORT=8083;INSTANCE_ID=fo-3;PEERS=https://localhost:8081,https://localhost:8082` |

### 4.4 Três réplicas na linha de comandos (PowerShell)

```powershell
$ts = '-Djavax.net.ssl.trustStore=certs/truststore.p12 -Djavax.net.ssl.trustStorePassword=password -Djavax.net.ssl.trustStoreType=PKCS12'
# terminal 1
$env:PORT="8081"; $env:INSTANCE_ID="fo-1"; $env:PEERS="https://localhost:8082,https://localhost:8083"; mvn spring-boot:run "-Dspring-boot.run.jvmArguments=$ts"
# terminal 2
$env:PORT="8082"; $env:INSTANCE_ID="fo-2"; $env:PEERS="https://localhost:8081,https://localhost:8083"; mvn spring-boot:run "-Dspring-boot.run.jvmArguments=$ts"
# terminal 3
$env:PORT="8083"; $env:INSTANCE_ID="fo-3"; $env:PEERS="https://localhost:8081,https://localhost:8082"; mvn spring-boot:run "-Dspring-boot.run.jvmArguments=$ts"
```

### 4.5 Verificar

- No log de cada réplica: `Tomcat started on port 808x (https)`, `Replicacao ATIVA` e `Peers configurados: [...]`
  com as outras duas
- `GET https://localhost:808x/actuator/health` → `"status": "UP"` (aberto, sem token)
- No Postman: desligar **Settings → General → SSL certificate verification** (certificado de desenvolvimento)

### 4.6 Tokens de teste

Enquanto a API Gateway não emitir tokens, correr `src/test/java/.../DevTokenGenerator.java` (▶ em `main`).
A consola mostra um JWT válido durante 24 h para cada perfil (ATCC, BACKOFFICE, ADMIN, MAINTENANCE_TECHNICIAN).
No Postman: separador **Authorization → Bearer Token**.

---

## 5. API

### 5.1 Pública – `/api/scheduled-flights` (exige `Authorization: Bearer <JWT>`)

| Método  | Endpoint                                      | Descrição                                                                    | Perfis                  | Respostas                         |
|---------|-----------------------------------------------|------------------------------------------------------------------------------|-------------------------|-----------------------------------|
| `POST`  | `/`                                           | US212 – agenda um voo (`routeId`, `registrationNumber`, `scheduledDateTime`) | ATCC, ADMIN             | 201, 400, 401, 403, 409, 502, 503 |
| `GET`   | `/{id}`                                       | Voo por id (cópia local ou via peers)                                        | ATCC, BACKOFFICE, ADMIN | 200, 400, 401, 403, 404, 503      |
| `PATCH` | `/{id}/status`                                | Muda o estado (`{"status": "DELAYED"}`), em qualquer réplica                 | ATCC, ADMIN             | 200, 400, 401, 403, 404, 409      |
| `GET`   | `/aircraft/{registrationNumber}`              | US213 – voos de um avião, agregados de todas as réplicas                     | ATCC, ADMIN             | 200, 204, 401, 403                |
| `GET`   | `/availability?registrationNumber=&dateTime=` | US205 – disponibilidade de um avião para uma data/hora                       | ATCC, ADMIN             | 200, 401, 403                     |
| `GET`   | `/route-utilization`                          | US229 – rotas mais voadas, sem contar cópias repetidas                       | BACKOFFICE, ADMIN       | 200, 204, 401, 403                |

- Qualquer endpoint não previsto é negado (*deny by default*).
- **401**: sem token, token inválido ou expirado. **403**: perfil sem permissão. Ambos em `ProblemDetail`.
- As respostas agregadas e os agendamentos incluem o header `X-Partial-Response-Unreachable-Peers` quando alguma
  réplica não respondeu.

Exemplo:
```http
POST https://localhost:8081/api/scheduled-flights
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
Content-Type: application/json

{ "routeId": "RT-OPOLIS", "registrationNumber": "CS-TTA", "scheduledDateTime": "2027-03-15T10:00:00" }
```

### 5.2 Interna – `/internal/scheduled-flights` (exige `X-API-Key`, sem JWT)

| Método | Endpoint                         | Descrição                                                          |
|--------|----------------------------------|--------------------------------------------------------------------|
| `GET`  | `/`                              | Todos os voos locais (sincronização no arranque e US229)           |
| `GET`  | `/{id}`                          | Voo local                                                          |
| `PUT`  | `/{id}/status`                   | Mudança de estado local (forwarding de escrita), depois replicada  |
| `PUT`  | `/{id}/replica`                  | Recebe a cópia de um voo enviada por outra réplica (idempotente)   |
| `GET`  | `/aircraft/{registrationNumber}` | Voos locais de um avião                                            |
| `GET`  | `/route-utilization`             | Contagens locais por rota                                          |

> **Estes endpoints não devem ser expostos aos clientes**, nem através da API Gateway.

### 5.3 Ciclo de vida do voo

```
SCHEDULED → DELAYED | IN_FLIGHT | CANCELED
DELAYED   → IN_FLIGHT | CANCELED
IN_FLIGHT → COMPLETED
COMPLETED, CANCELED → estados finais
```

---

## 6. Decisões de arquitetura

1. **Base de dados por réplica, com replicação assíncrona.** O voo é gravado na réplica que recebe o pedido, que
   responde logo ao cliente. Em segundo plano, a cópia é enviada às outras réplicas (`PUT /internal/.../replica`).
   Se uma réplica estiver em baixo, a cópia fica pendente e é reenviada a cada 5 s até ela voltar.
2. **Convergência por versão (*last writer wins*).** Cada voo tem uma `revision`, incrementada em cada mudança de
   estado. Uma réplica só aplica cópias mais recentes do que a sua (em empate, ganha a de `lastModifiedAt` mais
   recente). Cópias repetidas ou fora de ordem não estragam nada, e todas as réplicas acabam com o mesmo estado.
3. **Sincronização no arranque.** Uma réplica que arranca ou reinicia vai buscar os voos às outras. Uma réplica que
   falhou recupera os dados sem intervenção manual.
4. **Identificador UUID gerado no domínio (`FlightId`).** É único entre réplicas sem coordenação. O `pk` numérico é
   apenas técnico e local a cada base de dados.
5. **API interna separada que nunca reencaminha.** Os peers chamam sempre `/internal/**`, que só devolve dados locais,
   e a cópia recebida em `/replica` nunca é propagada outra vez. Isto elimina, por construção, o forwarding circular
   (A → B → A) e os ciclos de replicação.
6. **Forwarding peer-to-peer como complemento da replicação.** Se um voo ainda não chegou a uma réplica, ela pergunta
   às outras **em paralelo** (o tempo de resposta é o do peer mais lento, não a soma). O mesmo para mudanças de estado.
7. **Resiliência nas chamadas remotas:** timeouts (1 s / 2 s), retry com backoff exponencial, circuit breaker por
   destino e failover entre réplicas dos outros serviços.
8. **CAP: estratégia AP, como pede o enunciado.** As **leituras** respondem sempre com os dados obtidos e assinalam
   respostas parciais. Os **agendamentos** são aceites mesmo com uma réplica em baixo, com aviso ao cliente, porque os
   voos são planeados com antecedência e um conflito raro pode ser resolvido antes da partida. Esse conflito é
   **detetado** quando as réplicas convergem (log `CONFLITO detetado apos replicacao`). Com
   `REQUIRE_COMPLETE_CONFLICT_CHECK=true`, o agendamento passa a CP (recusa com 503 durante a partição).
9. **Segurança em camadas** (ver secção 8): TLS em todas as ligações, JWT + RBAC na API pública, API key entre réplicas
   e auditoria.
10. **DDD.** Referências a rotas e aviões de outros serviços apenas por identidade (sem chaves estrangeiras nem cópias de
    dados). Regras de negócio no domínio (`FlightSchedulingPolicy`, máquina de estados no agregado). Tradução do modelo
    externo numa *Anti-Corruption Layer*.

---

## 7. Tolerância a falhas

| Cenário                                                                    | Comportamento                                                                               |
|----------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| Voo ainda não replicado para esta réplica                                  | Forwarding em paralelo para os peers; 404 só se **todos** responderem que não existe        |
| Voo não encontrado e algum peer sem resposta                               | 503: o voo pode existir nessa réplica (nunca um 404 falso)                                  |
| Réplica onde o voo foi criado em baixo                                     | As outras réplicas têm a cópia: o voo continua disponível                                   |
| Uma réplica em baixo (leitura agregada)                                    | Resposta com os restantes dados + header de resposta parcial                                |
| Uma réplica em baixo (agendamento)                                         | AP: 201 com header de resposta parcial; a cópia fica pendente e é entregue quando ela volta |
| Réplica reinicia (BD em memória vazia)                                     | Sincronização no arranque com as outras réplicas                                            |
| Réplica falha repetidamente                                                | Circuit breaker abre: deixa de ser contactada durante 10 s (*fail fast*)                    |
| Uma réplica de outro serviço em baixo                                      | Failover para a réplica seguinte                                                            |
| Todas as réplicas de outro serviço em baixo                                | Agendamento → 503; consulta de disponibilidade → 200 com `available: false` e o motivo      |
| Peer com API key errada (401)                                              | Tratado como indisponível; não interrompe o pedido                                          |
| Dois pedidos simultâneos para o mesmo avião (mesma réplica)                | Lock por avião: só um é aceite, o outro recebe 409                                          |
| Dois agendamentos em conflito em réplicas diferentes, durante uma partição | Ambos aceites (AP); conflito detetado e registado quando as réplicas convergem              |
| Alterações concorrentes ao mesmo voo em réplicas diferentes                | Convergem para a mais recente (`revision`, `lastModifiedAt`)                                |

---

## 8. Segurança

Perfis do AISafe (enunciado de PSOFT): **Admin, Backoffice Operator, ATCC, Maintenance Technician, Maintenance
Supervisor**.

- **Proteção do canal (TLS):** todas as réplicas servem apenas HTTPS. As chamadas a outras réplicas e ao Airports &
  Routes também são HTTPS, validadas com a truststore do grupo.
- **Autenticação de utilizadores (JWT):** o token emitido pela API Gateway é validado em cada réplica (assinatura
  HS256 com o segredo partilhado, expiração, utilizador e perfis no claim `roles`). Sem sessão no servidor.
- **Autorização (RBAC):** regras por endpoint e método (tabela da secção 5.1). Tudo o que não está previsto é negado.
- **Propagação da identidade:** o mesmo JWT é reencaminhado nas chamadas aos outros serviços, que aplicam as suas
  próprias regras. As chamadas em segundo plano (replicação) usam só a API key.
- **Autenticação entre réplicas:** header `X-API-Key` em `/internal/**`, comparado em tempo constante e enviado
  automaticamente pelo `RestTemplate`.
- **Auditoria (logger `AUDIT`):**
    - operações: `User: atcc.user - Action: CREATE - Target ID: <flightId>` (também `UPDATE_STATUS ...`);
    - acessos negados: `DENIED 401 ... reason=...` e `DENIED 403 ... user=... roles=[...]`;
    - API interna: `ALLOWED` / `DENIED` com a réplica de origem.
- **Erros sem detalhes internos:** respostas `ProblemDetail` normalizadas.

---

## 9. Testes

### 9.1 Testes unitários (JUnit)

`src/test/java` – **67 testes**:
- **domínio:** value objects, máquina de estados, agregado, regras de agendamento e convergência das cópias
  (`ReplicaMergeTest`);
- **infraestrutura:** circuit breaker, `PeerClient` (paralelismo, peers em falha, resposta parcial, 503 em vez de 404)
  e `FlightReplicator` (envio a todos os peers, cópia pendente até o peer voltar, replicação desligada);
- **segurança:** validação do JWT (`JwtAuthenticationFilterTest`), regras RBAC por perfil e endpoint com o Spring
  Security real (`SecurityRulesTest`) e propagação do JWT e da API key (`HttpClientConfigTest`).

Executar: no IntelliJ, botão direito em `src/test/java` → **Run 'All Tests'**, ou `mvn test`.

### 9.2 Testes distribuídos (Postman)

Pasta `tests/`:
- `flight-operations.postman_collection.json` – testes automáticos: health, criação e leitura, replicação,
  forwarding, agregação, disponibilidade, regras de negócio, ciclo de vida, concorrência e segurança;
- `falhas-manuais.postman_collection.json` – testes de falha, executados um a um depois de parar uma réplica ou o stub;
- `aisafe-local.postman_environment.json` – ambiente com `fo1`, `fo2`, `fo3`, `apiKey` e tokens por perfil.

> As coleções serão atualizadas para HTTPS e JWT quando a API Gateway e os restantes serviços estiverem integrados.

Executar: importar os três ficheiros, escolher o ambiente **AISafe local** e correr a coleção principal com o
**Collection Runner**.

---

## 10. Limitações conhecidas e trabalho futuro

| Limitação                                                                 | Impacto                                                                                       | Trabalho futuro                                                            |
|---------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| Conflitos entre réplicas durante uma partição (estratégia AP)             | Dois voos do mesmo avião podem ser aceites; são detetados, mas não resolvidos automaticamente | Particionar agendamentos por matrícula (uma réplica responsável por avião) |
| Resolução *last writer wins*                                              | Em alterações concorrentes ao mesmo voo, uma delas perde-se                                   | Relógios vetoriais ou resolução no domínio                                 |
| Bases de dados H2 em memória                                              | Mitigado pela sincronização no arranque; se as 3 réplicas reiniciarem, os dados perdem-se     | Base de dados persistente                                                  |
| Cópias pendentes guardadas em memória                                     | Se a réplica de origem reiniciar antes de entregar, a cópia só chega por sincronização        | Fila persistente ou broker de mensagens                                    |
| Certificado de desenvolvimento partilhado por todos os serviços; sem mTLS | Adequado a um ambiente de desenvolvimento                                                     | Um certificado por serviço emitido por uma CA; mTLS entre serviços         |
| Sem encriptação dos dados guardados                                       | BD em memória, sem ficheiros em disco                                                         | BD persistente com encriptação (ex.: H2 `CIPHER=AES`)                      |
| Segredo JWT e API key partilhados (por variável de ambiente)              | Quem tiver o segredo pode emitir tokens                                                       | Gestor de segredos; tokens assinados com chave assimétrica (RS256)         |
| Aircraft Service ainda em stub                                            | Integração com aviões testada só com dados simulados                                          | Integrar com o serviço real do colega                                      |
| `minimumRange` ainda não exposto pelo Airports & Routes                   | Regra do alcance não é verificada com o serviço real                                          | Acrescentar o campo ao `RouteViewDTO` do serviço de rotas                  |
| Datas sem fuso horário (`LocalDateTime`)                                  | Ambiguidade entre aeroportos em fusos diferentes                                              | `OffsetDateTime` / UTC                                                     |
| Lista de peers estática                                                   | Adicionar réplicas exige reconfigurar                                                         | *Service discovery*                                                        |

---

## 11. Estrutura do projeto

```
src/main/java/pt/isep/psoft/aisafe/flight_operations/
├── api/              controllers (público e interno) e tratamento de erros
├── application/      ScheduledFlightService, FlightAssignmentValidator, DTOs
├── domain/           agregado, value objects, FlightStatus, FlightSchedulingPolicy
├── infrastructure/   PeerClient, FlightReplicator, RemoteCaller, CircuitBreaker, ReplicatedServiceClient
├── repositories/     ScheduledFlightRepository
├── security/         JwtAuthenticationFilter, SecurityProblemHandler, AuditLog, Roles
└── config/           SecurityConfig, HttpClientConfig (RestTemplate + JWT), ApiKeyFilter, beans de domínio
src/main/resources/   application.properties, keystore.p12
src/test/java/...     testes JUnit, StubExternalServices e DevTokenGenerator
certs/                truststore.p12 (local, não submetido)
```