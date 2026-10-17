package br.com.oficina.execution.common.infrastructure.mensageria;

/**
 * Entrega fisica de uma mensagem ja confirmada no outbox: SQS FIFO ({@code TransporteSqs}) ou, com o
 * SQS desligado, nenhuma ({@code TransporteIndisponivel}). O publicador do outbox nao sabe qual esta ativo.
 *
 * <p>Lancar significa "nao entregue": o outbox registra a falha e tenta de novo no proximo ciclo.
 */
public interface TransporteMensagens {

    void enviar(MensagemSaida mensagem);

    /**
     * @param fila      nome logico (ver {@code Filas})
     * @param groupId   ordemServicoId - ordem garantida dentro do grupo
     * @param corpo     envelope serializado
     */
    record MensagemSaida(String fila, String groupId, String messageId, String tipo, String corpo) {
    }
}
