package br.com.oficina.execution.estoque.infrastructure.persistence;

import java.math.BigDecimal;

import br.com.oficina.execution.estoque.domain.model.CategoriaPeca;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;

/**
 * Item da tabela {@code oficina-<env>-pecas} (ADR 0006). {@code quantidadeDisponivel} e dado derivado
 * ({@code estoque - reservada}) guardado de proposito: o DynamoDB nao aceita aritmetica em
 * {@code ConditionExpression}, e a reserva precisa da condicao {@code quantidadeDisponivel >= :q}.
 * Toda escrita que mexe em estoque ou reserva mantem os tres campos coerentes.
 */
@DynamoDbBean
public class PecaItem {
    private String pecaId;
    private String descricao;
    private String marca;
    private BigDecimal preco;
    private Integer quantidadeEstoque;
    private Integer quantidadeReservada;
    private Integer quantidadeDisponivel;
    private String codigoReferencia;
    private CategoriaPeca categoria;
    private Long versao;

    public static PecaItem de(PecaInsumo peca) {
        PecaItem item = new PecaItem();
        item.pecaId = peca.getId();
        item.descricao = peca.getDescricao();
        item.marca = peca.getMarca();
        item.preco = peca.getPreco();
        item.quantidadeEstoque = peca.getQuantidadeEstoque();
        item.quantidadeReservada = peca.getQuantidadeReservada();
        item.quantidadeDisponivel = peca.getQuantidadeDisponivel();
        item.codigoReferencia = peca.getCodigoReferencia();
        item.categoria = peca.getCategoria();
        item.versao = peca.getVersao();
        return item;
    }

    public PecaInsumo paraDominio() {
        return new PecaInsumo(pecaId, descricao, marca, preco, quantidadeEstoque, quantidadeReservada, codigoReferencia, categoria, versao);
    }

    @DynamoDbPartitionKey
    public String getPecaId() {
        return pecaId;
    }

    public void setPecaId(String pecaId) {
        this.pecaId = pecaId;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public String getMarca() {
        return marca;
    }

    public void setMarca(String marca) {
        this.marca = marca;
    }

    public BigDecimal getPreco() {
        return preco;
    }

    public void setPreco(BigDecimal preco) {
        this.preco = preco;
    }

    public Integer getQuantidadeEstoque() {
        return quantidadeEstoque;
    }

    public void setQuantidadeEstoque(Integer quantidadeEstoque) {
        this.quantidadeEstoque = quantidadeEstoque;
    }

    public Integer getQuantidadeReservada() {
        return quantidadeReservada;
    }

    public void setQuantidadeReservada(Integer quantidadeReservada) {
        this.quantidadeReservada = quantidadeReservada;
    }

    public Integer getQuantidadeDisponivel() {
        return quantidadeDisponivel;
    }

    public void setQuantidadeDisponivel(Integer quantidadeDisponivel) {
        this.quantidadeDisponivel = quantidadeDisponivel;
    }

    public String getCodigoReferencia() {
        return codigoReferencia;
    }

    public void setCodigoReferencia(String codigoReferencia) {
        this.codigoReferencia = codigoReferencia;
    }

    public CategoriaPeca getCategoria() {
        return categoria;
    }

    public void setCategoria(CategoriaPeca categoria) {
        this.categoria = categoria;
    }

    /** Lock otimista: o Enhanced Client condiciona a escrita a esta versao e a incrementa. */
    @DynamoDbVersionAttribute
    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}
