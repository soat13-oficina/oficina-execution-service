package br.com.oficina.execution.common.application;

import java.util.function.Supplier;

/**
 * Fronteira transacional dos casos de uso (o {@code @Transactional} desta aplicacao, que usa o
 * DynamoDB): tudo que o bloco escreve - estado, eventos no outbox, reservas - confirma junto, ou nada
 * confirma. Escrita concorrente na mesma execucao ou peca vira {@code ConflitoDeRecursoException}.
 */
public interface Transacao {

    <T> T executar(Supplier<T> acao);

    default void executar(Runnable acao) {
        executar(() -> {
            acao.run();
            return null;
        });
    }
}
