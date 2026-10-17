package br.com.oficina.execution.estoque.infrastructure.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

@Schema(name = "RemoverEstoquePecaRequest", description = "Dados para remover estoque")
public record RemoverEstoquePecaRequest(
        @Min(value = 1, message = "A quantidade deve ser no mínimo 1.")
        @Schema(description = "Quantidade a remover do estoque disponível", example = "3")
        int quantidade) {
}
