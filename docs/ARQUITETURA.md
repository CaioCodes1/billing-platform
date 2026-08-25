# Arquitetura — Billing Platform

Registro das decisões de projeto e do *porquê* de cada uma. Quem for mexer em
regra de negócio deveria ler as seções 2 e 4 antes.

---

## 1. Escopo

API REST de assinaturas recorrentes: uma empresa vende planos, clientes assinam,
o sistema emite cobranças mensais, registra pagamentos, cobra os inadimplentes e
reporta o financeiro.

**Single-tenant, decidido explicitamente.** O enunciado original dizia "empresas
controlam seus clientes", no plural, mas não previa nenhuma entidade de empresa.
Multi-tenancy não é um campo a mais: obriga `tenant_id` em toda tabela, em todo
índice, em toda query, e um filtro que nunca pode ser esquecido — esquecer uma
vez é vazar dados de um cliente para outro. Ou o sistema nasce assim, ou não é.
Este nasce single-tenant: **uma instalação atende uma empresa**.

---

## 2. Onde este projeto diverge do enunciado original

O enunciado tinha três regras que, implementadas ao pé da letra, produzem um
sistema de cobrança que não fecha. Estão corrigidas aqui, e o motivo fica
registrado para não serem "consertadas de volta".

### 2.1 A cobrança nasce do ciclo, não do pagamento

> Enunciado: *"Nova cobrança gerada após pagamento."*

Se a próxima fatura nasce do pagamento da anterior, **quem não paga nunca recebe
a segunda fatura**. Em cascata: o inadimplente fica devendo eternamente um mês
em vez de três; "total pendente" no dashboard passa a ser estruturalmente
errado; e a régua de cobrança perde o insumo.

Aqui a emissão é dirigida pelo **ciclo**: o job diário emite a fatura de quem
tem renovação chegando, pago ou não. O pagamento apenas liquida a fatura.

Para isso não virar bola de neve, duas regras acompanham:

- assinatura `SUSPENDED` **não gera** nova cobrança — a dívida para de crescer
  quando o serviço para;
- quitadas as pendências, a assinatura volta a `ACTIVE` e o ciclo retoma.

### 2.2 O preço mora na assinatura, não no plano

O enunciado tem `valorMensal` no plano e nada na assinatura. Se a emissão lê
`plans.monthly_price`, o dia em que a empresa reajustar o plano Básico **todos
os contratos vivos são reprecificados**, inclusive relatórios do passado que
recalculem valor.

`subscriptions.unit_price` é copiado do plano no ato da contratação. Reajuste
passa a valer só para contratos novos, que é o comportamento correto — e migrar
um contrato para o preço novo vira uma ação explícita e auditável.

### 2.3 A vaga de assinatura só é liberada por cancelamento

O enunciado diz "um cliente só pode possuir uma assinatura ativa". Duas
correções:

**A regra mora no banco.** Como `if` no service, duas requisições simultâneas
passam as duas pela verificação antes de qualquer uma gravar. O índice único
parcial é o único ponto do sistema onde essa corrida é decidida:

```sql
CREATE UNIQUE INDEX uq_subscriptions_active_slot ON subscriptions (customer_id)
    WHERE status IN ('PENDING', 'ACTIVE', 'SUSPENDED');
```

**`SUSPENDED` continua ocupando a vaga.** Se suspensão liberasse, o inadimplente
abriria assinatura nova e deixaria a dívida para trás. Só `CANCELLED` libera.

### 2.4 Outros ajustes

| Ajuste | Motivo |
|---|---|
| `customers.document` (CPF/CNPJ) acrescentado | Não existe fatura sem documento do pagador |
| Status `PARTIALLY_PAID` e `REFUNDED` em `invoices` | O enunciado tinha `valorPago` mas nenhum estado para pagamento parcial; e PIX tem devolução, cartão tem chargeback |
| `payments` virou livro-razão *append-only* | Ver seção 3.4 |
| `billing_day` separado de `current_period_start` | Ver seção 5.2 |
| Cobertura de 80% medida só sobre código com lógica | Ver seção 8 |

---

## 3. Modelo de dados

```
customers ──< subscriptions >── plans
                   │
                   └──< invoices ──< payments

users >── user_roles ──< roles
users ──< refresh_tokens

outbox_messages        (fila de eventos)
shedlock               (eleição do job)
```

### 3.1 Convenção de enums

`VARCHAR` + `CHECK`, não o tipo `ENUM` nativo do Postgres. Com
`@Enumerated(EnumType.STRING)` o JPA lê e escreve `VARCHAR` sem adaptador
nenhum, e acrescentar um valor é um `ALTER` de `CHECK` dentro da transação da
migration. Com `ENUM` nativo seria preciso `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`
em toda entidade e `ALTER TYPE ... ADD VALUE`, que tem regras próprias. A
garantia de integridade é a mesma.

### 3.2 Chaves

`UUID` gerado pelo banco (`gen_random_uuid()`). Custa localidade de índice
comparado a `BIGSERIAL`, mas evita expor contagem de clientes na URL e permite
gerar o id antes do insert. Para o volume alvo, a troca compensa.

### 3.3 A trava de idempotência da emissão

```sql
CREATE UNIQUE INDEX uq_invoices_competencia ON invoices (subscription_id, period_start);
```

Job reiniciado no meio, retry, deploy durante a execução, duas réplicas subindo
juntas: a segunda tentativa de emitir a mesma competência falha no `INSERT`.
Cobrar o cliente duas vezes deixa de depender de o código estar certo.

### 3.4 `payments` como livro-razão

Não existe "corrigir um pagamento": lança-se o contrário (`type = 'REFUND'`).
Um trigger recusa `UPDATE` e `DELETE` na tabela. O status da fatura é
**derivado** da soma dos `PAYMENT` menos os `REFUND`.

O que sai de graça desse desenho:

- pagamento parcial → soma < `amount` → `PARTIALLY_PAID`
- pagamento a maior → soma > `amount` → crédito visível, não perdido
- estorno PIX e chargeback de cartão → um lançamento, não um `UPDATE`
- auditoria financeira completa sem tabela de auditoria separada

Duas chaves de deduplicação: `provider_ref` (id da transação no PSP — webhook
reentregue não vira pagamento duplicado) e `idempotency_key` (enviada pelo
cliente da API — clique duplo no front não vira pagamento duplicado).

---

## 4. O ciclo de cobrança

Um job diário, `America/Sao_Paulo`, com lock distribuído (ShedLock) e
processamento em páginas. Prazos em `application.yml`, porque são decisão
comercial e não podem exigir recompilação.

| Passo | O que faz |
|---|---|
| 1. Emitir | `ACTIVE` com `next_renewal_date <= hoje + 5`: cria a fatura da competência, avança o período, recalcula a próxima renovação |
| 2. Vencer | `PENDING`/`PARTIALLY_PAID` com `due_date < hoje` → `OVERDUE` |
| 3. Suspender | Fatura `OVERDUE` há ≥ 15 dias → assinatura `SUSPENDED` + evento no outbox |
| 4. Encerrar | `SUSPENDED` há ≥ 30 dias → `CANCELLED` |

O passo 4 não estava no enunciado. Sem ele o ciclo não tem fim: assinaturas
suspensas acumulariam para sempre, sem gerar receita e sem sair do relatório.

### Por que ShedLock

`@Scheduled` dispara em **toda** instância da aplicação. Com duas réplicas, o
faturamento roda duas vezes no mesmo minuto. O índice único de `invoices` já
impede a cobrança duplicada, mas o trabalho é feito em dobro e o log fica
ilegível. O ShedLock elege uma instância por execução.

### Por que em páginas

`findAll()` de cobranças vencidas com milhares de clientes carrega tudo em
memória. O job pagina e abre uma transação curta por página — falha na página
40 não desfaz as 39 anteriores.

---

## 5. Dinheiro e datas

### 5.1 Dinheiro

`NUMERIC(19,4)` no banco, `BigDecimal` no Java. Nunca `double` ou `float`:
`0.1 + 0.2` em ponto flutuante binário não dá `0.3`, e num sistema de cobrança
isso vira diferença de centavo em fechamento contábil.

Quatro casas decimais, não duas, porque cálculo proporcional (mudança de plano
no meio do mês) precisa de casas intermediárias antes do arredondamento final.

### 5.2 O problema do dia 31

Cliente assina em 31/01. `plusMonths(1)` devolve 28/02 — o Java já faz o clamp
corretamente. O problema é o ciclo seguinte: partindo de 28/02, ele vira 28/03,
e o cliente **virou cliente do dia 28 para sempre**.

Por isso `subscriptions.billing_day` guarda o dia **original** (1–31), separado
de `current_period_start`. Todo cálculo de ciclo parte do `billing_day` e faz o
clamp contra o mês corrente, então março volta a ser 31.

### 5.3 Fuso

`due_date` e `period_*` são `DATE` — não têm hora e não sofrem conversão de
fuso. `paid_at` e os demais carimbos são `TIMESTAMPTZ`, gravados em UTC.

O job roda em `America/Sao_Paulo`, fixado em configuração e não herdado do
relógio do servidor: "vencido hoje" precisa significar a mesma coisa
independentemente de onde o container está rodando.

---

## 6. Segurança

**Access token JWT curto (15 min) + refresh token opaco e revogável.**

O access token é stateless — não dá para invalidar antes de expirar, e por isso
é curto. O refresh é guardado no banco como **hash SHA-256**: vazamento do dump
não vira sessão válida, mesma lógica da senha.

**Rotação com detecção de reuso.** Todo refresh emitido a partir de um login
carrega o mesmo `family_id`. Se um token já rotacionado for apresentado de novo,
é sinal de que alguém copiou o token — revoga-se a família inteira, derrubando
atacante e usuário legítimo. Na dúvida, exigir login novo.

Assinatura via `JwtEncoder`/`JwtDecoder` do Spring Security (Nimbus), sem trazer
biblioteca de terceiro só para isso.

### Papéis

| | ADMIN | FINANCIAL | SUPPORT |
|---|:---:|:---:|:---:|
| Planos (escrita) | ✅ | ❌ | ❌ |
| Usuários | ✅ | ❌ | ❌ |
| Clientes (escrita) | ✅ | ❌ | ✅ |
| Assinaturas | ✅ | ✅ | leitura |
| Pagamentos e estornos | ✅ | ✅ | ❌ |
| Dashboard | ✅ | ✅ | ❌ |

`@PreAuthorize` no **método do service**, não só no controller: a regra continua
valendo se o endpoint mudar ou se o método for chamado pelo job.

Nenhum usuário é semeado por migration — credencial em migration é credencial
versionada no git e igual em todo ambiente. O admin inicial é criado no boot a
partir de `BOOTSTRAP_ADMIN_PASSWORD`, e só se a tabela estiver vazia.

---

## 7. Camadas

```
Controller  → HTTP, DTO, códigos de status. Não conhece regra.
Service     → regra de negócio, transação. Não conhece HTTP.
Repository  → acesso a dados. Não conhece regra.
Mapper      → Entity ↔ DTO (MapStruct, gerado em compilação).
```

Decisões que sustentam a separação:

- **Exceções de domínio carregam o status HTTP**, mas quem traduz é o
  `GlobalExceptionHandler` — nenhum service importa `org.springframework.web`.
- **Nenhum controller tem `try/catch`.**
- **Resposta de erro em RFC 7807** (`ProblemDetail`, nativo do Spring 6), não um
  formato inventado: cliente HTTP, gateway e ferramenta de observabilidade já
  sabem ler `application/problem+json`.
- **`open-in-view: false`.** Ligado (padrão do Boot), a sessão do Hibernate fica
  aberta até a resposta ser serializada, o que esconde N+1 atrás da renderização
  do JSON e segura conexão do pool.
- **Envelope de paginação próprio** (`PageResponse`), não o `Page` do Spring
  Data serializado direto — o formato do `Page` já mudou entre versões e não
  deveria ser o contrato público da API.
- **Entidade JPA nunca cruza a fronteira do controller.**

### O outbox

`@Transactional` que manda e-mail tem dois defeitos: o SMTP lento segura a
conexão do banco, e um rollback depois do envio deixa o cliente com um aviso de
cobrança que não existe — e não dá para "des-enviar".

O service grava o evento em `outbox_messages` **na mesma transação** do dado: ou
os dois vão para o banco, ou nenhum vai. Um worker separado consome os
`PENDING` com `FOR UPDATE SKIP LOCKED` (que permite várias réplicas consumindo
a fila sem colidir) e despacha, com retry e backoff.

---

## 8. Testes

**Testcontainers com Postgres real, não H2.** Metade das garantias deste sistema
é do banco: índice único parcial, `CHECK`, trigger de append-only,
`SKIP LOCKED`. O H2 não implementa índice único parcial — um teste que passasse
nele estaria justamente deixando de verificar "um cliente, uma assinatura ativa".

`SchemaGarantiasTest` existe por isso: testa as constraints diretamente. Um
refactor que apague um índice quebra ali, não em produção.

**A meta de 80% é medida sobre código com lógica.** DTO, mapper gerado, entity e
config estão excluídos do JaCoCo. Contá-los infla o número sem nenhum teste a
mais, e a métrica vira teatro. O gate está ativo desde a fase 2 — hoje em **88,9%**.

### Unitário e integração são separados

| Comando | Roda | Precisa de Docker |
|---|---|---|
| `mvn test` | surefire → `*Test` | não |
| `mvn verify` | + failsafe → `*IT` | sim |

Testes de negócio puro (`DocumentValidatorTest`, `CustomerServiceTest`) rodam em
milissegundos e sem dependência externa, então servem de feedback durante a
escrita do código. Os que sobem Postgres (`*IT`) ficam para o `verify`.

> Armadilha que custou um diagnóstico: a receita comum de configurar o failsafe
> com `<argLine>${failsafeArgLine}</argLine>` aponta para uma propriedade que o
> JaCoCo não define — ele define `argLine`, que o failsafe já lê sozinho. Com a
> referência errada, o agente não anexa e a cobertura dos testes de integração
> aparece como **zero, sem nenhum erro no build**.

---

## 9. Fases

| # | Entrega | Estado |
|---|---|---|
| 1 | Esqueleto, Flyway, Docker Compose, erro global, Testcontainers | ✅ |
| 2 | `Customer` — CRUD, paginação, filtro, ordenação | ✅ |
| 3 | `Plan` — CRUD, reajuste, catálogo | ✅ |
| 4 | `Subscription` — máquina de estados, vaga única, ciclo | ✅ |
| 5 | `Invoice` — emissão idempotente por competência | ✅ |
| 6 | `Payment` — livro-razão e derivação do status | ✅ |
| 7 | Segurança — JWT, refresh com rotação, papéis | ✅ |
| 8 | Scheduler — ShedLock e os 5 passos | ✅ |
| 9 | Dashboard financeiro | ✅ |
| 10 | Outbox worker + Spring Mail | ✅ |

As dez fases estão implementadas. As fases 1–4 foram o grosso do esforço; as
demais se apoiaram nelas.
