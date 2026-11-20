# AISafe – Airports & Routes Service (Entrega 2)

Serviço responsável pelos **aeroportos** e pelas **rotas** da rede. Nesta entrega passou a usar **CQRS**,
a replicar entre instâncias por **mensagens (RabbitMQ)** em vez de HTTP direto, e cada instância tem a
**sua própria base de dados PostgreSQL** (Database-per-Service). Mantém a API REST da Entrega 1.

> A documentação da Entrega 1 está em [`README.md`](README.md).

## Início rápido (Modo B – Docker seguro)

Pré-requisito: Docker Desktop. A partir da pasta `infrastructure` do repositório:

```powershell
copy .env.example .env             # só na primeira vez; depois ver a nota abaixo
.\docker\gen-certs.ps1             # gera os certificados e preenche os segredos CHANGE_ME do .env
docker compose -f docker-compose.secure.yml up --build -d
docker compose -f docker-compose.secure.yml ps
```

Quando tudo estiver `healthy` (o `rabbitmq-init` termina com `Exited (0)`, e isso é o esperado):

| O quê | Onde |
|---|---|
| Gateway 1 / Gateway 2 | `https://localhost:8443` / `https://localhost:8444` |
| Consola RabbitMQ (nós 1, 2, 3) | `http://localhost:15672` / `15673` / `15674` (utilizador e password no `.env`) |

O `.env` **não vai para o Git**. Mais detalhes (Keycloak, utilizadores, portas) em `infrastructure/README.md`.

## E2.1 O que mudou face à Entrega 1

| Entrega 1 | Entrega 2 |
|---|---|
| Replicação por HTTP direto entre instâncias (uma tarefa de outbox **por par**) | Replicação por **eventos no RabbitMQ** (exchange fanout); **uma só tarefa de outbox por evento** |
| Um só modelo de dados (leituras e escritas na mesma tabela) | **CQRS**: modelo de escrita (agregados JPA) e **modelo de leitura** separado (`airport_read_model`, `route_read_model`) |
| H2 em memória/ficheiro | **PostgreSQL 16 por instância** no Modo B (`airports-db-1`, `airports-db-2`); H2 mantém-se no Modo A e nos testes |
| Sem broker | **Cluster RabbitMQ de 3 nós**, filas *quorum*, AMQP só sobre TLS (infraestrutura comum ao grupo) |
| Mensagens/tarefas falhadas só no outbox | Fila de **dead-letter** por instância, TTL e limite de tamanho das filas |

A replicação por HTTP continua disponível como alternativa (`app.replication.transport=http`), mas o valor
por omissão da aplicação é `amqp`. O contrato REST externo **não mudou**.

## E2.2 Arquitetura (CQRS)

**Lado dos comandos (escrita).** `AirportService` e `RouteService` validam o pedido, gravam o agregado
(`Airport`, `Route`), atualizam o modelo de leitura e gravam uma tarefa na tabela de outbox, tudo
**na mesma transação**. Se algo falhar (por exemplo uma linha inválida numa importação CSV), nada fica gravado
e nenhum evento é publicado.

**Lado das queries (leitura).** `AirportQueryService` e `RouteQueryService` leem **apenas** o modelo de leitura:
pesquisa, listagens, estatísticas, exportação GeoJSON e o cálculo de rotas alternativas
(`FastestRouteStrategy`, pesquisa em largura sobre `route_read_model`).

**Projetores.** `AirportProjector` e `RouteProjector` transformam o resultado de um comando (ou de um evento
recebido do broker) numa linha do modelo de leitura. São chamados na mesma transação do comando, por isso o modelo
de leitura **da própria instância** nunca fica atrás do de escrita. As outras instâncias atualizam-se ao aplicar o evento.

**Reconstrução.** Ao arrancar, `ReadModelRebuilder` reconstrói o modelo de leitura a partir do de escrita
(log: `Modelo de leitura reconstruido: N aeroportos, M rotas`). O modelo de leitura é, por isso, sempre descartável e reconstruível.

| Comandos (escrita) | Queries (leitura) |
|---|---|
| `POST /api/airports`, `POST /api/airports/import` (CSV) | `GET /api/airports/{iata}`, `/search`, `/grouped` |
| `PATCH /api/airports/{iata}/details`, `/status`, `/image` | `GET /api/routes`, `/{id}`, `/{id}/history`, `/airport/{iata}`, `/involving/{iata}` |
| `POST /api/routes`, `PATCH /api/routes/{id}`, `/{id}/deactivate` | `GET /api/routes/alternatives`, `/statistics/*`, `/active/sorted`, `/export/geojson` |

## E2.3 Comunicação entre serviços

| Padrão | Onde | Detalhe |
|---|---|---|
| **REST** (externo) | Cliente → gateways → serviço | HTTPS, JWT do Keycloak (RS256) validado por cada serviço |
| **Publish/subscribe (AMQP)** | Entre instâncias e para outros serviços | Exchange `airports.events` (fanout, durável) |
| **Transactional Outbox** | Dentro de cada instância | `AmqpReplicationPublisher` lê a tabela de outbox e publica |
| **HTTP par-a-par** (só leituras) | Entre instâncias | Se uma instância não tem o dado, pergunta à outra com `X-P2P-Hops` (máximo 1 salto, sem ciclos); usa *circuit breaker* e um JWT interno `ROLE_PEER` |

**Eventos publicados:** `AIRPORT_CREATED`, `AIRPORT_DETAILS_UPDATED`, `AIRPORT_STATUS_CHANGED`, `AIRPORT_IMAGE_UPDATED`,
`ROUTE_CREATED`, `ROUTE_UPDATED`, `ROUTE_DEACTIVATED`.
O evento da imagem leva a imagem em *data URL* (base64) e só existe no modo AMQP; o tamanho fica limitado pelo limite de upload do Spring (por omissão 1 MB).

**Envelope (JSON, mensagem persistente):** `taskId`, `eventType`, `method`, `path`, `payload`, `originalUser`,
`lastModified`, `originNodeId`. O `messageId` é `<originNodeId>-<taskId>`.

**Topologia.** Cada instância tem a **sua fila** `airports.events.<instância>` ligada ao exchange fanout; assim todas as
instâncias recebem todos os eventos, e se uma estiver parada a sua fila guarda-os. O Flight Operations consome os eventos de
rotas nas suas próprias filas (`fo.routes.flight-operations-N`).

## E2.4 Modelo de consistência

- **Consistência eventual entre instâncias.** Uma escrita é aceite e gravada na instância que a recebe; as outras instâncias
  convergem quando aplicam o evento (o publicador corre de 2 em 2 segundos, por isso a diferença é normalmente de segundos).
- **Na própria instância**, uma leitura logo após a escrita já vê o dado (o projetor corre na mesma transação).
- **Entrega "pelo menos uma vez".** O publicador só marca a tarefa como `DONE` depois da confirmação do broker (*publisher confirm*);
  pode, por isso, publicar a mesma mensagem mais do que uma vez.
- **Consumidores idempotentes.** Aplicar o mesmo evento duas vezes não altera o resultado.
- **Conflitos: *last-writer-wins*.** Ganha a alteração com `lastModified` mais recente; em caso de empate ganha o `originNodeId` maior.
  Um evento mais antigo do que o estado atual é ignorado. Os eventos da própria instância (devolvidos pelo fanout) também são ignorados.

Ver `docs/sd-conflict-resolution.puml`.

## E2.5 Infraestrutura sem pontos únicos de falha (Modo B)

| Componente | Redundância |
|---|---|
| Gateways | 2 (NGINX, portas 8443 e 8444); o cliente tem de conhecer as duas |
| Airports & Routes | 2 instâncias, cada uma com a **sua** base PostgreSQL |
| RabbitMQ | Cluster de 3 nós; filas *quorum* (Raft) continuam a funcionar com 1 nó em baixo; `pause_minority` em partições de rede; AMQP só em TLS (5671) |
| Keycloak | 2 nós, base PostgreSQL com 1 primário e 2 standby (repmgr) |

Uma instância e a sua base de dados falham em conjunto; o outro par (instância + base) tem uma cópia completa.

## E2.6 Tolerância a falhas

| Falha | Comportamento |
|---|---|
| **Broker indisponível** | As escritas continuam a funcionar. A tarefa fica `PENDING` na outbox e o publicador repete com atraso crescente: `min(60 s, 5 s << tentativas)`, até 10 tentativas; depois fica `FAILED` (visível em `GET /api/admin/replication-tasks`). |
| **Uma instância parada** | Os eventos esperam na fila dela (durável, *quorum*) e são aplicados quando voltar, desde que não passe o TTL de 1 hora. |
| **Mensagem que não se consegue processar** | O consumidor tenta 3 vezes (1 s, depois ×2) e rejeita sem reenfileirar; a mensagem vai para `airports.events.<instância>.dlq`. |
| **Mensagem expirada ou fila cheia** | TTL de 1 hora e limite de 10000 mensagens; o excedente também segue para a *dead-letter queue*, por isso nada se perde sem deixar rasto. |
| **Instância par inacessível numa leitura** | *Circuit breaker* por par: 3 falhas seguidas abrem o circuito durante 10 s; leituras agregadas devolvem `X-Partial-Result: true`. |
| **Nó do RabbitMQ em baixo** | O cliente usa a lista de 3 endereços (`SPRING_RABBITMQ_ADDRESSES`) e as filas *quorum* mantêm-se. |

Ver `docs/sd-failure-recovery.puml` e `docs/sd-amqp-event-propagation.puml`.

## E2.7 `GET /api/v2/unicorns` (humor dos aviões)

Este endpoint obrigatório pertence ao domínio das aeronaves e está implementado como *read model* no serviço
**Flight Operations** (ver o `README_E2.md` desse serviço, secção E2.7). Para os testes de integração em Postman existe também
um **mock** (`SISDIS_P2_unicorns_mock.postman_collection.json`) que devolve o estado de "humor" em JSON.
A ligação ao CQRS é a mesma: o humor é uma vista de leitura derivada de eventos, e não uma consulta ao serviço dono dos dados.

## E2.8 API – alterações face à Entrega 1

Nenhuma alteração aos endpoints REST. Mudou apenas **como** a replicação entre instâncias é feita (eventos em vez de HTTP direto).

## E2.9 Configuração (novas variáveis)

| Propriedade / variável | Valor por omissão | Função |
|---|---|---|
| `app.replication.transport` | `amqp` | `amqp` (RabbitMQ) ou `http` (entrega direta aos pares, como na Entrega 1) |
| `app.messaging.exchange` | `airports.events` | Nome do exchange fanout |
| `app.messaging.queue.ttl-ms` | `3600000` (1 h) | TTL das mensagens nas filas |
| `app.messaging.queue.max-length` | `10000` | Limite de mensagens por fila |
| `PEER_NODE_ID` | `airports-routes-N` | Identificador da instância (usado em `originNodeId` e no nome da fila) |
| `SPRING_RABBITMQ_ADDRESSES` | `rabbitmq-1:5671,rabbitmq-2:5671,rabbitmq-3:5671` | Nós do cluster |
| `SPRING_RABBITMQ_SSL_ENABLED` | `true` | AMQP sobre TLS |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://airports-db-N:5432/airports` | Base de dados da instância |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `AIRPORTS_DB_PASSWORD` | no `.env` | Credenciais (não vão para o Git) |

## E2.10 Testes

- **Unitários e de integração:** `./mvnw test` na pasta do serviço — **52 testes a passar** (4 ignorados), captura
  `docs/screenshots/ar-02-testes-intellij.png`. Cobrem, entre outros, o consumidor AMQP (`ReplicationEventConsumerTest`:
  eventos próprios ignorados, rejeição sem reenfileirar), o publicador do outbox (`AmqpReplicationPublisherTest`: DONE só
  com confirmação do broker, backoff, FAILED às 10 tentativas), o projetor CQRS (`AirportProjectorTest`) e a replicação
  da imagem (`AirportImageReplicationTest`).
- **Postman (Entrega 2)**, pasta `tests(postman)/` na raiz do repositório:
  - `SIDIS – Entrega 2 (Modo B)` — **80 testes a passar** (`ar-07-postman-e2-modo-b.png`): escreve por uma gateway e lê
    pela outra até convergir; as leituras do Airports levam `X-P2P-Hops: 1` (sem reencaminhamento), por isso um 200 prova
    que o evento chegou pelo RabbitMQ. Inclui Flight Operations, eventos entre serviços, `/api/v2/unicorns` e o mock.
  - `SIDIS – Entrega 2 (Modo A)` — **119 testes a passar** (`ar-08-postman-e2-modo-a.png`): o mesmo sobre 3 instâncias
    de cada serviço com acesso direto, mais *last-writer-wins* em escritas concorrentes.
  - Pastas MANUAL: parar uma instância, escrever noutra e ver a fila dela guardar o evento até voltar.
- **Postman (Entrega 1, Modo B):** 348 testes (`ar-01-postman-348-modo-b.png`).
- **Evidências:** `docs/screenshots/` — filas *quorum* com TTL, limite, DLX e DLQ no RabbitMQ (`ar-04-rabbitmq-filas.png`),
  modelo de leitura reconstruído (`ar-06-modelo-leitura.png`), mock dos unicorns (`ar-03*`).

## E2.11 Limitações conhecidas

- O esquema da base de dados é gerido por `spring.jpa.hibernate.ddl-auto=update` (sem migrações versionadas, como Flyway).
- Tarefas de outbox em estado `FAILED` não são reprocessadas automaticamente.
- O *circuit breaker* não tem estado *half-open* completo: passados os 10 s o contador de falhas recomeça a zero.
- O token interno entre instâncias (`ROLE_PEER`, HS256) usa um segredo partilhado, e o segredo por omissão de desenvolvimento deve ser substituído.
- A importação CSV corre numa só transação, o que é simples e atómico mas não é adequado a ficheiros muito grandes.
- O publicador do outbox faz *polling* de 2 em 2 segundos, e não *change data capture*.

## E2.12 Estrutura do projeto

```
src/main/java/.../airports_routes_service/
  api/            controladores REST (Airport, Route, Admin) e tratamento de erros
  application/    serviços de comando e de query, outbox, estratégia de rotas
  domain/         agregados (Airport, Route, ReplicationTask, ...)
  readmodel/      modelo de leitura, repositórios de leitura e projetores
  messaging/      exchange, filas, publicador e consumidor AMQP
  repositories/   repositórios JPA do modelo de escrita
  bootstrapper/   dados iniciais e reconstrução do modelo de leitura
  security/       validação de JWT (Keycloak RS256) e token interno entre instâncias
```

## E2.13 Diagramas

Pasta [`docs/`](docs/) (PlantUML):

| Ficheiro | Mostra |
|---|---|
| `c4-component-airports-routes-p2.puml` | Componentes (CQRS, outbox, mensageria) |
| `physical-view-airports-routes.puml` | Vista física (Modo B: gateways, cluster RabbitMQ, bases por instância) |
| `physical-view-airports-routes-mode-a.puml` | Vista física (Modo A: IntelliJ, H2 por instância, RabbitMQ e PostgreSQL do FO em Docker) |
| `class-diagram-airports-routes.puml` | Modelo de domínio (lado da escrita), modelo de leitura CQRS e outbox |
| `messaging-topology-airports-routes.puml` | Topologia AMQP (exchange, filas *quorum*, DLQ) |
| `sd-amqp-event-propagation.puml` | Propagação de um evento entre instâncias |
| `sd-cqrs-query-read-model.puml` | Uma query a ler só o modelo de leitura |
| `sd-failure-recovery.puml` | Broker em baixo, instância parada, mensagem inválida |
| `sd-conflict-resolution.puml` | Resolução de conflitos (*last-writer-wins*) |
| `sd-create-airport-outbox.puml`, `sd-create-route-outbox.puml`, `sd-csv-import-outbox.puml` | Comandos com outbox |
| `sd-circuit-breaker.puml`, `sd-p2p-forwarding-read.puml` | Leituras entre instâncias |
