package com.caiocodes.billing.invoice.repository;

import java.util.UUID;

/**
 * Projeção do passo "suspender": quais assinaturas têm cobrança vencida há mais
 * do que o prazo, e há quantos dias.
 *
 * <p>Projeção em vez de entidade porque o job só precisa do id para agir — trazer
 * as cobranças inteiras (com assinatura, cliente e plano) seria carregar meio
 * banco para ler uma coluna.
 */
public interface OverdueProjection {

    UUID getSubscriptionId();

    long getDiasEmAtraso();
}
