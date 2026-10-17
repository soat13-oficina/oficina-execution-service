package br.com.oficina.execution.support.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException;
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.estoque.domain.repository.PecaInsumoRepository;

/**
 * Duplo do cadastro de pecas: mesmas regras de versao, existencia e disponivel que o adapter do
 * DynamoDB (que o LocalStack confere de verdade), sem rede.
 */
public class PecaInsumoRepositoryEmMemoria implements PecaInsumoRepository {

    private final Map<String, PecaInsumo> pecas = new LinkedHashMap<>();

    @Override
    public void criar(PecaInsumo peca) {
        if (pecas.containsKey(peca.getId())) {
            throw new ConflitoDeRecursoException("Ja existe peca/insumo com o ID: " + peca.getId());
        }
        pecas.put(peca.getId(), comVersao(peca, 1L));
    }

    @Override
    public void substituir(PecaInsumo peca) {
        PecaInsumo atual = pecas.get(peca.getId());
        if (atual == null) {
            throw new RecursoNaoEncontradoException("Peça/Insumo não encontrada com o ID: " + peca.getId());
        }
        if (!atual.getVersao().equals(peca.getVersao())) {
            throw new ConflitoDeRecursoException("A peça/insumo foi alterada por outra requisição; consulte e tente novamente.");
        }
        pecas.put(peca.getId(), comVersao(peca, atual.getVersao() + 1));
    }

    @Override
    public Optional<PecaInsumo> buscarPorId(String id) {
        return Optional.ofNullable(pecas.get(id));
    }

    @Override
    public List<PecaInsumo> buscarTodos() {
        return List.copyOf(pecas.values());
    }

    @Override
    public void excluirPorId(String id) {
        PecaInsumo peca = pecas.get(id);
        if (peca != null && peca.getQuantidadeReservada() > 0) {
            throw new RegraDeNegocioException("Não é possível excluir peça/insumo com reserva ativa.");
        }
        pecas.remove(id);
    }

    @Override
    public void adicionarEstoque(String id, int quantidade) {
        PecaInsumo peca = pecas.get(id);
        if (peca == null) {
            throw new RecursoNaoEncontradoException("Peça/Insumo não encontrada com o ID: " + id);
        }
        ajustar(peca, peca.getQuantidadeEstoque() + quantidade, peca.getQuantidadeReservada());
    }

    @Override
    public void removerEstoque(String id, int quantidade) {
        PecaInsumo peca = pecas.get(id);
        if (peca == null) {
            throw new RecursoNaoEncontradoException("Peça/Insumo não encontrada com o ID: " + id);
        }
        if (peca.getQuantidadeDisponivel() < quantidade) {
            throw new RegraDeNegocioException("Estoque disponível insuficiente. Disponível: " + peca.getQuantidadeDisponivel()
                    + ", quantidade solicitada: " + quantidade);
        }
        ajustar(peca, peca.getQuantidadeEstoque() - quantidade, peca.getQuantidadeReservada());
    }

    public void limpar() {
        pecas.clear();
    }

    /** Atalho de teste: cadastra ja com id e quantidades conhecidos. */
    public PecaInsumo semear(String id, String descricao, String preco, int estoque) {
        PecaInsumo peca = new PecaInsumo(id, descricao, "Marca", new java.math.BigDecimal(preco), estoque, 0, "REF-" + id,
                br.com.oficina.execution.estoque.domain.model.CategoriaPeca.FILTROS, 1L);
        pecas.put(id, peca);
        return peca;
    }

    /** O que a reserva faz com a peca: o mesmo debito que o UpdateExpression do DynamoDB. */
    public void reservar(String id, int quantidade) {
        PecaInsumo peca = pecas.get(id);
        ajustar(peca, peca.getQuantidadeEstoque(), peca.getQuantidadeReservada() + quantidade);
    }

    public void devolver(String id, int quantidade) {
        PecaInsumo peca = pecas.get(id);
        ajustar(peca, peca.getQuantidadeEstoque(), peca.getQuantidadeReservada() - quantidade);
    }

    private void ajustar(PecaInsumo peca, int estoque, int reservada) {
        pecas.put(peca.getId(), new PecaInsumo(peca.getId(), peca.getDescricao(), peca.getMarca(), peca.getPreco(), estoque, reservada,
                peca.getCodigoReferencia(), peca.getCategoria(), peca.getVersao() + 1));
    }

    private static PecaInsumo comVersao(PecaInsumo peca, long versao) {
        return new PecaInsumo(peca.getId(), peca.getDescricao(), peca.getMarca(), peca.getPreco(), peca.getQuantidadeEstoque(),
                peca.getQuantidadeReservada(), peca.getCodigoReferencia(), peca.getCategoria(), versao);
    }
}
