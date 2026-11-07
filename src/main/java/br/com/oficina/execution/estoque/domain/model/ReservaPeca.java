package br.com.oficina.execution.estoque.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Quanto de uma peca esta reservado para uma OS. E o que permite liberar "a reserva desta OS" e
 * responder de novo a um {@code ReservarPecas} reenviado sem reservar duas vezes.
 */
public record ReservaPeca(
        UUID ordemDeServicoId,
        String pecaInsumoId,
        int quantidade,
        Status status,
        Instant criadaEm,
        Instant atualizadaEm) {

    public enum Status {
        RESERVADA,
        CONSUMIDA,
        LIBERADA
    }

    public static ReservaPeca nova(UUID ordemDeServicoId, String pecaInsumoId, int quantidade, Instant agora) {
        return new ReservaPeca(ordemDeServicoId, pecaInsumoId, quantidade, Status.RESERVADA, agora, agora);
    }

    public boolean ativa() {
        return status == Status.RESERVADA;
    }
}
