package br.com.oficina.execution.execucao.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;

/**
 * Execucoes por OS. {@link #criar} e {@link #atualizar} nao gravam na hora: entram na transacao em
 * curso ({@code Transacao}) e confirmam junto com os eventos no outbox e as demais escritas. Chamadas
 * fora dela falham.
 */
public interface ExecucaoRepository {

    /** Leitura com consistencia forte. */
    Optional<Execucao> buscarPorOrdemDeServico(UUID ordemServicoId);

    /** A execucao nao pode existir ainda: a transacao e recusada se existir. */
    void criar(Execucao execucao);

    /**
     * Grava a execucao seguinte a uma lida antes. A transacao e recusada se alguem a alterou depois da
     * leitura ({@code execucao.versao()} e a versao lida).
     */
    void atualizar(Execucao execucao);

    /** A fila de uma etapa em ordem de chegada (indice eventualmente consistente). */
    List<Execucao> listarPorEtapa(Etapa etapa);
}
