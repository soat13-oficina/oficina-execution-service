package br.com.oficina.execution.estoque.infrastructure.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonFormat;

import br.com.oficina.execution.estoque.application.CatalogoDePecasService;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Consulta interna do cadastro de pecas pelo Billing ao gerar o orcamento (ADR 0003). Nao passa pelo
 * API Gateway: exige {@code X-Service-Token} (ver {@code ServiceTokenFilter}). Contrato em
 * {@code docs/contratos/rest-interno.md} do OS Service.
 */
@RestController
@Tag(name = "Interno", description = "Chamadas entre servicos, fora do API Gateway (header X-Service-Token)")
public class ConsultaInternaPecasController {

    private final CatalogoDePecasService catalogo;

    public ConsultaInternaPecasController(CatalogoDePecasService catalogo) {
        this.catalogo = catalogo;
    }

    @Schema(name = "ConsultaPecasRequest")
    public record ConsultaPecasRequest(
            @Schema(description = "Ids das pecas (ate 200; repetidos sao ignorados)") List<String> pecaInsumoIds) {
    }

    @Schema(name = "ConsultaPecasResponse")
    public record ConsultaPecasResponse(List<PecaConsultada> pecas, List<String> inexistentes) {
    }

    @Schema(name = "PecaConsultada")
    public record PecaConsultada(
            String pecaInsumoId,
            String descricao,
            @Schema(description = "Preco atual, texto com duas casas", example = "80.00")
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal preco) {

        static PecaConsultada de(PecaInsumo peca) {
            return new PecaConsultada(peca.getId(), peca.getDescricao(), peca.getPreco().setScale(2, RoundingMode.HALF_UP));
        }
    }

    @Operation(summary = "Consultar pecas por id (interno)",
            description = "Descricao e preco atuais das pecas pedidas, na ordem pedida, e os ids que nao existem no cadastro.",
            parameters = @Parameter(name = "X-Service-Token", in = ParameterIn.HEADER, required = true,
                    description = "Segredo oficina/<env>/service-token"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consulta feita",
                    content = @Content(schema = @Schema(implementation = ConsultaPecasResponse.class))),
            @ApiResponse(responseCode = "400", description = "Lista vazia ou com mais de 200 ids", content = @Content),
            @ApiResponse(responseCode = "401", description = "X-Service-Token ausente ou invalido", content = @Content)
    })
    @PostMapping("/internal/pecas-insumos/consulta")
    public ResponseEntity<ConsultaPecasResponse> consultar(@RequestBody ConsultaPecasRequest request) {
        CatalogoDePecasService.ResultadoConsulta resultado = catalogo.consultar(request == null ? null : request.pecaInsumoIds());
        return ResponseEntity.ok(new ConsultaPecasResponse(
                resultado.encontradas().stream().map(PecaConsultada::de).toList(),
                resultado.inexistentes()));
    }
}
