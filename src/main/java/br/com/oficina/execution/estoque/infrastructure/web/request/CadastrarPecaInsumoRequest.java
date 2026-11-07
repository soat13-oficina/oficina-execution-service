package br.com.oficina.execution.estoque.infrastructure.web.request;

import java.math.BigDecimal;

import br.com.oficina.execution.estoque.domain.model.CategoriaPeca;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(name = "CadastrarPecaInsumoRequest", description = "Dados necessários para cadastrar uma peça ou insumo")
public record CadastrarPecaInsumoRequest(
        @NotBlank(message = "O campo descricao é obrigatório.")
        @Schema(description = "Descrição da peça/insumo", example = "Filtro de óleo motor")
        String descricao,

        @NotBlank(message = "O campo marca é obrigatório.")
        @Schema(description = "Marca da peça/insumo", example = "Bosch")
        String marca,

        @NotNull(message = "O campo preco é obrigatório.")
        @DecimalMin(value = "0.01", message = "O preço deve ser no mínimo 0.01.")
        @Schema(description = "Preço unitário da peça/insumo", example = "45.90")
        BigDecimal preco,

        @Min(value = 0, message = "A quantidade em estoque não pode ser negativa.")
        @Schema(description = "Quantidade inicial em estoque", example = "10")
        int quantidadeEstoque,

        @NotBlank(message = "O campo codigoReferencia é obrigatório.")
        @Schema(description = "Código de referência do fabricante", example = "OB0986B01044")
        String codigoReferencia,

        @NotNull(message = "O campo categoria é obrigatório.")
        @Schema(description = "Categoria da peça/insumo", example = "FILTROS")
        CategoriaPeca categoria) {
}
