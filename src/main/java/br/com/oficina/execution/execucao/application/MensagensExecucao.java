package br.com.oficina.execution.execucao.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tipos e payloads da Saga do lado da execucao, como em {@code docs/contratos/mensagens.md}. Copia
 * propria do servico: o que o une ao OS Service e o contrato (e o teste de contrato contra os JSON
 * Schemas). Dinheiro vai como texto com duas casas.
 */
public final class MensagensExecucao {

    // Comandos recebidos do orquestrador
    public static final String ENFILEIRAR_DIAGNOSTICO = "EnfileirarDiagnostico";
    public static final String REABRIR_DIAGNOSTICO = "ReabrirDiagnostico";
    public static final String ENFILEIRAR_EXECUCAO = "EnfileirarExecucao";
    public static final String CANCELAR_EXECUCAO = "CancelarExecucao";

    // Eventos publicados
    public static final String DIAGNOSTICO_ENFILEIRADO = "DiagnosticoEnfileirado";
    public static final String DIAGNOSTICO_INICIADO = "DiagnosticoIniciado";
    public static final String DIAGNOSTICO_CONCLUIDO = "DiagnosticoConcluido";
    public static final String DIAGNOSTICO_REABERTO = "DiagnosticoReaberto";
    public static final String EXECUCAO_ENFILEIRADA = "ExecucaoEnfileirada";
    public static final String EXECUCAO_INICIADA = "ExecucaoIniciada";
    public static final String EXECUCAO_CONCLUIDA = "ExecucaoConcluida";
    public static final String EXECUCAO_FALHOU = "ExecucaoFalhou";
    public static final String EXECUCAO_CANCELADA = "ExecucaoCancelada";

    private MensagensExecucao() {
    }

    public record Veiculo(String placa, String marca, String modelo, Integer ano) {
    }

    public record Servico(String descricao, String valorMaoDeObra) {
    }

    public record ItemPeca(String pecaInsumoId, int quantidade) {
    }

    public record Mecanico(String id, String nome) {
    }

    // Comandos
    public record EnfileirarDiagnostico(
            String numeroOrdemServico, Veiculo veiculo, List<Servico> servicosPrevistos, List<ItemPeca> pecasPrevistas, Instant abertaEm) {
    }

    public record ReabrirDiagnostico(String motivo, List<String> pecasInvalidas) {
    }

    public record EnfileirarExecucao(UUID orcamentoId) {
    }

    public record CancelarExecucao(String motivo) {
    }

    // Eventos
    public record DiagnosticoEnfileirado(int posicaoNaFila) {
    }

    public record DiagnosticoIniciado(Mecanico mecanico, Instant iniciadoEm) {
    }

    public record DiagnosticoConcluido(
            Mecanico mecanico, String descricaoDiagnostico, List<Servico> servicos, List<ItemPeca> pecas, Instant concluidoEm) {
    }

    public record DiagnosticoReaberto(Instant reabertoEm) {
    }

    public record ExecucaoEnfileirada(int posicaoNaFila) {
    }

    public record ExecucaoIniciada(Mecanico mecanico, Instant iniciadaEm) {
    }

    public record ExecucaoConcluida(Mecanico mecanico, List<ItemPeca> pecasConsumidas, Instant concluidaEm) {
    }

    public record ExecucaoFalhou(Mecanico mecanico, String motivo, List<ItemPeca> pecasConsumidas, Instant abortadaEm) {
    }

    public record ExecucaoCancelada(Instant canceladaEm) {
    }
}
