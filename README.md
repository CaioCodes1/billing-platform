# Billing Platform

API REST de gestão de assinaturas recorrentes e cobrança: planos, contratação,
emissão mensal automática, pagamentos, régua de inadimplência e relatórios
financeiros.

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · MapStruct ·
Spring Security (JWT) · ShedLock · Testcontainers · Docker Compose

> Projeto single-tenant por decisão de arquitetura — uma instalação atende uma
> empresa. O porquê disso e de todas as demais decisões está em
> [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md).

---

## Como rodar

Pré-requisitos: JDK 21, Maven 3.9+, Docker.

```bash
cp .env.example .env
```

Sobe o Postgres (porta **5433** no host, para não colidir com os outros
projetos do workspace) e o Mailpit:

```bash
docker compose up -d
```

Sobe a aplicação:

```bash
mvn spring-boot:run
```

| Endereço | O que é |
|---|---|
| http://localhost:8080/docs | Swagger UI |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8025 | Mailpit — os e-mails enviados aparecem aqui |

## Testes

Os testes de integração sobem um Postgres real via Testcontainers, então o
Docker precisa estar rodando:

```bash
mvn verify
```

> **Windows + Docker Engine 29:** se der `Could not find a valid Docker
> environment` mesmo com o Docker funcionando, é a incompatibilidade de versão
> de API do docker-java. A correção está no [`CLAUDE.md`](CLAUDE.md#️-testcontainers--docker-engine-29-obrigatório-senão-nenhum-teste-roda).

Relatório de cobertura em `target/site/jacoco/index.html`.

> [!nota] Migrations foram editadas durante o desenvolvimento
> As migrations `V1` a `V4` tiveram `CHAR(n)` trocado por `VARCHAR(n)` depois de
> já terem rodado em containers descartáveis. Se você tiver um banco de
> desenvolvimento antigo, o Flyway vai reclamar de checksum:
>
> ```bash
> docker compose down -v && docker compose up -d
> ```
>
> Daqui para frente, correção de schema é migration nova.

---

## Estado

**As 10 fases estão implementadas**: fundação, schema completo em migrations,
tratamento global de erro em RFC 7807, clientes, planos, assinaturas com máquina
de estados, emissão idempotente de cobranças, livro-razão de pagamentos,
segurança com JWT e papéis, job diário com ShedLock, dashboard financeiro e
outbox de e-mails.

**187 testes verdes**: 104 unitários (~15 s, sem Docker) e 83 de integração
(Postgres real via Testcontainers). Cobertura de 89,2%.

> A API exige autenticação. Crie o admin inicial via `BOOTSTRAP_ADMIN_PASSWORD`
> e obtenha o token em `POST /api/v1/auth/login`.

O roteiro das fases está no fim de [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md).

## Segurança

`SegurancaHardeningIT` são 12 testes que **atacam** a API em vez de exercitar o
caminho feliz: token forjado com outra chave, `alg: none`, token expirado,
payload trocado mantendo a assinatura, escalada de papel, vazamento de hash na
resposta e enumeração de usuários pelo login.

Análise estática com Semgrep, sem instalar Python:

```bash
docker run --rm --mount "type=bind,source=E:\projetos\billing-platform,target=/src,readonly" semgrep/semgrep semgrep --metrics=off --config p/java --config p/security-audit --config p/secrets --config p/owasp-top-ten --config p/jwt --config p/dockerfile /src
```

Último scan (25/08/2026): **153 regras, 105 arquivos, 0 achados**.

> `--config auto` não serve aqui: ele exige telemetria ligada, porque manda
> dados do projeto ao semgrep.dev para escolher as regras. Os packs acima
> cobrem o mesmo terreno sem isso. O que a auditoria encontrou — e por que
> `httpBasic` foi removido e o health check parou de depender de SMTP — está na
> seção 6.1 de [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md).

## Modelo

```
customers ──< subscriptions >── plans
                   │
                   └──< invoices ──< payments

users >── user_roles ──< roles
users ──< refresh_tokens

outbox_messages    (fila de eventos de e-mail)
shedlock           (eleição do job diário)
```

Três garantias que moram no banco e não no código — cada uma com teste próprio
em `SchemaGarantiasIT`:

- **um cliente, uma assinatura** — índice único parcial; só o cancelamento
  libera a vaga
- **uma cobrança por competência** — o job de faturamento pode rodar duas vezes
  sem cobrar o cliente duas vezes
- **pagamento é imutável** — para desfazer, lança-se um `REFUND`

---

## Documentação

| Arquivo | O quê |
|---|---|
| [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md) | As decisões e o porquê de cada uma, com trade-offs |
| [`docs/obsidian/Billing Platform.md`](docs/obsidian/Billing%20Platform.md) | Visão geral em formato Obsidian: diagramas, callouts e as armadilhas encontradas |
| [`CLAUDE.md`](CLAUDE.md) | Índice do projeto e configuração de ambiente |
