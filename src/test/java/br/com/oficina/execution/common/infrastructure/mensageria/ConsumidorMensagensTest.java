package br.com.oficina.execution.common.infrastructure.mensageria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.FabricaEnvelopes;
import br.com.oficina.execution.common.application.mensageria.ManipuladorMensagem;
import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.EscritaRecusadaException;
import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.UnidadeDeTrabalho;
import br.com.oficina.execution.support.dynamodb.DynamoDbClientFalso;
import br.com.oficina.execution.support.mensageria.PublicadorMensagensEmMemoria;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class ConsumidorMensagensTest {

    private static final PropriedadesDynamo PROPRIEDADES = new PropriedadesDynamo("hml", null, null);

    private DynamoDbClientFalso dynamo;
    private UnidadeDeTrabalho unidade;
    private SimpleMeterRegistry metricas;
    private ManipuladorDeTeste manipulador;
    private ConsumidorMensagens consumidor;

    /** Escreve um item no lote, como o estoque faz, e anota o que viu. */
    private class ManipuladorDeTeste implements ManipuladorMensagem {
        final List<Envelope> tratadas = new ArrayList<>();
        boolean unidadeAtiva;
        String correlacaoNoMdc;
        RuntimeException falha;

        @Override
        public Set<String> tipos() {
            return Set.of("ReservarPecas");
        }

        @Override
        public void tratar(Envelope envelope) {
            tratadas.add(envelope);
            unidadeAtiva = unidade.ativa();
            correlacaoNoMdc = MDC.get(FabricaEnvelopes.MDC_CORRELATION_ID);
            if (falha != null) {
                throw falha;
            }
            unidade.adicionar("peca#a", TransactWriteItem.builder()
                    .put(Put.builder().tableName("t").item(Map.of("pk", Atributos.texto("a"))).build()).build());
        }
    }

    @BeforeEach
    void preparar() {
        dynamo = new DynamoDbClientFalso();
        unidade = new UnidadeDeTrabalho(dynamo);
        metricas = new SimpleMeterRegistry();
        manipulador = new ManipuladorDeTeste();
        consumidor = consumidor(manipulador);
    }

    private ConsumidorMensagens consumidor(ManipuladorMensagem... manipuladores) {
        DefaultListableBeanFactory fabrica = new DefaultListableBeanFactory();
        for (int i = 0; i < manipuladores.length; i++) {
            fabrica.registerSingleton("manipulador" + i, manipuladores[i]);
        }
        return new ConsumidorMensagens(fabrica.getBeanProvider(ManipuladorMensagem.class),
                new ControleDeMensagens(dynamo, PROPRIEDADES), unidade, PublicadorMensagensEmMemoria.JSON, metricas);
    }

    private static Envelope comando() {
        return PublicadorMensagensEmMemoria.fabrica().novo("ReservarPecas", UUID.randomUUID(), UUID.randomUUID(), Map.of("itens", List.of()));
    }

    private static String corpo(Envelope envelope) {
        return PublicadorMensagensEmMemoria.JSON.writeValueAsString(envelope);
    }

    private double contagem(String resultado) {
        return metricas.counter("oficina.mensageria.consumidas", "tipo", "ReservarPecas", "resultado", resultado).count();
    }

    @Test
    void mensagemNovaEhTratadaNumaUnicaTransacaoComOItemDeIdempotencia() {
        Envelope envelope = comando();

        consumidor.consumir(corpo(envelope));

        assertThat(manipulador.tratadas).containsExactly(envelope);
        assertThat(manipulador.unidadeAtiva).isTrue();
        assertThat(manipulador.correlacaoNoMdc).isEqualTo(envelope.correlationId());
        assertThat(dynamo.transacoes).singleElement().satisfies(t -> {
            assertThat(t.transactItems()).hasSize(2);
            assertThat(t.transactItems().get(1).put().item().get("pk").s()).isEqualTo("MSG#" + envelope.messageId());
        });
        assertThat(contagem("processada")).isEqualTo(1.0);
        assertThat(MDC.get(FabricaEnvelopes.MDC_CORRELATION_ID)).as("MDC restaurado").isNull();
    }

    @Test
    void mensagemJaProcessadaEhDescartadaSemTratar() {
        Envelope envelope = comando();
        dynamo.itemLido = Map.of("pk", Atributos.texto("MSG#" + envelope.messageId()));

        consumidor.consumir(corpo(envelope));

        assertThat(manipulador.tratadas).isEmpty();
        assertThat(dynamo.transacoes).isEmpty();
        assertThat(contagem("duplicada")).isEqualTo(1.0);
    }

    @Test
    void entregaEmParaleloPerdeACorridaEViraDuplicada() {
        dynamo.falhaNaTransacao = () -> TransactionCanceledException.builder().message("cancelada")
                .cancellationReasons(CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("ConditionalCheckFailed").build())
                .build();

        consumidor.consumir(corpo(comando()));

        assertThat(contagem("duplicada")).isEqualTo(1.0);
        assertThat(contagem("processada")).isZero();
    }

    @Test
    void escritaConcorrenteNaPecaDevolveAMensagemAFila() {
        dynamo.falhaNaTransacao = () -> TransactionCanceledException.builder().message("cancelada")
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build(),
                        CancellationReason.builder().code("None").build())
                .build();

        assertThatThrownBy(() -> consumidor.consumir(corpo(comando()))).isInstanceOf(EscritaRecusadaException.class);
        assertThat(contagem("processada")).isZero();
    }

    @Test
    void falhaDoManipuladorNaoGravaNadaEPropaga() {
        manipulador.falha = new IllegalStateException("estoque fora");

        assertThatThrownBy(() -> consumidor.consumir(corpo(comando()))).hasMessage("estoque fora");

        assertThat(dynamo.transacoes).isEmpty();
        assertThat(MDC.get(FabricaEnvelopes.MDC_CORRELATION_ID)).isNull();
    }

    @Test
    void mdcAnteriorEhRestauradoDepoisDeTratar() {
        MDC.put("outra", "valor");
        try {
            consumidor.consumir(corpo(comando()));

            assertThat(MDC.get("outra")).isEqualTo("valor");
            assertThat(MDC.get(FabricaEnvelopes.MDC_CORRELATION_ID)).isNull();
        } finally {
            MDC.clear();
        }
    }

    @Test
    void envelopeSemSagaNaoPoeSagaNoMdc() {
        Envelope semSaga = PublicadorMensagensEmMemoria.fabrica().novo("ReservarPecas", null, UUID.randomUUID(), Map.of());

        consumidor.consumir(corpo(semSaga));

        assertThat(manipulador.tratadas).hasSize(1);
    }

    @Test
    void mensagemForaDoContratoEhInvalida() {
        assertThatThrownBy(() -> consumidor.consumir("{nao e json"))
                .isInstanceOf(MensagemInvalidaException.class)
                .hasMessageContaining("fora do contrato");
        assertThatThrownBy(() -> consumidor.consumir("{\"type\":\"ReservarPecas\"}"))
                .isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    void tipoSemManipuladorEhInvalido() {
        Envelope desconhecida = PublicadorMensagensEmMemoria.fabrica().novo("TipoQueNinguemTrata", null, UUID.randomUUID(), Map.of());

        assertThatThrownBy(() -> consumidor.consumir(corpo(desconhecida)))
                .isInstanceOf(MensagemInvalidaException.class)
                .hasMessageContaining("TipoQueNinguemTrata");
    }

    @Test
    void doisManipuladoresParaOMesmoTipoImpedemAInicializacao() {
        assertThatThrownBy(() -> consumidor(manipulador, new ManipuladorDeTeste()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dois manipuladores para o tipo ReservarPecas");
    }
}
