# AISafe – Airports & Routes Service

Serviço do sistema distribuído **AISafe Flight Management System** responsável pela gestão da infraestrutura base da aviação: 
    **aeroportos** (com pistas, facilidades e dados operacionais) 
    e **rotas aéreas** (com parâmetros de tempo, alcance, capacidade, histórico de alterações e pesquisa avançada de rotas alternativas).

O serviço corre em **2 a 3 réplicas** (3 no Modo A, no IntelliJ; 2 no Docker), cada uma com a sua própria base de dados em memória (H2).
As escritas utilizam um padrão **Outbox Assíncrono** para a **replicação P2P** (*peer-to-peer*): a criação e alteração são gravadas 
localmente e replicadas em background, enquanto as consultas que não encontram dados localmente executam *forwarding peer-to-peer* 
em paralelo. Os clientes entram pela **API Gateway (NGINX)** e autenticam-se no **Keycloak**, validando o JWT e aplicando regras 
de acesso por perfil (**RBAC**). Toda a comunicação utiliza **HTTPS**.

---

## 1. Arquitetura

| Componente                                       | Responsabilidade                                                                                                                                               |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AirportController` & `RouteController`          | API unificada (`/api/airports`, `/api/routes`). Leituras distribuídas, respostas HATEOAS e suporte a autorização via header `X-P2P-Sync`.                      |
| `AdminReplicationController`                     | API de administração (`/api/admin/replication-tasks`) para monitorização do estado das tarefas de sincronização Outbox.                                        |
| `AirportService` & `RouteService`                | Orquestração de negócio: CRUD, cálculo de estatísticas (aeroportos mais movimentados, distância total da rede), deduplicação (*Last-Write-Wins*) e forwarding. |
| `FastestRouteStrategy`                           | Estratégia de grafos baseada em BFS (*Breadth-First Search*) para a US216 (pesquisa de rotas alternativas com múltiplas escalas e regras de MCT).              |
| `ReplicationOutbox` & `ReplicationTaskScheduler` | Padrão Outbox: enfileira alterações na BD e um scheduler em background reenvia tarefas pendentes a cada 5s com backoff exponencial.                            |
| `PeerResilienceInterceptor`                      | *Circuit Breaker* customizado no RestTemplate para proteger o servidor contra nós vizinhos lentos ou inoperantes.                                              |
| `Airport` & `Route` + Value Objects              | Agregados JPA: `IATACode`, `Coordinates`, `Runway`, `Facility`, `RouteId`, `RouteHistory` (controlo histórico de ativação/desativação).                        |
| `JwtAuthenticationFilter`                        | Valida o JWT (**RS256 do Keycloak** via JWKS ou tokens HS256 de desenvolvimento/testes internos).                                                              |
| `PeerReplicationAuthorizer`                      | Valida se o emissor possui a `ROLE_PEER` quando o header `X-P2P-Sync` está ativo, prevenindo o forjamento de chamadas de replicação.                           |
| `AuditService` & `CorrelationIdFilter`           | Registo de auditoria (logger `AUDIT`) com suporte a Correlation ID e MDC (*Mapped Diagnostic Context*).                                                        |
| `GlobalExceptionHandler`                         | Traduz exceções de domínio e infraestrutura para códigos HTTP normalizados em formato `ProblemDetail`.                                                         |

Infraestrutura comum (pasta `infrastructure/` na raiz do repositório): **API Gateway NGINX** (`https://localhost:8443`) e **Keycloak** (`http://localhost:8180`, realm `aisafe`).

Serviços que colaboram com este microsserviço:
* **Flight Operations:** Consulta rotas e aeroportos deste serviço para validar restrições de capacidade e alcance antes de agendar voos.

---

## 2. Pré-requisitos

- JDK 17 ou superior e Maven (ou IntelliJ IDEA).
- **Keycloak** e **NGINX** a correr (ver `infrastructure/README.md`).
- Certificado de desenvolvimento do grupo (`keystore.p12`) e truststore configurada.
- Postman para testes de integração e resiliência.

---

## 3. Configuração

Configurado através de `src/main/resources/application.properties` e perfis específicos (`application-inst1.properties`, etc.):

| Variável / Propriedade | Omissão / Exemplo | Descrição |
|---|---|---|
| `server.port` | `8091` (inst1) | Porta HTTPS da réplica |
| `peer.urls` | `https://localhost:8092,https://localhost:8093` | URLs HTTPS dos nós pares |
| `peer.forwarding.max-hops` | `1` | Limite máximo de saltos para forwarding de leitura P2P |
| `spring.datasource.url` | `jdbc:h2:file:./data/airports_db_1` | Caminho para a base de dados H2 isolada |
| `jwt.secret` | `change-this-development-secret...` | Segredo para tokens HMAC gerados entre réplicas (`ROLE_PEER`) |
| `app.security.jwt.jwk-set-uri` | `http://localhost:8180/.../certs` | URI do Keycloak para validação de tokens RS256 |

---

## 4. Como executar

### 4.1. Três réplicas no IntelliJ
Criar três *Run Configurations* para `AirportsRoutesServiceApplication` com as VM options de truststore SSL e os perfis ativos correspondentes (`inst1`, `inst2`, `inst3`).

### 4.2. Modo Docker
Executar através do ficheiro `docker-compose.secure.yml` na raiz do repositório para levantar a stack completa com réplicas balanceadas pelo NGINX.

---

## 5. API

### 5.1. Endpoints Públicos (`/api/airports`, `/api/routes`) – Exigem Bearer JWT
* `POST /api/airports` – Registo de aeroporto (suporta replicação e outbox).
* `GET /api/airports/{iataCode}` – Detalhes do aeroporto (com suporte a forwarding de saltos via `X-P2P-Hops`).
* `GET /api/airports/search` – Pesquisa flexível por nome, cidade ou país (retorna `X-Partial-Result` em caso de falha de peers).
* `PATCH /api/airports/{iataCode}/status` – Atualiza o estado operacional.
* `POST /api/airports/import` – Ingestão em massa de aeroportos via ficheiro CSV (`MultipartFile`).
* `POST /api/routes` – Criação de nova rota aérea.
* `GET /api/routes/{id}` – Detalhes de rota com suporte a HATEOAS e P2P forwarding.
* `GET /api/routes/alternatives` – US216: Pesquisa de rotas alternativas com escala baseada em algoritmo BFS.
* `GET /api/routes/export/geojson` – US228: Exportação de toda a rede de rotas em formato GeoJSON (`application/geo+json`).
* `GET /api/routes/statistics/total-distance` – US215: Distância total da rede ativa.

---

## 6. Decisões de Arquitetura

1. **API Unificada e Segurança Baseada em Roles:** Ao contrário de separar controladores públicos e internos, os endpoints são partilhados. A segurança garante que apenas chamadas com `ROLE_PEER` (via header `X-P2P-Sync` e token assinado) conseguem injetar dados de replicação, mantendo o código limpo (*DRY*).
2. **Padrão Outbox para Replicação Assíncrona:** As escritas são imediatas no nó de origem e persistidas numa tabela de tarefas. Um agendador de background encarrega-se da entrega aos pares, garantindo resiliência contra falhas temporárias de rede.
3. **Resolução de Conflitos (*Last-Write-Wins*):** Cada entidade guarda metadados de modificação (`lastModified` e `originNodeId`). As fusões de dados entre réplicas privilegiam sempre a versão temporal mais recente.
4. **Pesquisa em Grafo Resiliente (BFS):** O cálculo de rotas alternativas não se limita à base de dados local; interroga preventivamente os nós vizinhos para construir um mapa global atualizado da rede.
5. **Degradação Graciosa e Respostas Parciais:** Se um nó vizinho falhar numa consulta de agregação, o sistema emite um resultado parcial acompanhado pelo cabeçalho `X-Partial-Result`, priorizando a disponibilidade (AP no teorema CAP).

---

## 7. Tolerância a Falhas

| Cenário | Comportamento do Sistema |
|---|---|
| Réplica vizinha em baixo durante escrita | A tarefa fica pendente na tabela Outbox e é reenviada automaticamente em background quando o nó recuperar. |
| Nó de leitura em baixo (forwarding) | O sistema tenta os restantes peers; se um deles falhar por completo a resposta, devolve `503 Service Unavailable` em vez de um falso `404`. |
| Falha temporária de rede entre serviços | O *Circuit Breaker* (`PeerResilienceInterceptor`) abre ao fim de 3 falhas consecutivas, protegendo os recursos do servidor (*fast-fail*). |

---

## 8. Testes

Execução através do Maven (`mvn test`) ou diretamente no IntelliJ. O projeto inclui testes unitários e de integração focados na validação de repositórios, regras de negócio de aeroportos/rotas, persistência outbox e fiabilidade do forwarding P2P.