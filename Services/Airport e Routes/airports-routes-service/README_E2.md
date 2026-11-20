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

> **Primeira vez com Flyway:** as bases criadas antes pelo Hibernate têm de ser apagadas, porque o Flyway só cria o
> esquema numa base vazia. Modo B: `docker compose -f docker-compose.secure.yml down` e
> `docker volume rm aisafe_airports-db-1-data aisafe_airports-db-2-data`. Modo A: apagar `data\airports_db_*.mv.db` na
> raiz do repositório. Os *bootstrappers* voltam a carregar os dados iniciais no arranque.

O `.env` **não vai para o Git**. Mais detalhes (Keycloak, utilizadores, portas) em `infrastructure/README.md`.

## E2.1 O que mudou face à Entrega 1

| Entrega 1 | Entrega 2 |
|---|---|
| Replicação por HTTP direto entre instâncias (uma tarefa de outbox **por par**) | Replicação por **eventos no RabbitMQ** (exchange fanout); **uma só tarefa de outbox por evento** |
| Um só modelo de dados (leituras e escritas na mesma tabela) | **CQRS**: modelo de escrita (agregados JPA) e **modelo de leitura** separado (`airport_read_model`, `route_read_model`) |
| H2 em memória/ficheiro | **PostgreSQL 16 por instância** no Modo B (`airports-db-1`, `airports-db-2`); H2 mantém-se no Modo A e nos testes |
| Sem broker | **Cluster RabbitMQ de 3 nós**, filas *quorum*, AMQP só sobre TLS (infraestrutura comum ao grupo) |
| Idempotência só pela regra *last-writer-wins* | **Inbox**: tabela `processed_event` (cada evento aplicado no máximo uma vez) + *last-writer-wins* |
| Esquema criado pelo Hibernate (`ddl-auto=update`) | **Migrações versionadas com Flyway** (`db/migration/V1__airports_routes_schema.sql`); o Hibernate não altera o esquema (`ddl-auto=none`) |
| Mensagens/tarefas falhadas só no outbox | Fila de **dead-letter** por instância, TTL e limite de tamanho das filas |
| Leituras com reencaminhamento HTTP para a outra instância (`X-P2P-Hops`, `X-Partial-Result`) | **Leituras só locais**: nenhuma comunicação síncrona entre instâncias (reencaminhamento desligado por omissão) |

A replicação por HTTP continua disponível como alternativa (`app.replication.transport=http`), mas o valor
por omissão da aplicação é `amqp`. O contrato REST externo **não mudou**.

## E2.2 Arquitetura (CQRS)

**Lado dos comandos (escrita).** `AirportService` e `RouteService` validam o pedido, gravam o agregado
(`Airport`, `Route`), atualizam o modelo de leitura e gravam uma tarefa na tabela de outbox, tudo
**na mesma transação**. Se algo falhar (por exemplo uma linha inválida numa importação CSV), nada fica gravado
e nenhum evento é publicado.

**Lado das queries (leitura).** `AirportQueryService` e `RouteQueryService` leem **apenas** o modelo de leitura
**da própria instância** e não fazem pedidos a outras instâncias (`peer.forwarding.max-hops=0`):
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
| ~~HTTP par-a-par~~ (leituras da Entrega 1) | — | **Desligado na Entrega 2** (`peer.forwarding.max-hops=0`): as instâncias já não comunicam de forma síncrona. O código fica disponível como opção (`PEER_FORWARDING_MAX_HOPS=1`). |

**Eventos publicados:** `AIRPORT_CREATED`, `AIRPORT_DETAILS_UPDATED`, `AIRPORT_STATUS_CHANGED`, `AIRPORT_IMAGE_UPDATED`,
`ROUTE_CREATED`, `ROUTE_UPDATED`, `ROUTE_DEACTIVATED`.
O evento da imagem leva a imagem em *data URL* (base64) e só existe no modo AMQP; o tamanho fica limitado pelo limite de upload do Spring (por omissão 1 MB).

**Envelope (JSON, mensagem persistente):** `eventId`, `taskId`, `eventType`, `method`, `path`, `payload`, `originalUser`,
`lastModified`, `originNodeId`. O `eventId` é um **UUID** gerado quando a tarefa entra no outbox e é também o `messageId`
AMQP. Ao contrário de `<originNodeId>-<taskId>` (usado antes), nunca se repete, mesmo que a base de dados seja recriada
— o que importa para os consumidores com inbox (este serviço e o Flight Operations).

**Topologia.** Cada instância tem a **sua fila** `airports.events.<instância>` ligada ao exchange fanout; assim todas as
instâncias recebem todos os eventos, e se uma estiver parada a sua fila guarda-os. O Flight Operations consome os eventos de
rotas nas suas próprias filas (`fo.routes.flight-operations-N`).

## E2.4 Modelo de consistência

- **Consistência eventual entre instâncias.** Uma escrita é aceite e gravada na instância que a recebe; as outras instâncias
  convergem quando aplicam o evento (o publicador corre de 2 em 2 segundos, por isso a diferença é normalmente de segundos).
- **Na própria instância**, uma leitura logo após a escrita já vê o dado (o projetor corre na mesma transação).
- **Entrega "pelo menos uma vez".** O publicador só marca a tarefa como `DONE` depois da confirmação do broker (*publisher confirm*);
  pode, por isso, publicar a mesma mensagem mais do que uma vez.
- **Consumidores idempotentes (padrão *Inbox*).** Cada evento aplicado fica registado na tabela `processed_event`
  (chave `consumer` + `eventId`, com `eventId` = UUID do evento, igual ao `messageId`), **na mesma transação** que o efeito.
  Uma mensagem repetida pelo broker é reconhecida e ignorada; se o efeito falhar, nada é registado e a mensagem pode ser
  tentada de novo. Os registos com mais de 7 dias são apagados de hora a hora (as filas têm TTL de 1 h, por isso um
  duplicado nunca chega tão tarde). Como segunda proteção, *last-writer-wins* impede que um evento antigo altere o estado.
- **Conflitos: *last-writer-wins*.** Ganha a alteração com `lastModified` mais recente; em caso de empate ganha o `originNodeId` maior.
  Um evento mais antigo do que o estado atual é ignorado. Os eventos da própria instância (devolvidos pelo fanout) também são ignorados.

Ver `docs/E2/sd-conflict-resolution.puml`.

## Decisões e alternativas consideradas

| Decisão | Porquê | Alternativa considerada | Porque não |
|---|---|---|---|
| **RabbitMQ** como broker | Filas por consumidor, *dead-letter*, *publisher confirms* e *quorum queues* prontos a usar; adequado a eventos de domínio | **Kafka** | Pensado para *streams* de grande volume e releitura de histórico; mais pesado de operar para este caso |
| **Exchange fanout + uma fila por instância** | Todas as instâncias recebem todos os eventos; a fila de uma instância parada guarda-os | **Uma fila partilhada** (*competing consumers*) | Cada evento chegaria só a uma instância: as outras ficavam desatualizadas |
| **Transactional outbox** | O evento é gravado na mesma transação do dado: não se perde nem se publica um evento de uma escrita falhada | Publicar diretamente no broker dentro do comando | *Dual write*: se o broker falhar depois do *commit*, o evento perde-se |
| | | *Change Data Capture* (Debezium) | Mais infraestrutura; o *polling* de 2 s chega para este volume |
| **Eventos com o estado necessário** (*event-carried state transfer*) | Cada evento basta para atualizar a outra instância, sem chamadas de volta | Eventos só com o id (*notification*) | A instância teria de pedir o dado por HTTP: voltava a dependência síncrona |
| **CQRS com projetor na mesma transação** | *Read-your-writes* na própria instância; modelo de leitura sempre alinhado | Projetor assíncrono (por evento) também localmente | Leitura logo após a escrita podia não ver o próprio dado |
| **Mesma base de dados, tabelas de leitura separadas** | Simples e transacional | Base de leitura separada (ex.: Elasticsearch) | Mais infraestrutura e mais um ponto de consistência eventual, sem ganho para este volume |
| **Leituras só locais** (sem reencaminhamento P2P) | Instâncias totalmente independentes; sem comunicação síncrona, que o enunciado aponta como limitação | Manter o reencaminhamento da Entrega 1 | Evita um 404 durante ~1 s, mas mantém dependência HTTP entre instâncias |
| **Inbox (`processed_event`)** para idempotência | Garante "no máximo uma vez" por evento, para qualquer tipo de operação (também para efeitos não idempotentes, como o histórico de rotas) | Só *last-writer-wins* | Chega para eventos que transportam estado, mas não protege efeitos cumulativos |
| ***Last-writer-wins*** por `lastModified` + `originNodeId` | Determinístico: todas as instâncias convergem para o mesmo estado, em qualquer ordem de chegada | Relógios vetoriais / CRDT | Mais complexos; com LWW perde-se uma de duas alterações concorrentes ao mesmo campo (limitação aceite) |
| **Flyway** (migrações SQL versionadas) | Esquema reproduzível e revisto no Git; cada alteração é um novo `V<n>__...sql`, aplicado uma só vez (`flyway_schema_history`) | `ddl-auto=update` do Hibernate | Altera o esquema sem controlo, nunca remove nem corrige colunas; inadequado em produção |
| **PostgreSQL por instância** (Modo B) | Database-per-Service real, persistente | H2 em memória | Perde os dados ao reiniciar; só usado nos testes e no Modo A (em ficheiro) |

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
| **Outra instância em baixo numa leitura** | Sem impacto: cada instância responde só com o seu modelo de leitura. (Com o reencaminhamento da Entrega 1 reativado, o *circuit breaker* abre ao fim de 3 falhas durante 10 s.) |
| **Nó do RabbitMQ em baixo** | O cliente usa a lista de 3 endereços (`SPRING_RABBITMQ_ADDRESSES`) e as filas *quorum* mantêm-se. |

Ver `docs/E2/sd-failure-recovery.puml` e `docs/E2/sd-amqp-event-propagation.puml`.

## E2.7 `GET /api/v2/unicorns` (humor dos aviões)

Este endpoint obrigatório pertence ao domínio das aeronaves e está implementado como *read model* no serviço
**Flight Operations** (ver o `README_E2.md` desse serviço, secção E2.7). Para os testes de integração em Postman existe também
um **mock** (`SISDIS_P2_unicorns_mock.postman_collection.json`) que devolve o estado de "humor" em JSON.
A ligação ao CQRS é a mesma: o humor é uma vista de leitura derivada de eventos, e não uma consulta ao serviço dono dos dados.

## E2.8 API – alterações face à Entrega 1

Os endpoints REST (URLs, perfis, códigos) não mudaram. Mudou **como** a replicação entre instâncias é feita (eventos em vez de HTTP direto) e as leituras deixaram de consultar a outra instância, por isso o cabeçalho `X-Partial-Result` já não é devolvido.

## E2.9 Configuração (novas variáveis)

| Propriedade / variável | Valor por omissão | Função |
|---|---|---|
| `app.replication.transport` | `amqp` | `amqp` (RabbitMQ) ou `http` (entrega direta aos pares, como na Entrega 1) |
| `app.messaging.exchange` | `airports.events` | Nome do exchange fanout |
| `app.messaging.queue.ttl-ms` | `3600000` (1 h) | TTL das mensagens nas filas |
| `spring.jpa.hibernate.ddl-auto` / `SPRING_JPA_HIBERNATE_DDL_AUTO` | `none` | O esquema é criado e alterado só pelo Flyway |
| `app.messaging.processed-event.retention` / `cleanup-interval` | `7d` / `1h` | Tempo que um evento processado fica em `processed_event` e frequência da limpeza |
| `app.messaging.queue.max-length` | `10000` | Limite de mensagens por fila |
| `peer.forwarding.max-hops` / `PEER_FORWARDING_MAX_HOPS` | `0` | `0` = leituras só locais (Entrega 2); `1` = reencaminhamento P2P da Entrega 1 |
| `PEER_NODE_ID` | `airports-routes-N` | Identificador da instância (usado em `originNodeId` e no nome da fila) |
| `SPRING_RABBITMQ_ADDRESSES` | `rabbitmq-1:5671,rabbitmq-2:5671,rabbitmq-3:5671` | Nós do cluster |
| `SPRING_RABBITMQ_SSL_ENABLED` | `true` | AMQP sobre TLS |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://airports-db-N:5432/airports` | Base de dados da instância |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `AIRPORTS_DB_PASSWORD` | no `.env` | Credenciais (não vão para o Git) |

## E2.10 Testes

- **Unitários e de integração:** `./mvnw test` na pasta do serviço — **87 testes, todos a passar** (captura
  `docs/screenshots/ar-02-testes-intellij.png`). Além dos da Entrega 1, cobrem:
  - consumidor AMQP (`ReplicationEventConsumerTest`): eventos próprios ignorados, rejeição sem reenfileirar (DLQ),
    duplicados ignorados e registo em `processed_event`;
  - Inbox (`IdempotencyGuardTest`): evento aplicado no máximo uma vez, nada registado se falhar, limpeza dos antigos;
  - publicador do outbox (`AmqpReplicationPublisherTest`): DONE só com confirmação do broker, backoff, FAILED às 10 tentativas;
  - outbox (`ReplicationOutboxTest`): uma tarefa por evento em AMQP vs uma por par em HTTP, metadados e utilizador;
  - consistência (`RouteReplicationConsistencyTest`): *last-writer-wins*, desempate por `originNodeId`, idempotência,
    convergência independente da ordem de chegada, eventos replicados nunca republicados;
  - CQRS (`AirportProjectorTest`, `ReadModelRebuilderTest`) e replicação da imagem (`AirportImageReplicationTest`).
- **Postman (Entrega 2)**, pasta `tests(postman)/` na raiz do repositório (coleções do sistema completo):
  - `SIDIS – Entrega 2 Modo B (Docker)` — **125 testes a passar** (`ar-07-postman-e2-modo-b.png`): escreve por uma gateway
    e lê pela outra até convergir; as leituras do Airports levam `X-P2P-Hops: 1`, por isso um 200 prova que o evento chegou
    pelo RabbitMQ. Inclui Flight Operations, eventos entre serviços, eventos de Aircraft, DLQ, `/api/v2/unicorns` e o mock.
  - `SIDIS – Entrega 2 Modo A (IntelliJ)` — **188 testes a passar** (`ar-08-postman-e2-modo-a.png`): o mesmo sobre 3
    instâncias de cada serviço com acesso direto, incluindo `ROUTE_UPDATED`, `AIRPORT_DETAILS_UPDATED` e *last-writer-wins*.
  - Pastas MANUAL: parar uma instância, escrever noutra e ver a fila dela guardar o evento até voltar.
  - Os testes que publicam diretamente no RabbitMQ precisam, no environment, de `rabbitUser`, `rabbitPassword`
    (valores do `.env` no Modo B; `aisafe` / `aisafe-mq` no Modo A) e `rabbitMgmt` (`http://localhost:15672`).
- **Postman (Entrega 1, Modo B):** 348 testes (`ar-01-postman-348-modo-b.png`).
- **Evidências:** `docs/screenshots/` — filas *quorum* com TTL, limite, DLX e DLQ no RabbitMQ (`ar-04-rabbitmq-filas.png`),
  modelo de leitura reconstruído (`ar-06-modelo-leitura.png`), mock dos unicorns (`ar-03*`).

## E2.11 Limitações conhecidas

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
src/main/resources/db/migration/   migrações Flyway (V1: escrita, outbox, inbox e modelo de leitura)
  security/       validação de JWT (Keycloak RS256) e token interno entre instâncias
```

## E2.13 Diagramas

Pasta [`docs/`](docs/) (PlantUML), organizada por entrega: [`docs/E2/`](docs/E2/) (esta entrega) e [`docs/E1/`](docs/E1/) (Entrega 1).

| Ficheiro | Mostra |
|---|---|
| `E2/c4-component-airports-routes-p2.puml` | Componentes (CQRS, outbox, mensageria) |
| `E2/physical-view-airports-routes.puml` | Vista física (Modo B: gateways, cluster RabbitMQ, bases por instância) |
| `E2/physical-view-airports-routes-mode-a.puml` | Vista física (Modo A: IntelliJ, H2 por instância, RabbitMQ e PostgreSQL do FO em Docker) |
| `E2/class-diagram-airports-routes.puml` | Modelo de domínio (lado da escrita), modelo de leitura CQRS e outbox |
| `E2/messaging-topology-airports-routes.puml` | Topologia AMQP (exchange, filas *quorum*, DLQ) |
| `E2/sd-amqp-event-propagation.puml` | Propagação de um evento entre instâncias |
| `E2/sd-cqrs-query-read-model.puml` | Uma query a ler só o modelo de leitura |
| `E2/sd-failure-recovery.puml` | Broker em baixo, instância parada, mensagem inválida |
| `E2/sd-conflict-resolution.puml` | Resolução de conflitos (*last-writer-wins*) |
| `E1/sd-create-airport-outbox.puml`, `E1/sd-create-route-outbox.puml`, `E1/sd-csv-import-outbox.puml` | Comandos com outbox (Entrega 1: entrega por HTTP; na Entrega 2 ver `E2/sd-amqp-event-propagation.puml`) |
| `E1/sd-circuit-breaker.puml`, `E1/sd-p2p-forwarding-read.puml` | Leituras entre instâncias (Entrega 1; desligadas por omissão na Entrega 2) |
