package br.com.oficina.execution.estoque.application;

import java.util.List;
import java.util.UUID;

/**
 * Tipos e payloads das mensagens da Saga do lado do estoque, como em
 * {@code docs/contratos/mensagens.md}. Copia propria do servico: o que o une ao OS Service e o
 * contrato (e o teste de contrato contra os JSON Schemas), nao uma classe compartilhada.
 */
public final class MensagensEstoque {

    // Comandos recebidos do orquestrador
    public static final String RESERVAR_PECAS = "ReservarPecas";
    public static final String LIBERAR_RESERVA = "LiberarReserva";

    // Eventos publicados
    public static final String PECAS_RESERVADAS = "PecasReservadas";
    public static final String RESERVA_FALHOU = "ReservaFalhou";
    public static final String RESERVA_LIBERADA = "ReservaLiberada";

    private MensagensEstoque() {
    }

    public record ItemPeca(String pecaInsumoId, int quantidade) {
    }

    public record PecaFaltante(String pecaInsumoId, int solicitada, int disponivel) {
    }

    public record ReservarPecas(UUID orcamentoId, List<ItemPeca> itens) {
    }

    public record LiberarReserva(String motivo) {
    }

    public record PecasReservadas(UUID orcamentoId, List<ItemPeca> itens) {
    }

    public record ReservaFalhou(UUID orcamentoId, List<PecaFaltante> faltantes) {
    }

    public record ReservaLiberada(List<ItemPeca> itens) {
    }
}
