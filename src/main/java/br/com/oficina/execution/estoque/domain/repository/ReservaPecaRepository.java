package br.com.oficina.execution.estoque.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.oficina.execution.estoque.domain.model.ReservaPeca;

/**
 * Reservas das pecas por OS. {@link #reservar} e {@link #liberar} nao gravam na hora: entram na
 * unidade de trabalho do consumidor da mensagem e confirmam junto com a resposta no outbox e o
 * registro de idempotencia (ADR 0006). Chamadas fora dela falham.
 */
public interface ReservaPecaRepository {

    /** Todas as reservas da OS, em qualquer status. */
    List<ReservaPeca> buscarPorOrdemDeServico(UUID ordemDeServicoId);

    /**
     * Debita o disponivel de cada peca e registra a reserva da OS. A condicao
     * {@code disponivel >= quantidade} e checada na confirmacao: se outra escrita tirou o estoque
     * entre a leitura e a confirmacao, a transacao inteira e recusada.
     *
     * @param itens quantidade por id de peca
     */
    void reservar(UUID ordemDeServicoId, Map<String, Integer> itens, Instant agora);

    /** Devolve ao disponivel o que as reservas ativas seguravam e as marca como liberadas. */
    void liberar(List<ReservaPeca> ativas, Instant agora);

    /**
     * Baixa definitiva, na conclusao do servico: as pecas reservadas saem do estoque (o disponivel nao
     * muda, pois ja estava descontado) e as reservas passam a consumidas.
     */
    void consumir(List<ReservaPeca> ativas, Instant agora);
}
