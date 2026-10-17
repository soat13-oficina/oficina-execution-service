package br.com.oficina.execution.execucao.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;

/**
 * A execucao de uma OS: da fila de diagnostico ate a conclusao (ou aborto, ou cancelamento pela Saga).
 * Imutavel: cada transicao devolve a execucao seguinte e anota a etapa no historico. {@code versao} e o
 * lock otimista do DynamoDB (a que foi lida; o repositorio grava a seguinte).
 *
 * <p>{@code servicosPrevistos}/{@code pecasPrevistas} sao o ponto de partida do mecanico (previsao da
 * abertura ou o diagnostico reaberto); {@code diagnostico} so existe depois que ele conclui.
 */
public record Execucao(
        UUID ordemServicoId,
        UUID sagaId,
        String numeroOrdemServico,
        Etapa etapa,
        Veiculo veiculo,
        List<ServicoDiagnostico> servicosPrevistos,
        List<PecaDiagnostico> pecasPrevistas,
        Diagnostico diagnostico,
        Mecanico mecanicoDiagnostico,
        Mecanico mecanicoExecucao,
        List<TransicaoDeEtapa> historico,
        Instant entrouNaEtapaEm,
        Instant criadaEm,
        Instant atualizadaEm,
        long versao) {

    public Execucao {
        servicosPrevistos = servicosPrevistos == null ? List.of() : List.copyOf(servicosPrevistos);
        pecasPrevistas = pecasPrevistas == null ? List.of() : List.copyOf(pecasPrevistas);
        historico = List.copyOf(historico);
    }

    /** OS recem-aberta, na fila de diagnostico. */
    public static Execucao nova(
            UUID ordemServicoId,
            UUID sagaId,
            String numeroOrdemServico,
            Veiculo veiculo,
            List<ServicoDiagnostico> servicosPrevistos,
            List<PecaDiagnostico> pecasPrevistas,
            Instant agora) {
        return new Execucao(ordemServicoId, sagaId, numeroOrdemServico, Etapa.AGUARDANDO_DIAGNOSTICO, veiculo,
                servicosPrevistos, pecasPrevistas, null, null, null,
                List.of(new TransicaoDeEtapa(Etapa.AGUARDANDO_DIAGNOSTICO, agora)), agora, agora, agora, 0);
    }

    public Execucao iniciarDiagnostico(Mecanico mecanico, Instant agora) {
        exigir(Etapa.AGUARDANDO_DIAGNOSTICO, "iniciar o diagnostico");
        return para(Etapa.EM_DIAGNOSTICO, agora, diagnostico, mecanico, mecanicoExecucao, servicosPrevistos, pecasPrevistas);
    }

    public Execucao concluirDiagnostico(Diagnostico novo, Instant agora) {
        exigir(Etapa.EM_DIAGNOSTICO, "concluir o diagnostico");
        if (novo.servicos().isEmpty()) {
            throw new RegraDeNegocioException("O diagnostico precisa de ao menos um servico.");
        }
        return para(Etapa.DIAGNOSTICO_CONCLUIDO, agora, novo, mecanicoDiagnostico, mecanicoExecucao, servicosPrevistos, pecasPrevistas);
    }

    /**
     * Compensacao de {@code OrcamentoFalhou}: volta para a fila de diagnostico. O diagnostico anterior vira
     * ponto de partida (os servicos ficam; as pecas invalidas saem) e o mecanico conclui de novo.
     */
    public Execucao reabrirDiagnostico(Set<String> pecasInvalidas, Instant agora) {
        exigir(Etapa.DIAGNOSTICO_CONCLUIDO, "reabrir o diagnostico");
        List<PecaDiagnostico> pecasMantidas = new ArrayList<>(diagnostico.pecas());
        pecasMantidas.removeIf(p -> pecasInvalidas.contains(p.pecaInsumoId()));
        return para(Etapa.AGUARDANDO_DIAGNOSTICO, agora, null, null, mecanicoExecucao, diagnostico.servicos(), pecasMantidas);
    }

    public Execucao enfileirarExecucao(Instant agora) {
        exigir(Etapa.DIAGNOSTICO_CONCLUIDO, "entrar na fila de execucao");
        return para(Etapa.AGUARDANDO_EXECUCAO, agora, diagnostico, mecanicoDiagnostico, mecanicoExecucao, servicosPrevistos, pecasPrevistas);
    }

    public Execucao iniciarExecucao(Mecanico mecanico, Instant agora) {
        exigir(Etapa.AGUARDANDO_EXECUCAO, "iniciar a execucao");
        return para(Etapa.EM_EXECUCAO, agora, diagnostico, mecanicoDiagnostico, mecanico, servicosPrevistos, pecasPrevistas);
    }

    public Execucao concluirExecucao(Instant agora) {
        exigir(Etapa.EM_EXECUCAO, "concluir a execucao");
        return para(Etapa.CONCLUIDA, agora, diagnostico, mecanicoDiagnostico, mecanicoExecucao, servicosPrevistos, pecasPrevistas);
    }

    public Execucao abortarExecucao(Instant agora) {
        exigir(Etapa.EM_EXECUCAO, "abortar a execucao");
        return para(Etapa.ABORTADA, agora, diagnostico, mecanicoDiagnostico, mecanicoExecucao, servicosPrevistos, pecasPrevistas);
    }

    /** Compensacao da Saga ({@code CancelarExecucao}). Quem ja terminou nao muda: o chamador confere {@link #etapa()}. */
    public Execucao cancelar(Instant agora) {
        if (etapa.ehFinal()) {
            throw new RegraDeNegocioException("A execucao ja terminou (" + etapa + ") e nao pode ser cancelada.");
        }
        return para(Etapa.CANCELADA, agora, diagnostico, mecanicoDiagnostico, mecanicoExecucao, servicosPrevistos, pecasPrevistas);
    }

    private void exigir(Etapa esperada, String acao) {
        if (etapa != esperada) {
            throw new RegraDeNegocioException("Nao e possivel " + acao + " com a execucao em " + etapa + " (esperado: " + esperada + ").");
        }
    }

    private Execucao para(
            Etapa nova,
            Instant agora,
            Diagnostico novoDiagnostico,
            Mecanico novoMecanicoDiagnostico,
            Mecanico novoMecanicoExecucao,
            List<ServicoDiagnostico> novosServicosPrevistos,
            List<PecaDiagnostico> novasPecasPrevistas) {
        List<TransicaoDeEtapa> novoHistorico = new ArrayList<>(historico);
        novoHistorico.add(new TransicaoDeEtapa(nova, agora));
        return new Execucao(ordemServicoId, sagaId, numeroOrdemServico, nova, veiculo, novosServicosPrevistos, novasPecasPrevistas,
                novoDiagnostico, novoMecanicoDiagnostico, novoMecanicoExecucao, novoHistorico, agora, criadaEm, agora, versao);
    }
}
