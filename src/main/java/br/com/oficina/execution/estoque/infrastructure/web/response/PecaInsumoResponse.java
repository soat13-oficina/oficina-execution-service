package br.com.oficina.execution.estoque.infrastructure.web.response;

import java.math.BigDecimal;

import br.com.oficina.execution.estoque.domain.model.CategoriaPeca;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PecaInsumoResponse", description = "Representação de uma peça ou insumo")
public record PecaInsumoResponse(
        @Schema(description = "Identificador da peça/insumo", example = "8e221ff7-71b9-4c22-8a8d-f94b6fd897cd")
        String id,
        @Schema(description = "Descrição da peça/insumo", example = "Filtro de óleo motor")
        String descricao,
        @Schema(description = "Marca da peça/insumo", example = "Bosch")
        String marca,
        @Schema(description = "Preço unitário", example = "45.90")
        BigDecimal preco,
        @Schema(description = "Quantidade total em estoque", example = "10")
        int quantidadeEstoque,
        @Schema(description = "Quantidade reservada para OSs", example = "2")
        int quantidadeReservada,
        @Schema(description = "Quantidade disponível (estoque - reservada)", example = "8")
        int quantidadeDisponivel,
        @Schema(description = "Código de referência do fabricante", example = "OB0986B01044")
        String codigoReferencia,
        @Schema(description = "Categoria da peça/insumo", example = "FILTROS")
        CategoriaPeca categoria) {

    public static PecaInsumoResponse from(PecaInsumo peca) {
        return new PecaInsumoResponse(
                peca.getId(),
                peca.getDescricao(),
                peca.getMarca(),
                peca.getPreco(),
                peca.getQuantidadeEstoque(),
                peca.getQuantidadeReservada(),
                peca.getQuantidadeDisponivel(),
                peca.getCodigoReferencia(),
                peca.getCategoria());
    }
}
