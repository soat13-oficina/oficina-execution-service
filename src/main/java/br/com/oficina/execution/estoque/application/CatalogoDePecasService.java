package br.com.oficina.execution.estoque.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.estoque.domain.model.CategoriaPeca;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.estoque.domain.repository.PecaInsumoRepository;

/**
 * Cadastro e estoque das pecas (as mesmas operacoes do modulo {@code pecainsumo} do OS Service).
 * Reservar, liberar e consumir NAO moram aqui: sao passos da Saga ({@link ReservasPelaSaga}), para
 * que toda reserva tenha uma OS dona.
 */
@Service
public class CatalogoDePecasService {
    private static final Logger log = LoggerFactory.getLogger(CatalogoDePecasService.class);

    /** Teto de ids por consulta interna (contrato em docs/contratos/rest-interno.md). */
    public static final int MAXIMO_IDS_POR_CONSULTA = 200;

    private final PecaInsumoRepository pecas;

    public CatalogoDePecasService(PecaInsumoRepository pecas) {
        this.pecas = pecas;
    }

    public record DadosPeca(
            String descricao,
            String marca,
            BigDecimal preco,
            int quantidadeEstoque,
            String codigoReferencia,
            CategoriaPeca categoria) {
    }

    /** Filtros da listagem; {@code null} = sem filtro. */
    public record Filtro(String marca, CategoriaPeca categoria, Boolean possuiReserva) {
    }

    /** Resultado da consulta interna: as encontradas, na ordem pedida, e os ids sem cadastro. */
    public record ResultadoConsulta(List<PecaInsumo> encontradas, List<String> inexistentes) {
    }

    /** @return o id da peca cadastrada */
    public String cadastrar(DadosPeca dados) {
        PecaInsumo peca = PecaInsumo.nova(
                dados.descricao(), dados.marca(), dados.preco(), dados.quantidadeEstoque(), dados.codigoReferencia(), dados.categoria());
        pecas.criar(peca);
        log.info("Peca/insumo cadastrada. id={}, estoque={}", peca.getId(), peca.getQuantidadeEstoque());
        return peca.getId();
    }

    /**
     * Corrige os dados da peca. A quantidade reservada nao e editavel: pertence as reservas das OSs
     * (Saga) e e mantida como esta; o estoque novo nao pode ficar abaixo dela.
     */
    public void alterar(String id, DadosPeca dados) {
        PecaInsumo atual = buscarOuFalhar(id);
        pecas.substituir(atual.alterar(
                dados.descricao(), dados.marca(), dados.preco(), dados.quantidadeEstoque(), atual.getQuantidadeReservada(),
                dados.codigoReferencia(), dados.categoria()));
    }

    public PecaInsumo buscar(String id) {
        return buscarOuFalhar(id);
    }

    public List<PecaInsumo> listar(Filtro filtro) {
        return pecas.buscarTodos().stream()
                .filter(peca -> filtro.marca() == null || peca.getMarca().equals(filtro.marca()))
                .filter(peca -> filtro.categoria() == null || peca.getCategoria() == filtro.categoria())
                .filter(peca -> filtro.possuiReserva() == null || (filtro.possuiReserva() == (peca.getQuantidadeReservada() > 0)))
                .toList();
    }

    public void excluir(String id) {
        PecaInsumo peca = buscarOuFalhar(id);
        if (peca.getQuantidadeReservada() > 0) {
            log.warn("Exclusao de peca/insumo bloqueada por reserva ativa. id={}, quantidadeReservada={}", id, peca.getQuantidadeReservada());
            throw new RegraDeNegocioException("Não é possível excluir peça/insumo com reserva ativa.");
        }
        pecas.excluirPorId(id);
        log.info("Peca/insumo excluida. id={}", id);
    }

    public void adicionarEstoque(String id, int quantidade) {
        exigirPositiva(quantidade, "adicionada");
        pecas.adicionarEstoque(id, quantidade);
    }

    public void removerEstoque(String id, int quantidade) {
        exigirPositiva(quantidade, "removida");
        pecas.removerEstoque(id, quantidade);
    }

    /** Descricao e preco atuais para o Billing montar o orcamento. */
    public ResultadoConsulta consultar(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new RegraDeNegocioException("Informe ao menos um id de peca/insumo.");
        }
        LinkedHashSet<String> unicos = new LinkedHashSet<>(ids);
        if (unicos.size() > MAXIMO_IDS_POR_CONSULTA) {
            throw new RegraDeNegocioException("No maximo " + MAXIMO_IDS_POR_CONSULTA + " pecas por consulta.");
        }
        List<PecaInsumo> encontradas = new ArrayList<>();
        List<String> inexistentes = new ArrayList<>();
        for (String id : unicos) {
            Optional<PecaInsumo> peca = id == null || id.isBlank() ? Optional.empty() : pecas.buscarPorId(id);
            peca.ifPresentOrElse(encontradas::add, () -> inexistentes.add(id));
        }
        log.info("Consulta interna de pecas. solicitadas={}, encontradas={}, inexistentes={}",
                unicos.size(), encontradas.size(), inexistentes.size());
        return new ResultadoConsulta(encontradas, inexistentes);
    }

    private PecaInsumo buscarOuFalhar(String id) {
        return pecas.buscarPorId(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Peça/Insumo não encontrada com o ID: " + id));
    }

    private static void exigirPositiva(int quantidade, String verbo) {
        if (quantidade <= 0) {
            throw new RegraDeNegocioException("A quantidade a ser " + verbo + " deve ser maior que zero");
        }
    }
}
