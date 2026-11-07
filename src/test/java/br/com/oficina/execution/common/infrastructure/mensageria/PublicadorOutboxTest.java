package br.com.oficina.execution.common.infrastructure.mensageria;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.mensageria.TransporteMensagens.MensagemSaida;
import br.com.oficina.execution.support.dynamodb.DynamoDbClientFalso;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

class PublicadorOutboxTest {

    private static final PropriedadesDynamo PROPRIEDADES = new PropriedadesDynamo("hml", null, null);

    private DynamoDbClientFalso dynamo;
    private SimpleMeterRegistry metricas;
    private final List<MensagemSaida> enviadas = new ArrayList<>();
    private Predicate<MensagemSaida> falhaQuando = m -> false;

    @BeforeEach
    void preparar() {
        dynamo = new DynamoDbClientFalso();
        metricas = new SimpleMeterRegistry();
    }

    private PublicadorOutbox publicador(int maxTentativas) {
        TransporteMensagens transporte = mensagem -> {
            if (falhaQuando.test(mensagem)) {
                throw new IllegalStateException("fila fora");
            }
            enviadas.add(mensagem);
        };
        return new PublicadorOutbox(new OutboxDynamo(dynamo, PROPRIEDADES), transporte,
                new TravaPublicacaoOutbox(dynamo, PROPRIEDADES), metricas, maxTentativas, 100);
    }

    private static Map<String, AttributeValue> pendente(String id, String grupo, int tentativas) {
        return Map.of(
                "pk", Atributos.texto("OUTBOX#" + id),
                "fila", Atributos.texto("os-saga-replies"),
                "grupo", Atributos.texto(grupo),
                "tipo", Atributos.texto("PecasReservadas"),
                "corpo", Atributos.texto("{\"id\":\"" + id + "\"}"),
                "tentativas", Atributos.numero(tentativas));
    }

    private List<String> atualizacoes() {
        return dynamo.atualizacoes.stream().map(UpdateItemRequest::updateExpression).toList();
    }

    @Test
    void enviaAsPendentesNaOrdemEMarcaCadaUmaComoPublicada() {
        dynamo.itensConsultados = List.of(pendente("m-1", "os-1", 0), pendente("m-2", "os-2", 0), pendente("m-3", "os-1", 0));

        int entregues = publicador(5).publicarPendentes();

        assertThat(entregues).isEqualTo(3);
        assertThat(enviadas).extracting(MensagemSaida::messageId).containsExactly("m-1", "m-2", "m-3");
        assertThat(enviadas.getFirst()).isEqualTo(new MensagemSaida("os-saga-replies", "os-1", "m-1", "PecasReservadas", "{\"id\":\"m-1\"}"));
        assertThat(atualizacoes()).containsOnly("SET publicadaEm = :agora REMOVE pendente").hasSize(3);
        assertThat(metricas.counter("oficina.mensageria.publicadas", "fila", "os-saga-replies", "tipo", "PecasReservadas").count()).isEqualTo(3.0);
    }

    @Test
    void falhaSeguraAsSeguintesDaMesmaOsMasNaoAsDeOutras() {
        dynamo.itensConsultados = List.of(pendente("m-1", "os-1", 0), pendente("m-2", "os-2", 0), pendente("m-3", "os-1", 0));
        falhaQuando = m -> m.messageId().equals("m-1");

        int entregues = publicador(5).publicarPendentes();

        assertThat(entregues).isEqualTo(1);
        assertThat(enviadas).extracting(MensagemSaida::messageId).containsExactly("m-2");
        assertThat(atualizacoes()).containsExactly("ADD tentativas :um SET ultimoErro = :erro", "SET publicadaEm = :agora REMOVE pendente");
        assertThat(metricas.counter("oficina.mensageria.falhas", "fila", "os-saga-replies", "tipo", "PecasReservadas").count()).isEqualTo(1.0);
    }

    @Test
    void naUltimaTentativaAMensagemSaiDoIndice() {
        dynamo.itensConsultados = List.of(pendente("m-1", "os-1", 4));
        falhaQuando = m -> true;

        assertThat(publicador(5).publicarPendentes()).isZero();

        assertThat(atualizacoes()).singleElement().asString().contains("REMOVE pendente").contains("esgotada");
    }

    @Test
    void semAConcessaoNaoLeNemEnvia() {
        dynamo.falhaNaGravacao = () -> ConditionalCheckFailedException.builder().message("outra replica").build();

        assertThat(publicador(5).publicarPendentes()).isZero();

        assertThat(dynamo.consultas).isEmpty();
        assertThat(enviadas).isEmpty();
    }

    @Test
    void agendadorEngoleFalhaDoCicloEProsseguePeloProximo() {
        dynamo.falhaNaGravacao = () -> new IllegalStateException("DynamoDB fora");
        AgendadorPublicacaoOutbox agendador = new AgendadorPublicacaoOutbox(publicador(5));

        agendador.publicar();
        agendador.publicar();

        assertThat(dynamo.gravacoes).as("tentou os dois ciclos").hasSize(2);
    }

    @Test
    void agendadorDisparaOPublicador() {
        dynamo.itensConsultados = List.of(pendente("m-1", "os-1", 0));

        new AgendadorPublicacaoOutbox(publicador(5)).publicar();

        assertThat(enviadas).hasSize(1);
    }

    @Test
    void transporteIndisponivelRecusaEFicaPendente() {
        dynamo.itensConsultados = List.of(pendente("m-1", "os-1", 0));
        PublicadorOutbox publicador = new PublicadorOutbox(new OutboxDynamo(dynamo, PROPRIEDADES), new TransporteIndisponivel(),
                new TravaPublicacaoOutbox(dynamo, PROPRIEDADES), metricas, 5, 100);

        assertThat(publicador.publicarPendentes()).isZero();
        assertThat(atualizacoes()).singleElement().isEqualTo("ADD tentativas :um SET ultimoErro = :erro");
    }
}
