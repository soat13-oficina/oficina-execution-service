package br.com.oficina.execution.execucao.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.Filas;
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.execucao.domain.model.Diagnostico;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.model.Mecanico;
import br.com.oficina.execution.execucao.domain.model.PecaDiagnostico;
import br.com.oficina.execution.execucao.domain.model.ServicoDiagnostico;
import br.com.oficina.execution.execucao.infrastructure.mensageria.ManipuladorComandosExecucao;
import br.com.oficina.execution.support.mensageria.PublicadorMensagensEmMemoria;
import br.com.oficina.execution.support.persistence.ExecucaoRepositoryEmMemoria;

class ExecucaoPelaSagaTest {

    private static final UUID SAGA = UUID.fromString("6f0e2b8a-1c3d-4e5f-8a9b-0c1d2e3f4a5b");
    private static final UUID ORDEM = UUID.fromString("3c9a1b2d-4e5f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final Instant AGORA = Instant.parse("2026-10-17T12:00:00Z");
    private static final Mecanico ANA = new Mecanico("ana@oficina.com", "Ana");

    private ExecucaoRepositoryEmMemoria execucoes;
    private PublicadorMensagensEmMemoria publicador;
    private ManipuladorComandosExecucao manipulador;

    @BeforeEach
    void preparar() {
        execucoes = new ExecucaoRepositoryEmMemoria();
        publicador = new PublicadorMensagensEmMemoria();
        manipulador = new ManipuladorComandosExecucao(new ExecucaoPelaSaga(execucoes,
                new EventosExecucao(publicador, PublicadorMensagensEmMemoria.fabrica()), PublicadorMensagensEmMemoria.JSON,
                Clock.fixed(AGORA, ZoneOffset.UTC)));
    }

    private static Envelope comando(UUID ordem, String tipo, Object payload) {
        return PublicadorMensagensEmMemoria.fabrica().novo(tipo, SAGA, ordem, payload);
    }

    private static Envelope enfileirarDiagnostico(UUID ordem) {
        return comando(ordem, MensagensExecucao.ENFILEIRAR_DIAGNOSTICO, new MensagensExecucao.EnfileirarDiagnostico(
                "OS-" + ordem.toString().substring(0, 4),
                new MensagensExecucao.Veiculo("ABC1D23", "Toyota", "Corolla", 2020),
                List.of(new MensagensExecucao.Servico("Revisao", "100.00")),
                List.of(new MensagensExecucao.ItemPeca("a", 1)),
                AGORA));
    }

    private Execucao concluidaDiagnostico() {
        Execucao e = execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().iniciarDiagnostico(ANA, AGORA)
                .concluirDiagnostico(new Diagnostico("Oleo", List.of(new ServicoDiagnostico("Troca", new BigDecimal("120.00"))),
                        List.of(new PecaDiagnostico("a", 1), new PecaDiagnostico("b", 2))), AGORA);
        execucoes.atualizar(e);
        return e;
    }

    @Test
    void manipuladorAtendeOsQuatroComandos() {
        assertThat(manipulador.tipos()).containsExactlyInAnyOrder(
                "EnfileirarDiagnostico", "ReabrirDiagnostico", "EnfileirarExecucao", "CancelarExecucao");
    }

    @Test
    void enfileirarDiagnosticoCriaAExecucaoNaFilaERespondeAPosicao() {
        Envelope pedido = enfileirarDiagnostico(ORDEM);

        manipulador.tratar(pedido);

        Execucao e = execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow();
        assertThat(e.etapa()).isEqualTo(Etapa.AGUARDANDO_DIAGNOSTICO);
        assertThat(e.sagaId()).isEqualTo(SAGA);
        assertThat(e.veiculo().placa()).isEqualTo("ABC1D23");
        assertThat(e.servicosPrevistos()).containsExactly(new ServicoDiagnostico("Revisao", new BigDecimal("100.00")));
        assertThat(e.pecasPrevistas()).containsExactly(new PecaDiagnostico("a", 1));
        Envelope resposta = publicador.unica();
        assertThat(publicador.publicadas().getFirst().fila()).isEqualTo(Filas.OS_SAGA_REPLIES);
        assertThat(resposta.type()).isEqualTo("DiagnosticoEnfileirado");
        assertThat(resposta.causationId()).isEqualTo(pedido.messageId());
        assertThat(publicador.payload(resposta, MensagensExecucao.DiagnosticoEnfileirado.class).posicaoNaFila()).isEqualTo(1);
    }

    @Test
    void asOsEntramNaFilaEmOrdemDeChegada() {
        manipulador.tratar(enfileirarDiagnostico(UUID.randomUUID()));
        manipulador.tratar(enfileirarDiagnostico(UUID.randomUUID()));
        manipulador.tratar(enfileirarDiagnostico(ORDEM));

        assertThat(publicador.publicadas()).extracting(p -> publicador.payload(p.envelope(), MensagensExecucao.DiagnosticoEnfileirado.class).posicaoNaFila())
                .containsExactly(1, 2, 3);
    }

    @Test
    void enfileirarDiagnosticoRepetidoSoRepeteARespostaComAPosicaoAtual() {
        manipulador.tratar(enfileirarDiagnostico(UUID.randomUUID()));
        manipulador.tratar(enfileirarDiagnostico(ORDEM));

        manipulador.tratar(enfileirarDiagnostico(ORDEM));

        assertThat(execucoes.listarPorEtapa(Etapa.AGUARDANDO_DIAGNOSTICO)).hasSize(2);
        assertThat(publicador.payload(publicador.publicadas().getLast().envelope(), MensagensExecucao.DiagnosticoEnfileirado.class)
                .posicaoNaFila()).isEqualTo(1 + 1);
    }

    @Test
    void enfileirarDiagnosticoRepetidoDepoisDeAndarNaoVoltaAFila() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        execucoes.atualizar(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().iniciarDiagnostico(ANA, AGORA));

        manipulador.tratar(enfileirarDiagnostico(ORDEM));

        assertThat(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().etapa()).isEqualTo(Etapa.EM_DIAGNOSTICO);
        assertThat(publicador.payload(publicador.publicadas().getLast().envelope(), MensagensExecucao.DiagnosticoEnfileirado.class)
                .posicaoNaFila()).isEqualTo(1);
    }

    @Test
    void reabrirDiagnosticoVoltaParaAFilaSemAsPecasInvalidas() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        concluidaDiagnostico();
        publicador.limpar();

        manipulador.tratar(comando(ORDEM, MensagensExecucao.REABRIR_DIAGNOSTICO,
                new MensagensExecucao.ReabrirDiagnostico("Peca invalida", List.of("b"))));

        Execucao e = execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow();
        assertThat(e.etapa()).isEqualTo(Etapa.AGUARDANDO_DIAGNOSTICO);
        assertThat(e.pecasPrevistas()).containsExactly(new PecaDiagnostico("a", 1));
        assertThat(publicador.unica().type()).isEqualTo("DiagnosticoReaberto");
        assertThat(publicador.payload(publicador.unica(), MensagensExecucao.DiagnosticoReaberto.class).reabertoEm()).isEqualTo(AGORA);
    }

    @Test
    void reabrirSemPecasInvalidasMantemTodasAsPecas() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        concluidaDiagnostico();

        manipulador.tratar(comando(ORDEM, MensagensExecucao.REABRIR_DIAGNOSTICO,
                new MensagensExecucao.ReabrirDiagnostico("Falhou", null)));

        assertThat(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().pecasPrevistas()).hasSize(2);
    }

    @Test
    void reabrirRepetidoSoRepeteAResposta() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        concluidaDiagnostico();
        Envelope reabrir = comando(ORDEM, MensagensExecucao.REABRIR_DIAGNOSTICO, new MensagensExecucao.ReabrirDiagnostico("x", List.of()));
        manipulador.tratar(reabrir);
        long versao = execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().versao();

        manipulador.tratar(comando(ORDEM, MensagensExecucao.REABRIR_DIAGNOSTICO, new MensagensExecucao.ReabrirDiagnostico("x", List.of())));

        assertThat(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().versao()).isEqualTo(versao);
        assertThat(publicador.tipos()).endsWith("DiagnosticoReaberto", "DiagnosticoReaberto");
    }

    @Test
    void reabrirOQueNaoTemDiagnosticoConcluidoEUmErroDaSaga() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        concluidaDiagnostico();
        execucoes.atualizar(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().enfileirarExecucao(AGORA));

        assertThatThrownBy(() -> manipulador.tratar(comando(ORDEM, MensagensExecucao.REABRIR_DIAGNOSTICO,
                new MensagensExecucao.ReabrirDiagnostico("x", List.of()))))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    void reabrirOsSemExecucaoEUmErroDaSaga() {
        assertThatThrownBy(() -> manipulador.tratar(comando(UUID.randomUUID(), MensagensExecucao.REABRIR_DIAGNOSTICO,
                new MensagensExecucao.ReabrirDiagnostico("x", List.of()))))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void enfileirarExecucaoColocaNaFilaERespondeAPosicao() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        concluidaDiagnostico();
        publicador.limpar();

        manipulador.tratar(comando(ORDEM, MensagensExecucao.ENFILEIRAR_EXECUCAO, new MensagensExecucao.EnfileirarExecucao(UUID.randomUUID())));

        assertThat(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().etapa()).isEqualTo(Etapa.AGUARDANDO_EXECUCAO);
        assertThat(publicador.unica().type()).isEqualTo("ExecucaoEnfileirada");
        assertThat(publicador.payload(publicador.unica(), MensagensExecucao.ExecucaoEnfileirada.class).posicaoNaFila()).isEqualTo(1);
    }

    @Test
    void enfileirarExecucaoRepetidoNaoMudaNada() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        concluidaDiagnostico();
        Envelope enfileirar = comando(ORDEM, MensagensExecucao.ENFILEIRAR_EXECUCAO, new MensagensExecucao.EnfileirarExecucao(UUID.randomUUID()));
        manipulador.tratar(enfileirar);

        manipulador.tratar(comando(ORDEM, MensagensExecucao.ENFILEIRAR_EXECUCAO, new MensagensExecucao.EnfileirarExecucao(UUID.randomUUID())));

        assertThat(execucoes.listarPorEtapa(Etapa.AGUARDANDO_EXECUCAO)).hasSize(1);
        assertThat(publicador.tipos()).endsWith("ExecucaoEnfileirada", "ExecucaoEnfileirada");
    }

    @Test
    void enfileirarExecucaoSemDiagnosticoConcluidoEUmErroDaSaga() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));

        assertThatThrownBy(() -> manipulador.tratar(comando(ORDEM, MensagensExecucao.ENFILEIRAR_EXECUCAO,
                new MensagensExecucao.EnfileirarExecucao(UUID.randomUUID()))))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    void cancelarMudaAEtapaEConfirma() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        concluidaDiagnostico();
        publicador.limpar();

        manipulador.tratar(comando(ORDEM, MensagensExecucao.CANCELAR_EXECUCAO, new MensagensExecucao.CancelarExecucao("ORCAMENTO_RECUSADO")));

        assertThat(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().etapa()).isEqualTo(Etapa.CANCELADA);
        assertThat(publicador.unica().type()).isEqualTo("ExecucaoCancelada");
        assertThat(publicador.payload(publicador.unica(), MensagensExecucao.ExecucaoCancelada.class).canceladaEm()).isEqualTo(AGORA);
    }

    @Test
    void cancelarSempreConfirmaMesmoSemNadaADesfazer() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        Envelope cancelar = comando(ORDEM, MensagensExecucao.CANCELAR_EXECUCAO, new MensagensExecucao.CancelarExecucao("x"));
        manipulador.tratar(cancelar); // cancela
        manipulador.tratar(comando(ORDEM, MensagensExecucao.CANCELAR_EXECUCAO, new MensagensExecucao.CancelarExecucao("x"))); // ja cancelada
        manipulador.tratar(comando(UUID.randomUUID(), MensagensExecucao.CANCELAR_EXECUCAO, new MensagensExecucao.CancelarExecucao("x"))); // inexistente

        assertThat(publicador.tipos()).endsWith("ExecucaoCancelada", "ExecucaoCancelada", "ExecucaoCancelada");
    }

    @Test
    void cancelarExecucaoJaConcluidaNaoMudaNada() {
        manipulador.tratar(enfileirarDiagnostico(ORDEM));
        concluidaDiagnostico();
        Execucao enfileirada = execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().enfileirarExecucao(AGORA).iniciarExecucao(ANA, AGORA).concluirExecucao(AGORA);
        execucoes.atualizar(enfileirada);

        manipulador.tratar(comando(ORDEM, MensagensExecucao.CANCELAR_EXECUCAO, new MensagensExecucao.CancelarExecucao("x")));

        assertThat(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().etapa()).isEqualTo(Etapa.CONCLUIDA);
    }
}
