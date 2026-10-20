# AISafe – Flight Operations Service

Serviço do sistema distribuído **AISafe Flight Management System** ( Assignment 1) responsável pelo
agendamento de voos, pela atribuição de aviões a rotas (com restrições de alcance e capacidade), pela consulta de
disponibilidade e pelo relatório de utilização de rotas.

O serviço corre em **várias réplicas** (3 por omissão). Cada réplica tem a sua própria base de dados e, quando não tem
os dados pedidos, consulta as outras réplicas (*‘forwarding’ peer-to-peer*) e agrega as respostas.

---

## 1. Arquitetura

| Componente                          | Responsabilidade                                                                                                                   |
|-------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| `ScheduledFlightController`         | API pública (`/api/scheduled-flights`). Leituras distribuídas, respostas HATEOAS                                                   |
| `InternalScheduledFlightController` | API interna (`/internal/scheduled-flights`), usada **apenas** entre réplicas. Responde só com dados locais e **nunca reencaminha** |
| `ScheduledFlightService`            | Orquestração: consultas locais vs distribuídas, agregação, forwarding de escrita, lock por avião                                   |
| `ScheduledFlight` + value objects   | Agregado: `FlightId` (UUID), `RouteId`, `RegistrationNumber`, máquina de estados `FlightStatus`                                    |
| `FlightSchedulingPolicy`            | Domain service (Java puro): rota ativa, avião `ACTIVE`, alcance, capacidade, conflitos de horário                                  |
| `FlightAssignmentValidator`         | Obtém a rota e o avião dos outros serviços e traduz o JSON recebido (`RouteInfo` / `AircraftInfo`, *Anti-Corruption Layer*)        |
| `PeerClient`                        | Comunicação com as outras réplicas **em paralelo** (`findFirst`, `collectAll`, `forwardPut`)                                       |
| `ReplicatedServiceClient`           | Cliente para os outros serviços: round-robin + failover entre réplicas                                                             |
| `RemoteCaller` + `CircuitBreaker`   | Chamadas HTTP com timeouts, retry com backoff expo~~~~nencial e circuit breaker por destino                                        |
| `ApiKeyFilter`                      | Exige `X-API-Key` em `/internal/**` e regista cada acesso (logger `AUDIT`)                                                         |
| `GlobalExceptionHandler`            | Traduz exceções para códigos HTTP (`ProblemDetail`)                                                                                |

Diagramas (pasta `Documentos/`):
- `c4-component-flight-operations` – C4 nível 3 (componentes)
- `class-diagram-flight-operations` – modelo de domínio do serviço
- `sd-schedule-flight` – agendamento com validação nos outros serviços
- `sd-get-flight-forwarding` – leitura com forwarding peer-to-peer
- `sd-route-utilization` – US229, agregação com resposta parcial
- `sd-change-flight-status` – forwarding de escrita para a réplica dona
- `sd-check-availability` – consulta de disponibilidade

---

## 2. Pré-requisitos

- Java 17 ou superior
- Maven (ou IntelliJ IDEA)
- Postman (para os testes distribuídos)
- Serviços **Airports & Routes** e **Aircraft** a correr. Para testar o serviço isoladamente, usar o stub
  `src/test/java/.../StubExternalServices.java` (ver secção 4)

---

## 3. Configuração

Toda a configuração está em `src/main/resources/application.properties` e pode ser alterada por **variáveis de ambiente**,
sem valores fixos no código.

| Variável | Omissão | Descrição |
|---|---|---|
| `PORT` | `8081` | Porta da réplica |
| `INSTANCE_ID` | `fo-1` | Identificador da réplica (aparece nos logs e no nome da BD) |
| `PEERS` | *(vazio)* | URLs das **outras** réplicas, separadas por vírgulas |
| `ROUTES_URLS` | `http://localhost:8091,http://localhost:8092` | Réplicas do Airports & Routes Service |
| `AIRCRAFT_URLS` | `http://localhost:8071,http://localhost:8072` | Réplicas do Aircraft Service |
| `API_KEY` | `sidis-dev-key` | Chave partilhada para chamadas entre serviços |

Outras propriedades relevantes:

| Propriedade | Valor | Descrição |
|---|---|---|
| `app.http.connect-timeout` / `read-timeout` | `1s` / `2s` | Timeouts das chamadas remotas |
| `app.http.max-attempts` / `initial-backoff` | `2` / `200ms` | Retry com backoff exponencial |
| `app.http.circuit-breaker.failure-threshold` | `3` | Falhas seguidas até o circuito abrir |
| `app.http.circuit-breaker.open-duration` | `10s` | Tempo com o circuito aberto antes de testar de novo |
| `app.scheduling.min-gap-hours` | `2` | Intervalo mínimo entre voos do mesmo avião |
| `app.scheduling.require-complete-conflict-check` | `true` | Recusa agendamentos (503) se alguma réplica não responder |

---

## 4. Como executar

### 4.1 Stub dos outros serviços (só para testes isolados)

No IntelliJ: abrir `src/test/java/.../StubExternalServices.java` e clicar ▶ em `main`.
Fica a responder em `:8091` (rotas) e `:8071` (aviões) com dados de teste
(`OPO-LIS`, `OPO-JFK`, `OPO-FNC` / `CS-TTA`, `CS-TTB`, `CS-TTM`).

### 4.2 Três réplicas no IntelliJ

Criar três *Run Configurations* da classe `FlightOperationsApplication` com estas variáveis de ambiente:

| Réplica | Environment variables |
|---|---|
| FO-1 | `PORT=8081;INSTANCE_ID=fo-1;PEERS=http://localhost:8082,http://localhost:8083` |
| FO-2 | `PORT=8082;INSTANCE_ID=fo-2;PEERS=http://localhost:8081,http://localhost:8083` |
| FO-3 | `PORT=8083;INSTANCE_ID=fo-3;PEERS=http://localhost:8081,http://localhost:8082` |

### 4.3 Três réplicas na linha de comandos (PowerShell)

```powershell
# terminal 1
$env:PORT="8081"; $env:INSTANCE_ID="fo-1"; $env:PEERS="http://localhost:8082,http://localhost:8083"; mvn spring-boot:run
# terminal 2
$env:PORT="8082"; $env:INSTANCE_ID="fo-2"; $env:PEERS="http://localhost:8081,http://localhost:8083"; mvn spring-boot:run
# terminal 3
$env:PORT="8083"; $env:INSTANCE_ID="fo-3"; $env:PEERS="http://localhost:8081,http://localhost:8082"; mvn spring-boot:run
```

### 4.4 Verificar

- `GET http://localhost:808x/actuator/health` → `"status": "UP"` em cada réplica
- No arranque, cada réplica regista `Peers configurados: [...]` com as outras duas

---

## 5. API

### 5.1 Pública – `/api/scheduled-flights`

| Método | Endpoint | Descrição | Respostas |
|---|---|---|---|
| `POST` | `/` | Agenda um voo (`routeId`, `registrationNumber`, `scheduledDateTime`) | 201, 400, 409, 503 |
| `GET` | `/{id}` | Voo por id (local ou via peers) | 200, 400, 404 |
| `PATCH` | `/{id}/status` | Muda o estado (`{"status": "DELAYED"}`), em qualquer réplica | 200, 400, 404, 409 |
| `GET` | `/aircraft/{registrationNumber}` | Voos de um avião, agregados de todas as réplicas | 200, 204 |
| `GET` | `/availability?registrationNumber=&dateTime=` | Disponibilidade de um avião para uma data/hora | 200 |
| `GET` | `/route-utilization` | US229 – rotas mais voadas, somadas em todas as réplicas | 200, 204 |

As leituras agregadas incluem o header `X-Partial-Response-Unreachable-Peers` quando alguma réplica não respondeu.

Exemplo:
```http
POST /api/scheduled-flights
Content-Type: application/json

{ "routeId": "OPO-LIS", "registrationNumber": "CS-TTA", "scheduledDateTime": "2027-03-15T10:00:00" }
```

### 5.2 Interna – `/internal/scheduled-flights` (exige `X-API-Key`)

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/{id}` | Voo local |
| `PUT` | `/{id}/status` | Mudança de estado local (forwarding de escrita) |
| `GET` | `/aircraft/{registrationNumber}` | Voos locais de um avião |
| `GET` | `/route-utilization` | Contagens locais por rota |

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

1. **Base de dados por réplica (sharding pela réplica que recebe o `POST`).** O voo fica guardado apenas na réplica que
   o criou, que passa a ser a sua "dona". As outras réplicas encontram-no por forwarding.
2. **Identificador UUID gerado no domínio (`FlightId`).** É único entre réplicas sem coordenação. O `pk` numérico é
   apenas técnico e local a cada base de dados.
3. **API interna separada que nunca reencaminha.** Os peers chamam sempre `/internal/**`, que só devolve dados locais.
   Isto elimina o forwarding circular (A → B → A) por construção.
4. **Consultas aos peers em paralelo.** O tempo de resposta é o do peer mais lento, não a soma de todos.
5. **Forwarding de escrita.** Um `PATCH` recebido numa réplica que não é dona do voo é reencaminhado para a réplica dona.
6. **Resiliência nas chamadas remotas:** timeouts (1 s / 2 s), retry com backoff exponencial, circuit breaker por
   destino e failover entre réplicas dos outros serviços.
7. **Modelo de consistência (CAP).** As **leituras** privilegiam disponibilidade (AP): respondem com os dados obtidos e
   assinalam respostas parciais. Os **agendamentos** são recusados (503) se não for possível consultar todas as réplicas,
   para nunca haver duplo agendamento do mesmo avião.
8. **DDD.** Referências a rotas e aviões de outros serviços apenas por identidade (sem chaves estrangeiras nem cópias de
   dados). Regras de negócio no domínio (`FlightSchedulingPolicy`, máquina de estados no agregado). Tradução do modelo
   externo numa *Anti-Corruption Layer*.

---

## 7. Tolerância a falhas

| Cenário | Comportamento |
|---|---|
| Voo não existe localmente | Forwarding em paralelo para os peers; 404 se nenhum o tiver |
| Uma réplica em baixo (leitura) | Ignorada; resposta com os restantes dados + header de resposta parcial |
| Uma réplica em baixo (agendamento) | 503: não é possível garantir que o avião está livre |
| Réplica falha repetidamente | Circuit breaker abre: deixa de ser contactada durante 10 s (*fail fast*) |
| Uma réplica de outro serviço em baixo | Failover para a réplica seguinte |
| Todas as réplicas de outro serviço em baixo | Agendamento → 503; consulta de disponibilidade → 200 com `available: false` e o motivo |
| Peer com API key errada (401) | Tratado como indisponível; não interrompe o pedido |
| Dois pedidos simultâneos para o mesmo avião (mesma réplica) | Lock por avião: só um é aceite, o outro recebe 409 |

---

## 8. Segurança

- **Autenticação entre serviços:** header `X-API-Key` em todas as chamadas a `/internal/**`, enviado automaticamente
  pelo `RestTemplate`.
- **Auditoria:** cada acesso à API interna é registado no logger `AUDIT` (`ALLOWED` / `DENIED`, origem e endereço).
- **HTTPS e controlo de acesso por papéis (RBAC):** asseguradas pela API Gateway do sistema. *(Completar quando a gateway
  estiver definida.)*

---

## 9. Testes

### 9.1 Testes unitários (JUnit)

`src/test/java` – 36 testes:
- domínio: value objects, máquina de estados, agregado e regras de agendamento;
- infraestrutura: circuit breaker e `PeerClient` (paralelismo, peers em falha, resposta parcial).

Executar: no IntelliJ, botão direito em `src/test/java` → **Run 'All Tests'**, ou `mvn test`.

### 9.2 Testes distribuídos (Postman)

Pasta `tests/`:
- `flight-operations.postman_collection.json` – testes automáticos: health, criação e leitura local, forwarding,
  agregação, disponibilidade, regras de negócio, ciclo de vida, concorrência e segurança da API interna;
- `falhas-manuais.postman_collection.json` – testes de falha, executados um a um depois de parar uma réplica ou o stub;
- `aisafe-local.postman_environment.json` – ambiente com `fo1`, `fo2`, `fo3` e `apiKey`.

Executar: importar os três ficheiros, escolher o ambiente **AISafe local** e correr a coleção principal com o
**Collection Runner**.

---

## 10. Limitações conhecidas

- **Bases de dados H2 em memória:** os dados de uma réplica perdem-se quando esta reinicia.
- **Consistência eventual entre réplicas:** dois agendamentos simultâneos do mesmo avião em **réplicas diferentes**
  podem ambos passar a verificação de conflitos. O lock por avião só protege dentro de cada réplica.
- **Sem replicação de dados:** se a réplica dona de um voo estiver em baixo, esse voo fica indisponível até ela voltar.
- **Datas sem fuso horário** (`LocalDateTime`).
- **API key partilhada** e transmitida em claro enquanto as ligações entre serviços não usarem HTTPS.
- **Lista de peers estática** (configurada por variável de ambiente; sem *service discovery*).

---

## 11. Estrutura do projeto

```
src/main/java/pt/isep/psoft/aisafe/flight_operations/
├── api/              controllers (público e interno) e tratamento de erros
├── application/      ScheduledFlightService, FlightAssignmentValidator, DTOs
├── domain/           agregado, value objects, FlightStatus, FlightSchedulingPolicy
├── infrastructure/   PeerClient, RemoteCaller, CircuitBreaker, ReplicatedServiceClient
├── repositories/     ScheduledFlightRepository
└── config/           RestTemplate, thread pool, ApiKeyFilter, beans de domínio
src/test/java/...     testes JUnit e StubExternalServices
```