package br.com.oficina.execution.estoque.domain.repository;

import java.util.List;
import java.util.Optional;

import br.com.oficina.execution.estoque.domain.model.PecaInsumo;

public interface PecaInsumoRepository {

    /** Grava a peca nova. */
    void criar(PecaInsumo peca);

    /**
     * Substitui a peca lida antes, com lock otimista pela {@code versao}.
     *
     * @throws br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException se outra escrita mexeu na peca depois da leitura
     * @throws br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException se a peca nao existe mais
     */
    void substituir(PecaInsumo peca);

    /** Leitura com consistencia forte: as decisoes de estoque nao podem se basear em dado antigo. */
    Optional<PecaInsumo> buscarPorId(String id);

    List<PecaInsumo> buscarTodos();

    /**
     * @throws br.com.oficina.execution.common.domain.exception.RegraDeNegocioException se ha reserva ativa
     */
    void excluirPorId(String id);

    /** Entrada de estoque, atomica: acrescenta ao estoque e ao disponivel. */
    void adicionarEstoque(String id, int quantidade);

    /**
     * Saida manual, atomica: so do que esta disponivel (o reservado pertence a uma OS).
     *
     * @throws br.com.oficina.execution.common.domain.exception.RegraDeNegocioException se nao ha disponivel suficiente
     */
    void removerEstoque(String id, int quantidade);
}
