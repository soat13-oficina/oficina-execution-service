package br.com.oficina.execution.support.persistence;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.repository.ExecucaoRepository;

/**
 * Duplo das execucoes: mesmas regras de versao e de criacao unica do adapter do DynamoDB (que o
 * LocalStack confere de verdade), a fila em ordem de entrada na etapa, sem rede.
 */
public class ExecucaoRepositoryEmMemoria implements ExecucaoRepository {

    private final Map<UUID, Execucao> execucoes = new LinkedHashMap<>();

    @Override
    public Optional<Execucao> buscarPorOrdemDeServico(UUID ordemServicoId) {
        return Optional.ofNullable(execucoes.get(ordemServicoId));
    }

    @Override
    public void criar(Execucao execucao) {
        if (execucoes.containsKey(execucao.ordemServicoId())) {
            throw new ConflitoDeRecursoException("Ja existe execucao para a OS " + execucao.ordemServicoId());
        }
        execucoes.put(execucao.ordemServicoId(), comVersao(execucao, 1));
    }

    @Override
    public void atualizar(Execucao execucao) {
        Execucao gravada = execucoes.get(execucao.ordemServicoId());
        if (gravada == null || gravada.versao() != execucao.versao()) {
            throw new ConflitoDeRecursoException("Execucao alterada por outra requisicao: " + execucao.ordemServicoId());
        }
        execucoes.put(execucao.ordemServicoId(), comVersao(execucao, execucao.versao() + 1));
    }

    @Override
    public List<Execucao> listarPorEtapa(Etapa etapa) {
        return execucoes.values().stream()
                .filter(e -> e.etapa() == etapa)
                .sorted(Comparator.comparing(Execucao::entrouNaEtapaEm))
                .toList();
    }

    public void limpar() {
        execucoes.clear();
    }

    private static Execucao comVersao(Execucao e, long versao) {
        return new Execucao(e.ordemServicoId(), e.sagaId(), e.numeroOrdemServico(), e.etapa(), e.veiculo(), e.servicosPrevistos(),
                e.pecasPrevistas(), e.diagnostico(), e.mecanicoDiagnostico(), e.mecanicoExecucao(), e.historico(),
                e.entrouNaEtapaEm(), e.criadaEm(), e.atualizadaEm(), versao);
    }
}
