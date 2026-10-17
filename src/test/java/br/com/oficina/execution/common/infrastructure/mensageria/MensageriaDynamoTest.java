package br.com.oficina.execution.common.infrastructure.mensageria;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.Filas;
import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.UnidadeDeTrabalho;
import br.com.oficina.execution.common.infrastructure.mensageria.OutboxDynamo.Pendente;
import br.com.oficina.execution.support.dynamodb.DynamoDbClientFalso;
import br.com.oficina.execution.support.mensageria.PublicadorMensagensEmMemoria;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** Itens de controle (idempotencia, outbox e concessao) montados para o DynamoDB: o que cada um grava e le. */
class MensageriaDynamoTest {

    private static final Instant AGORA = Instant.parse("2026-10-17T12:00:00Z");
    private static final Clock RELOGIO = Clock.fixed(AGORA, ZoneOffset.UTC);
    private static final PropriedadesDynamo PROPRIEDADES = new PropriedadesDynamo("hml", null, null);
    private static final String TABELA = "oficina-hml-execution-controle";

    private DynamoDbClientFalso dynamo;

    @BeforeEach
    void preparar() {
        dynamo = new DynamoDbClientFalso();
    }

    @Test
    void propriedadesTemPadroesEMontamONomeDaTabela() {
        PropriedadesDynamo padrao = new PropriedadesDynamo(" ", null, "");

        assertThat(padrao.ambiente()).isEqualTo("local");
        assertThat(padrao.regiao()).isEqualTo("us-east-1");
        assertThat(PROPRIEDADES.tabela("pecas")).isEqualTo("oficina-hml-pecas");
    }

    @Nested
    class Idempotencia {

        private ControleDeMensagens controle;

        @BeforeEach
        void preparar() {
            controle = new ControleDeMensagens(dynamo, PROPRIEDADES, RELOGIO);
        }

        @Test
        void mensagemJaProcessadaELidaComConsistenciaForte() {
            UUID id = UUID.randomUUID();
            dynamo.itemLido = Map.of("pk", Atributos.texto("MSG#" + id));

            assertThat(controle.jaProcessada(id)).isTrue();

            assertThat(dynamo.leituras).singleElement().satisfies(r -> {
                assertThat(r.tableName()).isEqualTo(TABELA);
                assertThat(r.key().get("pk").s()).isEqualTo("MSG#" + id);
                assertThat(r.consistentRead()).isTrue();
            });
        }

        @Test
        void mensagemNovaNaoTemItem() {
            assertThat(controle.jaProcessada(UUID.randomUUID())).isFalse();
        }

        @Test
        void registroEUmPutCondicionalComTtlDeSeteDias() {
            Envelope envelope = PublicadorMensagensEmMemoria.fabrica().novo("ReservarPecas", null, UUID.randomUUID(), Map.of());

            var put = controle.registrar(envelope).put();

            assertThat(put.tableName()).isEqualTo(TABELA);
            assertThat(put.conditionExpression()).isEqualTo("attribute_not_exists(pk)");
            assertThat(put.item().get("pk").s()).isEqualTo("MSG#" + envelope.messageId());
            assertThat(put.item().get("tipo").s()).isEqualTo("ReservarPecas");
            assertThat(put.item().get("expiraEm").n()).isEqualTo(Long.toString(AGORA.plusSeconds(7 * 24 * 3600).getEpochSecond()));
        }
    }

    @Nested
    class Outbox {

        private OutboxDynamo outbox;

        @BeforeEach
        void preparar() {
            outbox = new OutboxDynamo(dynamo, PROPRIEDADES, RELOGIO);
        }

        private Map<String, AttributeValue> pendente(String id, String grupo, int tentativas) {
            return Map.of(
                    "pk", Atributos.texto("OUTBOX#" + id),
                    "fila", Atributos.texto(Filas.OS_SAGA_REPLIES),
                    "grupo", Atributos.texto(grupo),
                    "tipo", Atributos.texto("PecasReservadas"),
                    "corpo", Atributos.texto("{}"),
                    "tentativas", Atributos.numero(tentativas));
        }

        @Test
        void itemEntraNoIndiceDePendentesComOrdemTextualCronologica() {
            var primeiro = outbox.item(Filas.OS_SAGA_REPLIES, "m-1", "os-1", "PecasReservadas", "{\"a\":1}").put();
            var segundo = outbox.item(Filas.OS_SAGA_REPLIES, "m-2", "os-1", "ReservaLiberada", "{\"a\":2}").put();

            assertThat(primeiro.tableName()).isEqualTo(TABELA);
            assertThat(primeiro.conditionExpression()).isEqualTo("attribute_not_exists(pk)");
            assertThat(primeiro.item().get("pendente").s()).isEqualTo("1");
            assertThat(primeiro.item().get("tentativas").n()).isEqualTo("0");
            assertThat(primeiro.item().get("pk").s()).isEqualTo("OUTBOX#m-1");
            assertThat(primeiro.item().get("criadoEm").s()).startsWith("2026-10-17T12:00:00.000000Z-");
            assertThat(segundo.item().get("criadoEm").s()).isGreaterThan(primeiro.item().get("criadoEm").s());
        }

        @Test
        void pendentesVemDoIndiceEsparsoEmOrdemCrescente() {
            dynamo.itensConsultados = List.of(pendente("m-1", "os-1", 0), pendente("m-2", "os-2", 3));

            List<Pendente> pendentes = outbox.pendentes(50);

            assertThat(pendentes).extracting(Pendente::messageId).containsExactly("m-1", "m-2");
            assertThat(pendentes.get(1).tentativas()).isEqualTo(3);
            assertThat(dynamo.consultas).singleElement().satisfies(q -> {
                assertThat(q.indexName()).isEqualTo("outbox-pendente");
                assertThat(q.scanIndexForward()).isTrue();
                assertThat(q.limit()).isEqualTo(50);
            });
        }

        @Test
        void pendenteSemContadorComecaDoZero() {
            dynamo.itensConsultados = List.of(Map.of(
                    "pk", Atributos.texto("OUTBOX#m-1"), "fila", Atributos.texto("f"), "grupo", Atributos.texto("g"),
                    "tipo", Atributos.texto("t"), "corpo", Atributos.texto("{}")));

            assertThat(outbox.pendentes(10)).singleElement().extracting(Pendente::tentativas).isEqualTo(0);
        }

        @Test
        void publicadaSaiDoIndice() {
            outbox.marcarPublicada("OUTBOX#m-1");

            assertThat(dynamo.atualizacoes).singleElement().satisfies(u -> {
                assertThat(u.updateExpression()).isEqualTo("SET publicadaEm = :agora REMOVE pendente");
                assertThat(u.key().get("pk").s()).isEqualTo("OUTBOX#m-1");
                assertThat(u.expressionAttributeValues().get(":agora").s()).isEqualTo(AGORA.toString());
            });
        }

        @Test
        void falhaContaATentativaEGuardaOErro() {
            outbox.registrarFalha("OUTBOX#m-1", "x".repeat(600), false);

            assertThat(dynamo.atualizacoes).singleElement().satisfies(u -> {
                assertThat(u.updateExpression()).isEqualTo("ADD tentativas :um SET ultimoErro = :erro");
                assertThat(u.expressionAttributeValues().get(":erro").s()).hasSize(500);
            });
        }

        @Test
        void tentativasEsgotadasTiramOItemDoIndice() {
            outbox.registrarFalha("OUTBOX#m-1", "boom", true);

            assertThat(dynamo.atualizacoes).singleElement().satisfies(u -> {
                assertThat(u.updateExpression()).contains("REMOVE pendente").contains("esgotada = :verdadeiro");
                assertThat(u.expressionAttributeValues().get(":verdadeiro").bool()).isTrue();
            });
        }

        @Test
        void publicadorGravaOEnvelopeNaUnidadeDeTrabalho() {
            UnidadeDeTrabalho unidade = new UnidadeDeTrabalho(dynamo);
            PublicadorMensagensOutbox publicador = new PublicadorMensagensOutbox(unidade, outbox, PublicadorMensagensEmMemoria.JSON);
            UUID ordem = UUID.randomUUID();
            Envelope envelope = PublicadorMensagensEmMemoria.fabrica().novo("PecasReservadas", null, ordem, Map.of("x", 1));

            unidade.executar(() -> publicador.publicar(Filas.OS_SAGA_REPLIES, envelope));

            var put = dynamo.transacoes.getFirst().transactItems().getFirst().put();
            assertThat(put.item().get("pk").s()).isEqualTo("OUTBOX#" + envelope.messageId());
            assertThat(put.item().get("fila").s()).isEqualTo(Filas.OS_SAGA_REPLIES);
            assertThat(put.item().get("grupo").s()).isEqualTo(ordem.toString());
            assertThat(put.item().get("corpo").s()).contains("\"type\":\"PecasReservadas\"");
        }
    }

    @Nested
    class Concessao {

        @Test
        void quemAdquireGravaOLeaseComPrazo() {
            TravaPublicacaoOutbox trava = new TravaPublicacaoOutbox(dynamo, PROPRIEDADES, RELOGIO);

            assertThat(trava.tentarAdquirir()).isTrue();

            assertThat(dynamo.gravacoes).singleElement().satisfies(p -> {
                assertThat(p.tableName()).isEqualTo(TABELA);
                assertThat(p.item().get("pk").s()).isEqualTo("LOCK#outbox");
                assertThat(p.item().get("venceEm").n()).isEqualTo(Long.toString(AGORA.toEpochMilli() + 15_000));
                assertThat(p.conditionExpression()).isEqualTo("attribute_not_exists(pk) OR venceEm < :agora OR titular = :titular");
                assertThat(p.expressionAttributeValues().get(":titular").s()).isEqualTo(p.item().get("titular").s());
            });
        }

        @Test
        void concessaoDeOutraReplicaImpedeAPublicacao() {
            dynamo.falhaNaGravacao = () -> ConditionalCheckFailedException.builder().message("outra replica").build();

            assertThat(new TravaPublicacaoOutbox(dynamo, PROPRIEDADES, RELOGIO).tentarAdquirir()).isFalse();
        }
    }
}
