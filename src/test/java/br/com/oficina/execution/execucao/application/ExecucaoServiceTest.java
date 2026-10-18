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
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import br.com.oficina.execution.execucao.application.ExecucaoService.DadosDiagnostico;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.model.Mecanico;
import br.com.oficina.execution.execucao.domain.model.PecaDiagnostico;
import br.com.oficina.execution.execucao.domain.model.ServicoDiagnostico;
import br.com.oficina.execution.execucao.domain.model.Veiculo;
import br.com.oficina.execution.support.mensageria.PublicadorMensagensEmMemoria;
import br.com.oficina.execution.support.persistence.ExecucaoRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.PecaInsumoRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.ReservaPecaRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.TransacaoEmMemoria;

class ExecucaoServiceTest {

    private static final UUID ORDEM = UUID.fromString("3c9a1b2d-4e5f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID SAGA = UUID.fromString("6f0e2b8a-1c3d-4e5f-8a9b-0c1d2e3f4a5b");
    private static final Mecanico ANA = new Mecanico("ana@oficina.com", "Ana");
    private static final Instant AGORA = Instant.parse("2026-10-17T12:00:00Z");

    private ExecucaoRepositoryEmMemoria execucoes;
    private PecaInsumoRepositoryEmMemoria pecas;
    private ReservaPecaRepositoryEmMemoria reservas;
    private PublicadorMensagensEmMemoria publicador;
    private ExecucaoService servico;

    @BeforeEach
    void preparar() {
        execucoes = new ExecucaoRepositoryEmMemoria();
        pecas = new PecaInsumoRepositoryEmMemoria();
        reservas = new ReservaPecaRepositoryEmMemoria(pecas);
        publicador = new PublicadorMensagensEmMemoria();
        servico = new ExecucaoService(execucoes, pecas, reservas,
                new EventosExecucao(publicador, PublicadorMensagensEmMemoria.fabrica()), new TransacaoEmMemoria(),
                Clock.fixed(AGORA, ZoneOffset.UTC));
        pecas.semear("a", "Filtro", "80.00", 10);
        pecas.semear("b", "Pastilha", "20.00", 10);
        execucoes.criar(Execucao.nova(ORDEM, SAGA, "OS-1", new Veiculo("ABC1D23", "Toyota", "Corolla", 2020), List.of(), List.of(), AGORA));
    }

    private static DadosDiagnostico diagnostico(PecaDiagnostico... pecas) {
        return new DadosDiagnostico("Oleo vencido",
                List.of(new ServicoDiagnostico("Troca de oleo", new BigDecimal("120"))), List.of(pecas));
    }

    private Execucao ate(Etapa etapa) {
        if (etapa == Etapa.AGUARDANDO_DIAGNOSTICO) {
            return servico.buscar(ORDEM);
        }
        servico.iniciarDiagnostico(ORDEM, ANA);
        if (etapa == Etapa.EM_DIAGNOSTICO) {
            return servico.buscar(ORDEM);
        }
        servico.concluirDiagnostico(ORDEM, ANA, diagnostico(new PecaDiagnostico("a", 2)));
        if (etapa == Etapa.DIAGNOSTICO_CONCLUIDO) {
            return servico.buscar(ORDEM);
        }
        execucoes.atualizar(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().enfileirarExecucao(AGORA));
        if (etapa == Etapa.AGUARDANDO_EXECUCAO) {
            return servico.buscar(ORDEM);
        }
        servico.iniciarExecucao(ORDEM, ANA);
        return servico.buscar(ORDEM);
    }

    @Test
    void iniciarDiagnosticoMudaAEtapaEPublicaODiagnosticoIniciado() {
        Execucao e = servico.iniciarDiagnostico(ORDEM, ANA);

        assertThat(e.etapa()).isEqualTo(Etapa.EM_DIAGNOSTICO);
        assertThat(e.mecanicoDiagnostico()).isEqualTo(ANA);
        Envelope evento = publicador.unica();
        assertThat(evento.type()).isEqualTo("DiagnosticoIniciado");
        assertThat(evento.causationId()).as("evento de pessoa nao tem causa").isNull();
        assertThat(evento.sagaId()).isEqualTo(SAGA);
        assertThat(evento.ordemServicoId()).isEqualTo(ORDEM);
        var payload = publicador.payload(evento, MensagensExecucao.DiagnosticoIniciado.class);
        assertThat(payload.mecanico()).isEqualTo(new MensagensExecucao.Mecanico("ana@oficina.com", "Ana"));
        assertThat(payload.iniciadoEm()).isEqualTo(AGORA);
    }

    @Test
    void concluirDiagnosticoPublicaServicosComDuasCasasEPecas() {
        servico.iniciarDiagnostico(ORDEM, ANA);
        publicador.limpar();

        Execucao e = servico.concluirDiagnostico(ORDEM, ANA, diagnostico(new PecaDiagnostico("a", 2)));

        assertThat(e.etapa()).isEqualTo(Etapa.DIAGNOSTICO_CONCLUIDO);
        assertThat(e.diagnostico().servicos()).hasSize(1);
        var payload = publicador.payload(publicador.unica(), MensagensExecucao.DiagnosticoConcluido.class);
        assertThat(publicador.unica().type()).isEqualTo("DiagnosticoConcluido");
        assertThat(payload.descricaoDiagnostico()).isEqualTo("Oleo vencido");
        assertThat(payload.servicos()).containsExactly(new MensagensExecucao.Servico("Troca de oleo", "120.00"));
        assertThat(payload.pecas()).containsExactly(new MensagensExecucao.ItemPeca("a", 2));
    }

    @Test
    void diagnosticoComPecaForaDoCatalogoEhRecusadoSemMudarNada() {
        servico.iniciarDiagnostico(ORDEM, ANA);
        publicador.limpar();

        assertThatThrownBy(() -> servico.concluirDiagnostico(ORDEM, ANA, diagnostico(new PecaDiagnostico("fantasma", 1))))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("fantasma");

        assertThat(servico.buscar(ORDEM).etapa()).isEqualTo(Etapa.EM_DIAGNOSTICO);
        assertThat(publicador.publicadas()).isEmpty();
    }

    @Test
    void diagnosticoComQuantidadeInvalidaEhRecusado() {
        servico.iniciarDiagnostico(ORDEM, ANA);

        assertThatThrownBy(() -> servico.concluirDiagnostico(ORDEM, ANA, diagnostico(new PecaDiagnostico("a", 0))))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("no mínimo 1");
    }

    @Test
    void iniciarExecucaoPublicaExecucaoIniciada() {
        ate(Etapa.AGUARDANDO_EXECUCAO);
        publicador.limpar();

        Execucao e = servico.iniciarExecucao(ORDEM, new Mecanico("beto@oficina.com", "Beto"));

        assertThat(e.etapa()).isEqualTo(Etapa.EM_EXECUCAO);
        assertThat(e.mecanicoExecucao().nome()).isEqualTo("Beto");
        assertThat(publicador.unica().type()).isEqualTo("ExecucaoIniciada");
    }

    @Test
    void concluirExecucaoBaixaAsPecasReservadasEPublicaOQueFoiConsumido() {
        ate(Etapa.EM_EXECUCAO);
        reservas.reservar(ORDEM, java.util.Map.of("a", 2, "b", 1), AGORA);
        publicador.limpar();

        Execucao e = servico.concluirExecucao(ORDEM, ANA);

        assertThat(e.etapa()).isEqualTo(Etapa.CONCLUIDA);
        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeEstoque()).isEqualTo(8);
        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeReservada()).isZero();
        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeDisponivel()).isEqualTo(8);
        assertThat(pecas.buscarPorId("b").orElseThrow().getQuantidadeEstoque()).isEqualTo(9);
        assertThat(reservas.buscarPorOrdemDeServico(ORDEM)).allMatch(r -> r.status() == ReservaPeca.Status.CONSUMIDA);
        var payload = publicador.payload(publicador.unica(), MensagensExecucao.ExecucaoConcluida.class);
        assertThat(publicador.unica().type()).isEqualTo("ExecucaoConcluida");
        assertThat(payload.pecasConsumidas()).containsExactlyInAnyOrder(
                new MensagensExecucao.ItemPeca("a", 2), new MensagensExecucao.ItemPeca("b", 1));
    }

    @Test
    void concluirSemPecasReservadasPublicaListaVazia() {
        ate(Etapa.EM_EXECUCAO);
        publicador.limpar();

        servico.concluirExecucao(ORDEM, ANA);

        assertThat(publicador.payload(publicador.unica(), MensagensExecucao.ExecucaoConcluida.class).pecasConsumidas()).isEmpty();
    }

    @Test
    void abortarPublicaExecucaoFalhouEDeixaAsReservasParaACompensacao() {
        ate(Etapa.EM_EXECUCAO);
        reservas.reservar(ORDEM, java.util.Map.of("a", 2), AGORA);
        publicador.limpar();

        Execucao e = servico.abortarExecucao(ORDEM, ANA, "Peca com defeito");

        assertThat(e.etapa()).isEqualTo(Etapa.ABORTADA);
        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeReservada()).as("quem libera e a Saga").isEqualTo(2);
        var payload = publicador.payload(publicador.unica(), MensagensExecucao.ExecucaoFalhou.class);
        assertThat(publicador.unica().type()).isEqualTo("ExecucaoFalhou");
        assertThat(payload.motivo()).isEqualTo("Peca com defeito");
        assertThat(payload.abortadaEm()).isEqualTo(AGORA);
    }

    @Test
    void acaoForaDaEtapaEhRecusadaSemPublicarNada() {
        publicador.limpar();

        assertThatThrownBy(() -> servico.concluirExecucao(ORDEM, ANA)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> servico.iniciarExecucao(ORDEM, ANA)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> servico.abortarExecucao(ORDEM, ANA, "x")).isInstanceOf(RegraDeNegocioException.class);
        assertThat(publicador.publicadas()).isEmpty();
    }

    @Test
    void osSemExecucaoResponde404() {
        UUID outra = UUID.randomUUID();

        assertThatThrownBy(() -> servico.buscar(outra)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> servico.iniciarDiagnostico(outra, ANA)).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void listaAsFilasEmOrdemDeChegadaEFiltraPorEtapa() {
        UUID segunda = UUID.randomUUID();
        execucoes.criar(Execucao.nova(segunda, SAGA, "OS-2", new Veiculo("DEF2G34", "Fiat", "Uno", null), List.of(), List.of(), AGORA.plusSeconds(5)));
        UUID terceira = UUID.randomUUID();
        execucoes.criar(Execucao.nova(terceira, SAGA, "OS-3", new Veiculo("GHI3J45", "Fiat", "Palio", null), List.of(), List.of(), AGORA.plusSeconds(1)));

        assertThat(servico.listar(Etapa.AGUARDANDO_DIAGNOSTICO)).extracting(Execucao::ordemServicoId).containsExactly(ORDEM, terceira, segunda);
        assertThat(servico.listar(null)).extracting(Execucao::ordemServicoId).containsExactly(ORDEM, terceira, segunda);
        assertThat(servico.listar(Etapa.EM_EXECUCAO)).isEmpty();

        servico.iniciarDiagnostico(terceira, ANA);
        servico.concluirDiagnostico(terceira, ANA, diagnostico());
        execucoes.atualizar(execucoes.buscarPorOrdemDeServico(terceira).orElseThrow().enfileirarExecucao(AGORA));
        assertThat(servico.listar(null)).extracting(Execucao::ordemServicoId).containsExactly(ORDEM, segunda, terceira);
    }
}
