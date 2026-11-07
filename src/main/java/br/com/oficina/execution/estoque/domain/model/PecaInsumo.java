package br.com.oficina.execution.estoque.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;

/**
 * Peca ou insumo do catalogo. Invariante: {@code disponivel = estoque - reservada}, sempre
 * {@code 0 <= reservada <= estoque}. {@code versao} e o lock otimista do DynamoDB ({@code null} ate a
 * primeira gravacao).
 */
public class PecaInsumo {
    private final String id;
    private final String descricao;
    private final String marca;
    private final BigDecimal preco;
    private final int quantidadeEstoque;
    private final int quantidadeReservada;
    private final String codigoReferencia;
    private final CategoriaPeca categoria;
    private final Long versao;

    public PecaInsumo(
            String id,
            String descricao,
            String marca,
            BigDecimal preco,
            int quantidadeEstoque,
            int quantidadeReservada,
            String codigoReferencia,
            CategoriaPeca categoria,
            Long versao) {
        if (quantidadeEstoque < 0 || quantidadeReservada < 0) {
            throw new RegraDeNegocioException("As quantidades de estoque e reserva nao podem ser negativas.");
        }
        if (quantidadeReservada > quantidadeEstoque) {
            throw new RegraDeNegocioException(
                    "A quantidade reservada (" + quantidadeReservada + ") nao pode exceder o estoque (" + quantidadeEstoque + ").");
        }
        this.id = id;
        this.descricao = descricao;
        this.marca = marca;
        this.preco = preco;
        this.quantidadeEstoque = quantidadeEstoque;
        this.quantidadeReservada = quantidadeReservada;
        this.codigoReferencia = codigoReferencia;
        this.categoria = categoria;
        this.versao = versao;
    }

    /** Peca nova: id gerado, nada reservado, ainda sem versao. */
    public static PecaInsumo nova(
            String descricao, String marca, BigDecimal preco, int quantidadeEstoque, String codigoReferencia, CategoriaPeca categoria) {
        return new PecaInsumo(UUID.randomUUID().toString(), descricao, marca, preco, quantidadeEstoque, 0, codigoReferencia, categoria, null);
    }

    /** Mesma peca (id e versao) com os dados corrigidos - o que o {@code PUT} faz. */
    public PecaInsumo alterar(
            String novaDescricao,
            String novaMarca,
            BigDecimal novoPreco,
            int novoEstoque,
            int novaReservada,
            String novoCodigoReferencia,
            CategoriaPeca novaCategoria) {
        return new PecaInsumo(id, novaDescricao, novaMarca, novoPreco, novoEstoque, novaReservada, novoCodigoReferencia, novaCategoria, versao);
    }

    public String getId() {
        return id;
    }

    public String getDescricao() {
        return descricao;
    }

    public String getMarca() {
        return marca;
    }

    public BigDecimal getPreco() {
        return preco;
    }

    public int getQuantidadeEstoque() {
        return quantidadeEstoque;
    }

    public int getQuantidadeReservada() {
        return quantidadeReservada;
    }

    public int getQuantidadeDisponivel() {
        return quantidadeEstoque - quantidadeReservada;
    }

    public String getCodigoReferencia() {
        return codigoReferencia;
    }

    public CategoriaPeca getCategoria() {
        return categoria;
    }

    public Long getVersao() {
        return versao;
    }
}
