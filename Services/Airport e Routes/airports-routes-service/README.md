# AISafe – Airports & Routes Service

Serviço do sistema distribuído **AISafe Flight Management System**. É responsável pela gestão da infraestrutura estática e topológica da aviação:
    **aeroportos** (caraterísticas operacionais, pistas, facilidades) 
    e **rotas aéreas** (alcance mínimo, tempo de voo, capacidade mínima).

O serviço também permite adicionar muitos aeroportos de uma só vez a partir de um ficheiro, 
exportar os dados para desenhar o mapa da rede de voos, e procurar alternativas de viagem com escalas quando não existe ligação direta entre dois aeroportos.
O serviço corre em **2 a 3 réplicas** (3 no Modo A, no IntelliJ; 2 no Docker), cada uma com a sua própria base de dados local. 
A arquitetura adota um padrão de **replicação P2P assíncrona (Outbox Pattern)**: as alterações são imediatamente guardadas e confirmadas ao utilizador, 
sendo enfileiradas e replicadas para os pares em segundo plano (*consistência eventual*).


Ao contrário de outros serviços, **não existe distinção de URL entre API pública e interna**:
as réplicas comunicam entre si nos mesmos endpoints, usando cabeçalhos estritos (`X-P2P-Hops` e `X-P2P-Sync`) e verificação JWT de permissões (`ROLE_PEER`).

Os clientes entram pela **API Gateway (NGINX)** e autenticam-se no **Keycloak**. A comunicação utiliza **HTTPS**.

---

## 1. Arquitetura

| Componente                                  | Responsabilidade                                                                                                                                                                      |
|---------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AirportController` & `RouteController`     | API unificada (`/api/airports`, `/api/routes`). Serve tanto clientes como réplicas. Respostas HATEOAS, interceção de paginação e paginação degradada (`X-Partial-Result`).            |
| `AdminReplicationController`                | API de administração para visualização do estado da fila de tarefas da *Outbox* local.                                                                                                |
| `AirportService` & `RouteService`           | Orquestração local vs distribuída, cálculo de estatísticas (aeroportos mais movimentados, distância total), CRUD e *forwarding* síncrono por *Hops*.                                  |
| `FastestRouteStrategy`                      | Algoritmo de grafos **BFS** (US216). Junta rotas locais com rotas dos pares, funde-as (*Last-Writer-Wins*) e calcula caminhos multi-escala com regras de *Minimum Connection Time* (60m). |
| `Airport` & `Route` + Value Objects         | Agregados: `IATACode`, `Coordinates`, `Runway`, `RouteId`, `RouteHistory` (rastreio de ativação/desativação). Incluem lógicas para resolver metadados de replicação.                  |
| `ReplicationOutbox`                         | Regista os dados de escrita (JSON) na entidade ReplicationTask na mesma transação local **(At-Least-Once delivery)**.                                                                 |
| `ReplicationTaskScheduler`                  | Tarefa automática em segundo plano que lê as tarefas pendentes e as envia aos pares a cada 5 segundos. Aplica *backoff exponencial* em caso de falha de rede.                         |
| `PeerResilienceInterceptor`                 | Intercetor customizado do `RestTemplate` que atua como **Circuit Breaker**. Protege o serviço contra nós bloqueados (timeout após 3 falhas, abre por 10s).                            |
| `JwtAuthenticationFilter` + `TokenDecoders` | Valida o JWT (**RS256 do Keycloak** via JWKS, emissor, expiração) e suporta tokens HS256 para comunicação interna assinada.                                                           |
| `PeerReplicationAuthorizer`                 | Segurança "Zero Trust": impede que clientes externos forjem chamadas de replicação, exigindo rigorosamente o perfil `ROLE_PEER` quando `X-P2P-Sync` é ativado.                        |
| `AuditService` & `CorrelationIdFilter`      | Guarda o histórico de todas as alterações com um identificador  (*Correlation ID*), permitindo rastrear exatamente quem fez o quê.                                                                       |
| `HalResponseUtils`                          |Lê os dados organizados em páginas que vêm das outras réplicas, mantendo intactos todos os links de navegação (HATEOAS).                       |
| `GlobalExceptionHandler`                    | Traduz as exceções do sistema (como falha de replicação ou violação de regras) diretamente para códigos HTTP de erro (`ProblemDetail`).                                                                               |

Infraestrutura comum (pasta `infrastructure/` na raiz do repositório): **API Gateway NGINX** (`https://localhost:8443`) 
e **Keycloak** (`http://localhost:8180`, realm `aisafe`).

Serviços com que este serviço colabora (como provedor):
* **Flight Operations:** Consulta frequentemente o `Airports & Routes` para validar se a rota está ativa 
* e verificar o alcance/capacidade necessários antes de agendar voos.

---

## 2. Pré-requisitos

* **JDK 17** (o projeto compila nativamente em Java 17) e Maven (ou IntelliJ IDEA).
* **Keycloak** e **NGINX** a correr (ver `infrastructure/README.md`).
* Certificado de desenvolvimento do grupo (`keystore.p12`) e respetiva truststore.
* Postman (para testar baterias de resiliência e importação multipart/form-data).
* Para o Modo Docker: Docker Desktop.

---

## 3. Configuração

Centralizada em `src/main/resources/application.properties`, com perfis dinâmicos (`inst1`, `inst2`, `inst3`).

| Variável / Propriedade                       | Exemplo / Omissão                               | Descrição                                                                                 |
|----------------------------------------------|-------------------------------------------------|-------------------------------------------------------------------------------------------|
| `server.port`                                | `8091`, `8092`, `8093`                          | Porta HTTPS da réplica local.                                                             |
| `peer.urls`                                  | `https://localhost:8092,https://localhost:8093` | URLs dos nós vizinhos. Usados para *Forwarding* e *Outbox*.                               |
| `peer.forwarding.max-hops`                   | `1`                                             | Limite do cabeçalho `X-P2P-Hops` para evitar ciclos infinitos na rede.                    |
| `app.http.circuit-breaker.failure-threshold` | `3`                                             | Número de falhas (timeouts) num par até o circuito abrir.                                 |
| `app.http.circuit-breaker.open-duration`     | `10000` (10s)                                   | Tempo de recuperação antes de tentar o nó isolado novamente.                              |
| `spring.datasource.url`                      | `jdbc:h2:file:./data/airports_db_1`             | Cada réplica mantém a sua própria BD local para isolamento e velocidade.                  |
| `jwt.secret`                                 | `change-this-development-secret...`             | Segredo usado pelo `JwtClientHttpRequestInterceptor` para gerar tokens P2P (`ROLE_PEER`). |
| `app.security.jwt.jwk-set-uri`               | `http://localhost:8180/.../certs`               | Chave pública do Keycloak para validar tokens dos utilizadores.                           |

---

## 4. Como executar

Existem três modos de execução (ver detalhes em `infrastructure/README.md`).

### 4.1. Certificados e Segurança (uma vez só)
- Colocar o certificado de segurança **(keystore.p12)** em `src/main/resources/` e preparar a *truststore* local
correspondente para permitir a comunicação HTTPS.

*(Nota: Ao correr o projeto via Docker, os caminhos para a truststore são injetados automaticamente no arranque 
através das variáveis de ambiente no ficheiro `docker-compose.yml`).*

### 4.2. Arranque da Infraestrutura (Keycloak)
Antes de iniciar os microsserviços, é necessário garantir que o servidor de autenticação (Keycloak) 
e as respetivas bases de dados estão operacionais. Na pasta de infraestrutura, executar o script de arranque:
```powershell
.\start-infra.ps1
```
(Isto garante que o Keycloak fica a correr na porta 8180 com o realm aisafe pré-configurado).


### 4.3. Três réplicas no IntelliJ (Modo A)
- Criar três *Run Configurations* para `AirportsRoutesServiceApplication`.
- **Perfis ativos (*Active profiles*):** Definir `inst1`, `inst2`, e `inst3` em cada configuração respetivamente.
- Os *Bootstrappers* (`AirportBootstrapper`) encarregam-se de injetar os dados iniciais automaticamente no arranque.



### 4.4. Modo Docker Seguro
Na pasta `infrastructure/docker`, gerar os certificados (`gen-certs.ps1`) e na raiz do projeto correr:
`docker compose -f docker-compose.secure.yml up --build`
O Airports & Routes ficará disponível através do NGINX nas instâncias `airports-routes-1` e `airports-routes-2`.

---

## 5. API

Este serviço funde a API pública com a API de sincronização, garantindo o princípio *DRY* (*Don't Repeat Yourself*).
O encaminhamento HATEOAS ajusta os links para a API Gateway.

### 5.1 Endpoints de Negócio (exigem `Authorization: Bearer <JWT>`)

| Método | Endpoint                     | Descrição                                                                                                                       | Perfis             |
|--------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------|--------------------|
| `POST` | `/api/airports`              | Criação de Aeroporto. Guarda localmente e enfileira na *Outbox*.                                                                | ADMIN, ATCC        |
| `GET`  | `/api/airports/{iata}`       | Consulta Aeroporto. Faz P2P Forwarding se não encontrar localmente.                                                             | ALL                |
| `POST` | `/api/airports/import`       | US114 - Importa CSV (`multipart/form-data`) e envia os registos em lote para a Outbox.                                          | ADMIN              |
| `POST` | `/api/routes`                | US110 - Cria Rota. Valida se os aeroportos existem na rede.                                                                     | ADMIN              |
| `GET`  | `/api/routes/alternatives`   | US216 - Calcula rotas por largura (`?originIata=X&destinationIata=Y`) aplicando um tempo mínimo de conexão (MCT) de 60 minutos. | ATCC, ADMIN        |
| `GET`  | `/api/routes/export/geojson` | US228 - Devolve a topologia ativa da rede em formato geoespacial `application/geo+json`.                                        | ADMIN, BACKOFFICE  |
| `GET`  | `/api/routes/airport/{iata}` | Paginação HATEOAS P2P: Funde as páginas da BD local com as dos pares.                                                           | ALL                |

* Se uma réplica remota falhar numa leitura paginada agregada, o endpoint retorna `200 OK` acompanhado do cabeçalho `X-Partial-Result: true`.

### 5.2 O Mecanismo P2P Sem `/internal/`

Em vez de controladores duplicados, a replicação é tratada através de metadados HTTP diretamente na API principal:

* **Leituras Distribuídas (`X-P2P-Hops`):** Quando um utilizador faz `GET /api/airports/LHR`, vai sem este header (ou hops=0). 
Se não existir na BD local, o `AirportService` lança pedidos `GET` aos pares injetando `X-P2P-Hops=1`.
* 
Se o peer também não tiver o dado, ele lê o *hop*, vê que atingiu o máximo, e recusa-se a reencaminhar, 
quebrando o ciclo e respondendo `404`.



* **Escritas Sincronizadas (`X-P2P-Sync`):** O `ReplicationTaskScheduler` consome a fila Outbox e envia `POST`/`PATCH` aos pares
com `X-P2P-Sync=true`. Um intercetor HTTP assina o pedido com um JWT contendo `ROLE_PEER`. 
* 
O servidor que recebe este pedido, ao ver o header, ignora as verificações normais de negócio, 
grava diretamente na BD via resolução de conflitos temporais (`X-Last-Modified`) e não volta a colocar este dado 
na sua própria Outbox (evitando replicação em loop).

### 5.3 Ciclo de vida da Rota (Histórico)
Sempre que uma rota transita entre `ACTIVE` e `INACTIVE` (ex: `PATCH /api/routes/{id}/deactivate`), 
a transação fecha a data-fim (`endDate`) da linha de histórico anterior na tabela `RouteHistory`
e cria uma nova entrada ("DEACTIVATED"). Todo este agregado é replicado atomicamente.

---

## 6. Decisões de Arquitetura

1. **Ausência de API Interna (Zero Trust):**
A decisão de usar os mesmos controladores baseia-se em *Defesa em Profundidade*. 
Em vez de confiarmos apenas no bloqueio da Gateway, o serviço delega a proteção do tráfego interno ao `PeerReplicationAuthorizer`,
que exige tokens criptográficos rigorosos (Role-Based Access Control).

2. **Baixa Latência de Escrita (AP):**
Um administrador não tem de esperar que as outras instâncias estejam ativas para importar 50 aeroportos via CSV. 
A escrita entra de imediato e o utilizador segue a sua vida (Prioriza a *Disponibilidade* do Teorema CAP).

3. **Resolução de Conflitos *Last-Writer-Wins*:**
O payload JSON de replicação traz o campo `X-Last-Modified`.
Se o registo local já for mais recente do que a cópia de sincronização recebida, o nó simplesmente ignora a sobreposição, 
garantindo convergência estável.

4. **Unificação Dinâmica da Topologia de Rede:**
   Antes de calcular a melhor rota (US216), o algoritmo BFS lê as rotas guardadas no próprio servidor, pede as rotas ativas 
aos servidores vizinhos e junta tudo na memória. Isto permite que o sistema tenha uma visão completa de todos os caminhos 
disponíveis na rede, evitando escolher uma rota pior ou com demasiadas escalas.

---

## 7. Tolerância a Falhas

| Cenário                                            | Comportamento                                                                                                                                                                                                                          |
|----------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Réplica vizinha offline (durante escrita)          | A escrita é bem-sucedida localmente. Fica na fila da `ReplicationOutbox`. Backoff exponencial (5s -> 10s -> 20s) tenta entrega posterior.                                                                                              |
| Réplica vizinha offline (durante Forwarding)       | Se o sistema tentar reencaminhar a leitura e o par estiver morto, o sistema retorna `503 Service Unavailable` à Gateway (porque o dado *pode* existir lá, logo não dá `404` falso).                                                    |
| Réplica vizinha offline (agregação HATEOAS)        | A query devolve as rotas conhecidas localmente e injeta o header `X-Partial-Result: true`, informando o cliente de que o resultado é parcial devido a uma falha de comunicação.                                                        |
| Falhas sucessivas de rede a um par                 | O `PeerResilienceInterceptor` ativa o Circuit Breaker perante falhas de um par, falhando chamadas futuras de imediato para proteger os recursos do sistema.                                                                            |
| Ciclos infinitos de reencaminhamento               | O cabeçalho `X-P2P-Hops` controla quantos saltos um pedido pode dar entre réplicas. Quando chega ao limite (max-hops=1), deixa de reencaminhar.                                                                                        |
| Arranque Limpo do Sistema (Base de Dados H2 Vazia) | O `AirportBootstrapper` inicializa os dados base locais no arranque. Em simultâneo, a base de dados sincroniza-se via consistência eventual assim que os nós vizinhos processam e entregam as *tasks* pendentes na respetiva *Outbox*. |

---

## 8. Segurança

Perfis do AISafe suportados: **Admin, ATCC, Backoffice**.

* **Autenticação Dupla:**
Suporta JWTs nativos do utilizador logado no Keycloak (com assinatura **RS256**) 
e suporta tokens locais entre microsserviços (com assinatura **HS256**), validados dinamicamente pelo `JwtAuthenticationFilter`.


* **Autorização (RBAC):** 
Regras de acesso bloqueadas por endpoint e método, garantindo modelo *deny by default*.


* **Proteção HATEOAS:**
Links gerados não expõem detalhes da rede interna; 
utilizam domínios da gateway recebidos por cabeçalhos padrão (`X-Forwarded-Host`).


* **Registo de Auditoria (`MDC`):** 
Todas as mutações e ingestões (CSV incluído) emitem *logs* cruzados e invioláveis:
`[CorrelationID: 8f3c...] User: admin - Action: IMPORT_CSV - Target: AIRPORTS - Status: SUCCESS`.

---

## 9. Testes

### 9.1 JUnit & Mockito

* **Algoritmos de Domínio:**
Testes exaustivos ao `FastestRouteStrategy` para validar regras do *Minimum Connection Time* (MCT)
e desvios BFS corretos com múltiplas escalas.

* **Mecânicas de Resiliência:** 
Testes aos interceptores HTTP para forçar a abertura do *Circuit Breaker* ao fim de 3 *Timeouts*
e testar o encerramento do ciclo de Hops.

* **Integração Outbox:** Testes para garantir que escritas locais despoletam transações pendentes
e confirmam a injeção do JWT de replicação na tentativa do *Scheduler*.

### 9.2 Postman

Coleção completa de testes passando sempre pela API Gateway:

* Valida a recusa do token se forjado, *rate-limits* aplicados pelo NGINX e testes ponta-a-ponta na submissão de ficheiros multipart (CSV).

* **Testes Distribuídos Manuais:**
Casos de uso documentados para parar a Réplica 2, submeter Rota na Réplica 1,
* ligar a Réplica 2 e atestar a chegada do registo via *Outbox scheduler*.

---

## 10. Limitações conhecidas e trabalho futuro

| Limitação                                    | Impacto                                                                                                                                   | Trabalho Futuro                                                                                                     |
|----------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| Base de dados H2 em memória                  | Se todos os servidores reiniciarem ao mesmo tempo, os dados perdem-se por completo.                                                       | Usar uma base de dados que guarde a informação permanentemente no disco (como PostgreSQL ou MySQL).                 |
| Sincronização dependente da base de dados    | O servidor consome recursos desnecessários por estar sempre a verificar a base de dados de 5 em 5 segundos à procura de dados para enviar. | Usar um sistema próprio para gestão e envio de mensagens entre servidores (como Kafka ou RabbitMQ).                 |
| Alto consumo de memória na pesquisa de rotas | O algoritmo carrega todas as rotas para a memória do servidor. Com milhões de rotas, o sistema pode ir abaixo por falta de RAM.           | Usar uma base de dados especializada em encontrar caminhos e gerir rotas (como o Neo4J).                            |
| Lista fixa de servidores vizinhos            | Para adicionar um novo servidor à rede, é preciso parar o sistema e alterar o ficheiro de configuração manualmente.                       | Usar uma ferramenta que descubra e adicione novos servidores automaticamente à rede (como o Netflix Eureka).        |
| Sincronização lenta ao ligar o servidor      | Se um servidor estiver muito tempo desligado, pode demorar a ficar com os dados todos iguais aos dos vizinhos quando volta a ligar.       | Criar um mecanismo onde o servidor que acaba de ligar pede imediatamente toda a informação atualizada aos vizinhos. |
---

## 11. Estrutura do projeto

```text
├── infrastructure/         # Configurações do NGINX, Keycloak, Docker e Certificados
└── Services/
    └── Airport e Routes/
        └── airports-routes-service/
            ├── data/       # Base de dados H2 local (gerada em runtime)
            ├── docs/       # Documentação e diagramas
            ├── src/
            │   ├── main/java/pt/isep/psoft/aisafe/airports_routes_service/
            │   │   ├── api/           # Controllers unificados (AirportController, RouteController)
            │   │   ├── application/   # Serviços de orquestração e lógicas de negócio
            │   │   ├── bootstrapper/  # Injeção de dados iniciais no arranque
            │   │   ├── config/        # Configurações de Beans, RestTemplate, Security
            │   │   ├── domain/        # Agregados (Airport, Route), Entidades e Value Objects
            │   │   ├── repositories/  # Repositórios Spring Data JPA (incluindo ReplicationTask)
            │   │   ├── security/      # Filtros JWT e autorização RBAC P2P
            │   │   ├── util/          # Classes utilitárias e ajudantes
            │   │   └── AirportsRoutesServiceApplication.java
            │   ├── main/resources/
            │   │   ├── application*.properties # Configurações base e perfis das réplicas
            │   │   ├── keystore.p12            # Certificado do serviço
            │   │   ├── static/
            │   │   └── templates/
            │   └── test/              # Testes unitários e de integração
            ├── Dockerfile
            ├── pom.xml
            └── README.md