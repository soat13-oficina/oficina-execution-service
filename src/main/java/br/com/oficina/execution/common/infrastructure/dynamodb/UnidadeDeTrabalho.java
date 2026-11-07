package br.com.oficina.execution.common.infrastructure.dynamodb;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * O {@code @Transactional} desta aplicacao: o DynamoDB nao tem transacao de sessao, so
 * {@code TransactWriteItems} de ate 100 itens. Quem escreve dentro de {@link #executar} nao grava na
 * hora - acrescenta seus itens ao lote da thread, e o lote inteiro confirma no fim, de uma vez:
 * estado, resposta no outbox e o registro de mensagem processada (ADR 0005 e 0006).
 *
 * <p>Leituras dentro do bloco veem o estado de antes do lote; os servicos leem, decidem e so
 * entao escrevem. Fora de um bloco, {@link #adicionar} falha alto: seria escrita sem a garantia.
 */
@Component
public class UnidadeDeTrabalho {

    /** Limite do DynamoDB por transacao. */
    static final int LIMITE_ITENS = 100;
    private static final String CONDICAO_RECUSADA = "ConditionalCheckFailed";

    private final DynamoDbClient dynamo;
    private final ThreadLocal<Lote> atual = new ThreadLocal<>();

    public UnidadeDeTrabalho(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    /**
     * Roda a acao e confirma o lote. Chamado dentro de outro bloco, junta-se a ele (quem abriu
     * confirma). Excecao na acao descarta o lote sem escrever nada.
     *
     * @throws EscritaRecusadaException se o DynamoDB cancelar a transacao
     */
    public <T> T executar(Supplier<T> acao) {
        if (atual.get() != null) {
            return acao.get();
        }
        Lote lote = new Lote();
        atual.set(lote);
        try {
            T resultado = acao.get();
            confirmar(lote);
            return resultado;
        } finally {
            atual.remove();
        }
    }

    public void executar(Runnable acao) {
        executar(() -> {
            acao.run();
            return null;
        });
    }

    public boolean ativa() {
        return atual.get() != null;
    }

    /** @param rotulo identifica o item no erro, se a condicao dele falhar (ex.: {@code idempotencia}) */
    public void adicionar(String rotulo, TransactWriteItem item) {
        Lote lote = atual.get();
        if (lote == null) {
            throw new IllegalStateException("Escrita fora de uma unidade de trabalho: " + rotulo);
        }
        lote.rotulos.add(rotulo);
        lote.itens.add(item);
    }

    private void confirmar(Lote lote) {
        if (lote.itens.isEmpty()) {
            return;
        }
        if (lote.itens.size() > LIMITE_ITENS) {
            throw new IllegalStateException("Transacao com " + lote.itens.size() + " itens; o DynamoDB aceita no maximo " + LIMITE_ITENS);
        }
        try {
            dynamo.transactWriteItems(r -> r.transactItems(lote.itens));
        } catch (TransactionCanceledException e) {
            List<String> recusados = new ArrayList<>();
            List<CancellationReason> motivos = e.cancellationReasons();
            for (int i = 0; i < motivos.size() && i < lote.rotulos.size(); i++) {
                if (CONDICAO_RECUSADA.equals(motivos.get(i).code())) {
                    recusados.add(lote.rotulos.get(i));
                }
            }
            throw new EscritaRecusadaException(recusados, e);
        }
    }

    private static final class Lote {
        final List<String> rotulos = new ArrayList<>();
        final List<TransactWriteItem> itens = new ArrayList<>();
    }
}
