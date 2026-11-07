package br.com.oficina.execution.contratos;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.execucao.application.EventosExecucao;
import br.com.oficina.execution.execucao.application.ExecucaoPelaSaga;
import br.com.oficina.execution.execucao.application.ExecucaoService;
import br.com.oficina.execution.execucao.domain.model.Mecanico;
import br.com.oficina.execution.execucao.domain.model.PecaDiagnostico;
import br.com.oficina.execution.execucao.domain.model.ServicoDiagnostico;
import br.com.oficina.execution.execucao.infrastructure.mensageria.ManipuladorComandosExecucao;
import br.com.oficina.execution.support.contratos.ContratosDeMensagens;
import br.com.oficina.execution.support.mensageria.PublicadorMensagensEmMemoria;
import br.com.oficina.execution.support.persistence.ExecucaoRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.PecaInsumoRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.ReservaPecaRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.TransacaoEmMemoria;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Teste de contrato (ADR 0005) da execucao, dos dois lados:
 * <ul>
 *   <li>consumidor: o exemplo de cada comando que a execucao recebe, como o contrato documenta, e
 *       processado sem erro;</li>
 *   <li>produtor: todo evento que a execucao publica - em resposta a comando ou por acao do mecanico -
 *       passa no JSON Schema do seu tipo.</li>
 * </ul>
 */
class ContratoDaExecucaoTest {

    private static final ContratosDeMensagens CONTRATOS = new ContratosDeMensagens();
    private static final Set<String> PRODUZIDOS = Set.of(
            "DiagnosticoEnfileirado", "DiagnosticoIniciado", "DiagnosticoConcluido", "DiagnosticoReaberto",
            "ExecucaoEnfileirada", "ExecucaoIniciada", "ExecucaoConcluida", "ExecucaoFalhou", "ExecucaoCancelada");

    private ExecucaoRepositoryEmMemoria execucoes;
    private PecaInsumoRepositoryEmMemoria pecas;
    private ReservaPecaRepositoryEmMemoria reservas;
    private PublicadorMensagensEmMemoria publicador;
    private ManipuladorComandosExecucao manipulador;
    private ExecucaoService servico;

    @BeforeEach
    void preparar() {
        execucoes = new ExecucaoRepositoryEmMemoria();
        pecas = new PecaInsumoRepositoryEmMemoria();
        reservas = new ReservaPecaRepositoryEmMemoria(pecas);
        publicador = new PublicadorMensagensEmMemoria();
        EventosExecucao eventos = new EventosExecucao(publicador, PublicadorMensagensEmMemoria.fabrica());
        manipulador = new ManipuladorComandosExecucao(new ExecucaoPelaSaga(execucoes, eventos, PublicadorMensagensEmMemoria.JSON));
        servico = new ExecucaoService(execucoes, pecas, reservas, eventos, new TransacaoEmMemoria());
    }

    private void consumir(ObjectNode exemplo, UUID ordem) {
        exemplo.put("messageId", UUID.randomUUID().toString());
        exemplo.put("ordemServicoId", ordem.toString());
        manipulador.tratar(PublicadorMensagensEmMemoria.JSON.treeToValue(exemplo, Envelope.class));
    }

    @Test
    void exemplosDosComandosSaoProcessadosEOsEventosRespeitamOContrato() {
        // EnfileirarDiagnostico do contrato; o veiculo, os servicos e as pecas previstas vem do exemplo.
        ObjectNode enfileirar = (ObjectNode) CONTRATOS.exemplo("EnfileirarDiagnostico");
        UUID ordem = UUID.fromString(enfileirar.get("ordemServicoId").asString());
        consumir(enfileirar, ordem);
        for (JsonNode peca : enfileirar.get("payload").path("pecasPrevistas")) {
            pecas.semear(peca.get("pecaInsumoId").asString(), "Peca do exemplo", "80.00", 10);
        }
        pecas.semear("p-1", "Filtro", "80.00", 10);

        // Acoes do mecanico: diagnostico iniciado e concluido.
        Mecanico ana = new Mecanico("ana@oficina.com", "Ana");
        servico.iniciarDiagnostico(ordem, ana);
        servico.concluirDiagnostico(ordem, ana, new ExecucaoService.DadosDiagnostico("Oleo vencido",
                List.of(new ServicoDiagnostico("Troca de oleo", new BigDecimal("120"))), List.of(new PecaDiagnostico("p-1", 1))));

        // ReabrirDiagnostico do contrato (compensacao de OrcamentoFalhou) e novo diagnostico.
        consumir((ObjectNode) CONTRATOS.exemplo("ReabrirDiagnostico"), ordem);
        servico.iniciarDiagnostico(ordem, ana);
        servico.concluirDiagnostico(ordem, ana, new ExecucaoService.DadosDiagnostico("Oleo vencido",
                List.of(new ServicoDiagnostico("Troca de oleo", new BigDecimal("120.50"))), List.of(new PecaDiagnostico("p-1", 1))));

        // EnfileirarExecucao do contrato, execucao iniciada e concluida (com a peca reservada pela Saga).
        consumir((ObjectNode) CONTRATOS.exemplo("EnfileirarExecucao"), ordem);
        servico.iniciarExecucao(ordem, ana);
        reservas.reservar(ordem, java.util.Map.of("p-1", 1), java.time.Instant.now());
        servico.concluirExecucao(ordem, ana);

        // Outra OS: aborto e cancelamento (CancelarExecucao do contrato, em outra OS pronta para cancelar).
        UUID abortada = UUID.randomUUID();
        consumir((ObjectNode) CONTRATOS.exemplo("EnfileirarDiagnostico"), abortada);
        servico.iniciarDiagnostico(abortada, ana);
        servico.concluirDiagnostico(abortada, ana, new ExecucaoService.DadosDiagnostico("Freio",
                List.of(new ServicoDiagnostico("Pastilhas", new BigDecimal("180.00"))), List.of()));
        consumir((ObjectNode) CONTRATOS.exemplo("EnfileirarExecucao"), abortada);
        servico.iniciarExecucao(abortada, ana);
        servico.abortarExecucao(abortada, ana, "Peca com defeito");
        UUID cancelada = UUID.randomUUID();
        consumir((ObjectNode) CONTRATOS.exemplo("EnfileirarDiagnostico"), cancelada);
        consumir((ObjectNode) CONTRATOS.exemplo("CancelarExecucao"), cancelada);

        List<String> erros = new ArrayList<>();
        Set<String> tipos = new TreeSet<>();
        for (var publicada : publicador.publicadas()) {
            tipos.add(publicada.envelope().type());
            erros.addAll(CONTRATOS.validar(PublicadorMensagensEmMemoria.JSON.writeValueAsString(publicada.envelope())));
        }
        assertThat(erros).as("eventos fora do contrato").isEmpty();
        assertThat(tipos).containsExactlyInAnyOrderElementsOf(PRODUZIDOS);
    }
}
