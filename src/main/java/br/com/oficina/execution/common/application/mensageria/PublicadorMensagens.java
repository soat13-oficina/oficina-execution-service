package br.com.oficina.execution.common.application.mensageria;

/**
 * Porta de saida para os eventos da Saga.
 *
 * <p>Publicar NAO envia nada na hora: grava a mensagem no outbox, dentro da mesma transacao do
 * DynamoDB que muda o estado, que precisa existir. A mensagem so sai se a transacao confirmar - e
 * sai mesmo que o pod caia logo depois, porque o publicador do outbox a encontra pendente no
 * proximo ciclo (ADR 0005 e 0006).
 */
public interface PublicadorMensagens {

    /**
     * @param fila nome logico da fila de destino (ver {@link Filas})
     */
    void publicar(String fila, Envelope envelope);
}
