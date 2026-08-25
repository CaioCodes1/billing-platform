# billing-platform

API REST de assinaturas recorrentes e cobrança. **Único projeto Java do
workspace** — os outros seis são Node.

Java 21 · Spring Boot 3.5.3 · PostgreSQL 16 · Flyway · MapStruct · Spring
Security (JWT) · ShedLock · Testcontainers.

## Rodar

Não tem entrada no `.claude/launch.json` da raiz (API pura, sem front).

```bash
docker compose up -d      # Postgres na 5433, Mailpit na 8025
mvn spring-boot:run       # app na 8080, Swagger em /docs
mvn verify                # testes; exige Docker (Testcontainers)
```

O Postgres usa a **5433** no host de propósito: o `stockflow` e o
`beacon-analytics` já ocupam a 5432.

## Ambiente

Toolchain instalado em 23/08/2026, quando este projeto começou:

| | Onde | Observação |
|---|---|---|
| JDK 21 | `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot` | `winget install Microsoft.OpenJDK.21`; define `JAVA_HOME` sozinho |
| Maven 3.9.16 | `D:\dev-tools\apache-maven-3.9.16` | **Não existe no winget** — baixado do CDN da Apache com verificação de SHA-512 |
| Repositório `.m2` | `D:\dev-cache\m2` | Via `~/.m2/settings.xml`. Cresce para vários GB; o `C:` tem ~14 GB livres |

Mesma lógica do cache do npm: o que cresce sem limite mora no `D:`.

### ⚠️ Testcontainers × Docker Engine 29 (obrigatório, senão nenhum teste roda)

O docker-java que vem com o Testcontainers 1.21.2 negocia **API 1.32**; o Engine
29.7.2 exige **mínimo 1.40**. Sem os dois ajustes abaixo, todo teste de
integração morre com `Could not find a valid Docker environment` — mesmo com
`docker run` funcionando normalmente.

| Ajuste | Onde | Valor |
|---|---|---|
| Versão da API | `~/.docker-java.properties` | `api.version=1.44` |
| Endpoint | variável de ambiente `DOCKER_HOST` (nível usuário) | `npipe:////./pipe/docker_engine_linux` |

**Por que o `DOCKER_HOST` também é necessário:** os pipes padrão
(`docker_engine`, `dockerDesktopLinuxEngine`) passam por um proxy do Docker
Desktop que responde **400 com um objeto `Info` inteiramente vazio**, escondendo
a causa real. O `docker_engine_linux` é o engine cru e devolve a mensagem de
verdade (`client version 1.32 is too old`). O CLI não é afetado — ele negocia
1.55 — então `docker info` funcionar não prova nada sobre o Testcontainers.

Ambos já estão aplicados nesta máquina. `~/.docker-java.properties` **não**
sobrevive a formatação; o `DOCKER_HOST` tampouco.

## Decisões que não são óbvias pelo código

Todas justificadas em [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md). As que mais
geram "conserto" indevido:

- **A cobrança nasce do ciclo, não do pagamento.** Foi uma correção deliberada
  do enunciado original: se a próxima fatura dependesse do pagamento da
  anterior, quem não paga nunca receberia a segunda e a régua de inadimplência
  não teria insumo. Assinatura `SUSPENDED` não gera cobrança nova.
- **`subscriptions.unit_price` é cópia, não referência.** Nunca faturar lendo
  `plans.monthly_price`, senão reajuste de plano repreça contrato vivo.
- **`SUSPENDED` ocupa a vaga de assinatura do cliente.** Só `CANCELLED` libera.
- **`payments` é append-only**, com trigger no banco. Para desfazer, lançar
  `type = 'REFUND'`. O status da fatura é derivado da soma.
- **`billing_day` é separado de `current_period_start`** por causa do dia 31 —
  ver seção 5.2 do doc.
- **`@PreAuthorize` fica no service, não no controller.** A regra vale mesmo se
  a rota mudar ou se outro caminho chamar o mesmo método.
- **`payments` é imutável e o status da cobrança é derivado da soma do razão.**
  Nunca atribuir `PAID` olhando um pagamento isolado.
- **`BillingItemProcessor` é bean separado do `BillingRunner`** porque
  `@Transactional` só vale em chamada que entra no bean de fora. Self-invocation
  não abre transação — silenciosamente.
- **`document` do cliente é imutável** e não aparece no `UpdateCustomerRequest`:
  ele identifica o pagador na fatura, trocá-lo reescreveria histórico fiscal.
- **`DELETE /customers/{id}` é desativação lógica**, nunca remoção física —
  cliente é referenciado por assinaturas, cobranças e pagamentos.
- **`saveAndFlush` no cadastro, `flush()` antes de mapear no update.** Os
  carimbos `created_at`/`updated_at` vêm de trigger; sem o flush o Hibernate os
  releria depois da resposta pronta e o cliente receberia `createdAt` nulo.
- **`Subscription` não tem `setter`.** Toda mudança é método de ação
  (`cancel`, `suspend`, `activate`), e cada um consulta
  `SubscriptionStatus.podeIrPara` antes. As transições são um `EnumMap` no
  enum, não `if` espalhados — estado novo obriga a declarar para onde vai.
- **Nenhum service chama `LocalDate.now()`.** Todos recebem `Clock` injetado
  (`ClockConfig`), que usa `billing.timezone`. Em container o fuso do SO é UTC
  e uma fatura venceria um dia antes para o cliente.
- **Assinatura não tem `PUT`.** Cancelar, suspender, trocar plano e
  reprecificar são endpoints separados — pré-condições, efeitos e (na fase 7)
  permissões diferentes.
- **`@EntityGraph` em `SubscriptionRepository`.** `customer` e `plan` são LAZY;
  sem o grafo, listar 20 assinaturas dispara 41 queries. Só vale porque são
  `...ToOne` — com coleção, join fetch + paginação pagina em memória.

## Camadas

`controller → service → repository`, com `mapper` (MapStruct) e `dto`.
O service não importa nada de `org.springframework.web`; a tradução de exceção
para HTTP acontece só no `GlobalExceptionHandler`, em RFC 7807.

## Estado

**As 10 fases estão implementadas e `mvn verify` está verde: 175 testes**
(104 unitários + 71 de integração), cobertura **88,9%** com o gate de 80% ativo.

`mvn test` roda só os unitários; `mvn verify` inclui os `*IT`, que sobem
Postgres via Testcontainers. Roteiro e decisões no `docs/ARQUITETURA.md`;
visão geral em `docs/obsidian/Billing Platform.md`.

Repositório iniciado localmente em 23/08/2026, **ainda sem primeiro commit e
sem remoto** — publicar em `github.com/CaioCodes1/`.
