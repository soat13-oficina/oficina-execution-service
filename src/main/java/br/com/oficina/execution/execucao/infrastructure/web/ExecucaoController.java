package br.com.oficina.execution.execucao.infrastructure.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.oficina.execution.common.infrastructure.seguranca.UsuarioAutenticado;
import br.com.oficina.execution.execucao.application.ExecucaoService;
import br.com.oficina.execution.execucao.application.ExecucaoService.DadosDiagnostico;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Mecanico;
import br.com.oficina.execution.execucao.domain.model.PecaDiagnostico;
import br.com.oficina.execution.execucao.domain.model.ServicoDiagnostico;
import br.com.oficina.execution.execucao.infrastructure.web.request.AbortarExecucaoRequest;
import br.com.oficina.execution.execucao.infrastructure.web.request.ConcluirDiagnosticoRequest;
import br.com.oficina.execution.execucao.infrastructure.web.response.ExecucaoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * A fila e o trabalho do mecanico (diagnostico e execucao). Somente funcionarios (ver
 * {@code SecurityConfig}). Cada acao muda a etapa e publica o evento correspondente para a Saga do OS
 * Service na mesma transacao.
 */
@RestController
@RequestMapping("/execucoes")
@Tag(name = "Execuções", description = "Fila, diagnóstico e execução do serviço (somente funcionários)")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Sem token válido", content = @Content),
        @ApiResponse(responseCode = "403", description = "Token de cliente", content = @Content)
})
public class ExecucaoController {

    private final ExecucaoService servico;

    public ExecucaoController(ExecucaoService servico) {
        this.servico = servico;
    }

    @Operation(summary = "Listar a fila",
            description = "Sem `etapa`, as duas filas de espera (diagnóstico e depois execução), em ordem de chegada. "
                    + "Com `etapa`, as execuções daquela etapa.")
    @GetMapping
    public ResponseEntity<List<ExecucaoResponse>> listar(@RequestParam(required = false) Etapa etapa) {
        return ResponseEntity.ok(servico.listar(etapa).stream().map(ExecucaoResponse::from).toList());
    }

    @Operation(summary = "Consultar a execução de uma OS")
    @ApiResponse(responseCode = "404", description = "A OS não tem execução", content = @Content)
    @GetMapping("/{ordemServicoId}")
    public ResponseEntity<ExecucaoResponse> buscar(@PathVariable UUID ordemServicoId) {
        return ResponseEntity.ok(ExecucaoResponse.from(servico.buscar(ordemServicoId)));
    }

    @Operation(summary = "Iniciar o diagnóstico", description = "A OS precisa estar aguardando diagnóstico. Publica `DiagnosticoIniciado`.")
    @PostMapping("/{ordemServicoId}/diagnostico/iniciar")
    public ResponseEntity<ExecucaoResponse> iniciarDiagnostico(
            @PathVariable UUID ordemServicoId, @AuthenticationPrincipal UsuarioAutenticado usuario) {
        return ResponseEntity.ok(ExecucaoResponse.from(servico.iniciarDiagnostico(ordemServicoId, mecanico(usuario))));
    }

    @Operation(summary = "Concluir o diagnóstico",
            description = "Informa serviços (com mão de obra) e peças, que precisam existir no catálogo. Publica `DiagnosticoConcluido`: "
                    + "a Saga gera o orçamento sozinha.")
    @PostMapping("/{ordemServicoId}/diagnostico/concluir")
    public ResponseEntity<ExecucaoResponse> concluirDiagnostico(
            @PathVariable UUID ordemServicoId,
            @AuthenticationPrincipal UsuarioAutenticado usuario,
            @Valid @RequestBody ConcluirDiagnosticoRequest request) {
        DadosDiagnostico dados = new DadosDiagnostico(
                request.descricaoDiagnostico(),
                request.servicos().stream().map(s -> new ServicoDiagnostico(s.descricao(), s.valorMaoDeObra())).toList(),
                request.pecas().stream().map(p -> new PecaDiagnostico(p.pecaInsumoId(), p.quantidade())).toList());
        return ResponseEntity.ok(ExecucaoResponse.from(servico.concluirDiagnostico(ordemServicoId, mecanico(usuario), dados)));
    }

    @Operation(summary = "Iniciar a execução",
            description = "A OS precisa estar na fila de execução (orçamento aprovado e pago). Publica `ExecucaoIniciada`.")
    @PostMapping("/{ordemServicoId}/iniciar")
    public ResponseEntity<ExecucaoResponse> iniciar(
            @PathVariable UUID ordemServicoId, @AuthenticationPrincipal UsuarioAutenticado usuario) {
        return ResponseEntity.ok(ExecucaoResponse.from(servico.iniciarExecucao(ordemServicoId, mecanico(usuario))));
    }

    @Operation(summary = "Concluir a execução",
            description = "Baixa do estoque as peças reservadas para a OS e publica `ExecucaoConcluida`.")
    @PostMapping("/{ordemServicoId}/concluir")
    public ResponseEntity<ExecucaoResponse> concluir(
            @PathVariable UUID ordemServicoId, @AuthenticationPrincipal UsuarioAutenticado usuario) {
        return ResponseEntity.ok(ExecucaoResponse.from(servico.concluirExecucao(ordemServicoId, mecanico(usuario))));
    }

    @Operation(summary = "Abortar a execução",
            description = "Publica `ExecucaoFalhou`: a Saga estorna o pagamento e libera as peças reservadas.")
    @PostMapping("/{ordemServicoId}/abortar")
    public ResponseEntity<ExecucaoResponse> abortar(
            @PathVariable UUID ordemServicoId,
            @AuthenticationPrincipal UsuarioAutenticado usuario,
            @Valid @RequestBody AbortarExecucaoRequest request) {
        return ResponseEntity.ok(ExecucaoResponse.from(servico.abortarExecucao(ordemServicoId, mecanico(usuario), request.motivo())));
    }

    /** Id e nome vem das claims do JWT; sem nome na claim, o e-mail identifica a pessoa. */
    private static Mecanico mecanico(UsuarioAutenticado usuario) {
        String nome = usuario.nome() == null || usuario.nome().isBlank() ? usuario.subject() : usuario.nome();
        return new Mecanico(usuario.subject(), nome);
    }
}
