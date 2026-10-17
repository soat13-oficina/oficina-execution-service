package br.com.oficina.execution.estoque.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.Filas;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import br.com.oficina.execution.estoque.infrastructure.mensageria.ManipuladorComandosEstoque;
import br.com.oficina.execution.support.mensageria.PublicadorMensagensEmMemoria;
import br.com.oficina.execution.support.persistence.PecaInsumoRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.ReservaPecaRepositoryEmMemoria;

class ReservasPelaSagaTest {

    private static final UUID SAGA = UUID.fromString("6f0e2b8a-1c3d-4e5f-8a9b-0c1d2e3f4a5b");
    private static final UUID ORDEM = UUID.fromString("3c9a1b2d-4e5f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID ORCAMENTO = UUID.fromString("9d2c4e6f-8a1b-4c3d-9e5f-7a6b5c4d3e2f");

    private PecaInsumoRepositoryEmMemoria pecas;
    private ReservaPecaRepositoryEmMemoria reservas;
    private PublicadorMensagensEmMemoria publicador;
    private ManipuladorComandosEstoque manipulador;

    @BeforeEach
    void preparar() {
        pecas = new PecaInsumoRepositoryEmMemoria();
        reservas = new ReservaPecaRepositoryEmMemoria(pecas);
        publicador = new PublicadorMensagensEmMemoria();
        EventosEstoque eventos = new EventosEstoque(publicador, PublicadorMensagensEmMemoria.fabrica());
        Clock relogio = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
        manipulador = new ManipuladorComandosEstoque(
                new ReservasPelaSaga(pecas, reservas, eventos, PublicadorMensagensEmMemoria.JSON, relogio));
    }

    private static Envelope comando(String tipo, Object payload) {
        return PublicadorMensagensEmMemoria.fabrica().novo(tipo, SAGA, ORDEM, payload);
    }

    private static Envelope reservar(MensagensEstoque.ItemPeca... itens) {
        return comando(MensagensEstoque.RESERVAR_PECAS, new MensagensEstoque.ReservarPecas(ORCAMENTO, List.of(itens)));
    }

    private static Envelope liberar() {
        return comando(MensagensEstoque.LIBERAR_RESERVA, new MensagensEstoque.LiberarReserva("PAGAMENTO_RECUSADO"));
    }

    @Test
    void manipuladorAtendeReservaELiberacao() {
        assertThat(manipulador.tipos()).containsExactlyInAnyOrder("ReservarPecas", "LiberarReserva");
    }

    @Test
    void reservaDebitaODisponivelERespondeComAsPecasReservadas() {
        pecas.semear("a", "Filtro", "10.00", 5);
        pecas.semear("b", "Pastilha", "20.00", 3);

        Envelope pedido = reservar(new MensagensEstoque.ItemPeca("b", 2), new MensagensEstoque.ItemPeca("a", 1));
        manipulador.tratar(pedido);

        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeDisponivel()).isEqualTo(4);
        assertThat(pecas.buscarPorId("b").orElseThrow().getQuantidadeDisponivel()).isEqualTo(1);
        assertThat(reservas.buscarPorOrdemDeServico(ORDEM)).hasSize(2).allMatch(ReservaPeca::ativa);

        Envelope resposta = publicador.unica();
        assertThat(publicador.publicadas().getFirst().fila()).isEqualTo(Filas.OS_SAGA_REPLIES);
        assertThat(resposta.type()).isEqualTo("PecasReservadas");
        assertThat(resposta.causationId()).isEqualTo(pedido.messageId());
        assertThat(resposta.sagaId()).isEqualTo(SAGA);
        assertThat(resposta.source()).isEqualTo("oficina-execution-service");
        var payload = publicador.payload(resposta, MensagensEstoque.PecasReservadas.class);
        assertThat(payload.orcamentoId()).isEqualTo(ORCAMENTO);
        assertThat(payload.itens()).containsExactly(new MensagensEstoque.ItemPeca("a", 1), new MensagensEstoque.ItemPeca("b", 2));
    }

    @Test
    void itensRepetidosDaMesmaPecaSaoSomados() {
        pecas.semear("a", "Filtro", "10.00", 5);

        manipulador.tratar(reservar(new MensagensEstoque.ItemPeca("a", 1), new MensagensEstoque.ItemPeca("a", 2)));

        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeReservada()).isEqualTo(3);
        assertThat(reservas.buscarPorOrdemDeServico(ORDEM)).singleElement().extracting(ReservaPeca::quantidade).isEqualTo(3);
    }

    @Test
    void estoqueInsuficienteNaoReservaNadaERespondeComAsFaltantes() {
        pecas.semear("a", "Filtro", "10.00", 5);
        pecas.semear("b", "Pastilha", "20.00", 1);

        manipulador.tratar(reservar(new MensagensEstoque.ItemPeca("a", 2), new MensagensEstoque.ItemPeca("b", 2)));

        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeReservada()).as("tudo ou nada").isZero();
        assertThat(reservas.buscarPorOrdemDeServico(ORDEM)).isEmpty();
        Envelope resposta = publicador.unica();
        assertThat(resposta.type()).isEqualTo("ReservaFalhou");
        var payload = publicador.payload(resposta, MensagensEstoque.ReservaFalhou.class);
        assertThat(payload.faltantes()).containsExactly(new MensagensEstoque.PecaFaltante("b", 2, 1));
    }

    @Test
    void pecaInexistenteContaComoFaltante() {
        manipulador.tratar(reservar(new MensagensEstoque.ItemPeca("fantasma", 1)));

        var payload = publicador.payload(publicador.unica(), MensagensEstoque.ReservaFalhou.class);
        assertThat(payload.faltantes()).containsExactly(new MensagensEstoque.PecaFaltante("fantasma", 1, 0));
    }

    @Test
    void comandoReenviadoPorTimeoutSoRepeteARespostaSemReservarDeNovo() {
        pecas.semear("a", "Filtro", "10.00", 5);
        manipulador.tratar(reservar(new MensagensEstoque.ItemPeca("a", 2)));

        manipulador.tratar(reservar(new MensagensEstoque.ItemPeca("a", 2))); // messageId novo, mesma OS

        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeReservada()).isEqualTo(2);
        assertThat(publicador.tipos()).containsExactly("PecasReservadas", "PecasReservadas");
    }

    @Test
    void listaVaziaConfirmaSemReservar() {
        manipulador.tratar(reservar());

        assertThat(publicador.tipos()).containsExactly("PecasReservadas");
        assertThat(publicador.payload(publicador.unica(), MensagensEstoque.PecasReservadas.class).itens()).isEmpty();
        assertThat(reservas.buscarPorOrdemDeServico(ORDEM)).isEmpty();
    }

    @Test
    void liberarDevolveAoDisponivelEConfirma() {
        pecas.semear("a", "Filtro", "10.00", 5);
        manipulador.tratar(reservar(new MensagensEstoque.ItemPeca("a", 2)));

        manipulador.tratar(liberar());

        PecaInsumo peca = pecas.buscarPorId("a").orElseThrow();
        assertThat(peca.getQuantidadeReservada()).isZero();
        assertThat(peca.getQuantidadeDisponivel()).isEqualTo(5);
        assertThat(reservas.buscarPorOrdemDeServico(ORDEM)).singleElement().extracting(ReservaPeca::status).isEqualTo(ReservaPeca.Status.LIBERADA);
        Envelope resposta = publicador.publicadas().getLast().envelope();
        assertThat(resposta.type()).isEqualTo("ReservaLiberada");
        assertThat(publicador.payload(resposta, MensagensEstoque.ReservaLiberada.class).itens())
                .containsExactly(new MensagensEstoque.ItemPeca("a", 2));
    }

    @Test
    void liberarSemReservaConfirmaComListaVazia() {
        manipulador.tratar(liberar());

        assertThat(publicador.unica().type()).isEqualTo("ReservaLiberada");
        assertThat(publicador.payload(publicador.unica(), MensagensEstoque.ReservaLiberada.class).itens()).isEmpty();
    }

    @Test
    void liberarDuasVezesNaoDevolveEmDobro() {
        pecas.semear("a", "Filtro", "10.00", 5);
        manipulador.tratar(reservar(new MensagensEstoque.ItemPeca("a", 2)));
        manipulador.tratar(liberar());

        manipulador.tratar(liberar());

        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeDisponivel()).isEqualTo(5);
    }

    @Test
    void podeReservarDeNovoDepoisDeLiberar() {
        pecas.semear("a", "Filtro", "10.00", 5);
        manipulador.tratar(reservar(new MensagensEstoque.ItemPeca("a", 2)));
        manipulador.tratar(liberar());

        manipulador.tratar(reservar(new MensagensEstoque.ItemPeca("a", 3)));

        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeReservada()).isEqualTo(3);
        assertThat(publicador.tipos()).containsExactly("PecasReservadas", "ReservaLiberada", "PecasReservadas");
    }
}
