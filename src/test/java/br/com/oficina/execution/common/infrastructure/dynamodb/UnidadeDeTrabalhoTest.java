package br.com.oficina.execution.common.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.support.dynamodb.DynamoDbClientFalso;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class UnidadeDeTrabalhoTest {

    private DynamoDbClientFalso dynamo;
    private UnidadeDeTrabalho unidade;

    @BeforeEach
    void preparar() {
        dynamo = new DynamoDbClientFalso();
        unidade = new UnidadeDeTrabalho(dynamo);
    }

    private static TransactWriteItem item(String pk) {
        return TransactWriteItem.builder().put(Put.builder().tableName("t").item(Map.of("pk", Atributos.texto(pk))).build()).build();
    }

    @Test
    void confirmaTodosOsItensDeUmaVezNoFimDoBloco() {
        String resultado = unidade.executar(() -> {
            unidade.adicionar("a", item("a"));
            unidade.adicionar("b", item("b"));
            assertThat(dynamo.transacoes).as("nada grava antes do fim").isEmpty();
            assertThat(unidade.ativa()).isTrue();
            return "ok";
        });

        assertThat(resultado).isEqualTo("ok");
        assertThat(dynamo.transacoes).singleElement().satisfies(t -> assertThat(t.transactItems()).hasSize(2));
        assertThat(unidade.ativa()).isFalse();
    }

    @Test
    void blocoSemEscritaNaoChamaODynamo() {
        unidade.executar(() -> {
        });

        assertThat(dynamo.transacoes).isEmpty();
    }

    @Test
    void blocoAninhadoJuntaSeAoDeFora() {
        unidade.executar(() -> {
            unidade.adicionar("a", item("a"));
            unidade.executar(() -> unidade.adicionar("b", item("b")));
            assertThat(dynamo.transacoes).as("quem abriu e que confirma").isEmpty();
        });

        assertThat(dynamo.transacoes).singleElement().satisfies(t -> assertThat(t.transactItems()).hasSize(2));
    }

    @Test
    void excecaoNoBlocoDescartaOLote() {
        assertThatThrownBy(() -> unidade.executar(() -> {
            unidade.adicionar("a", item("a"));
            throw new IllegalStateException("falhou no meio");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(dynamo.transacoes).isEmpty();
        assertThat(unidade.ativa()).isFalse();
    }

    @Test
    void escreverForaDeUmBlocoFalhaAlto() {
        assertThatThrownBy(() -> unidade.adicionar("a", item("a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fora de uma unidade de trabalho");
    }

    @Test
    void transacaoCancelada_informaOsRotulosCujaCondicaoFalhou() {
        dynamo.falhaNaTransacao = () -> TransactionCanceledException.builder()
                .message("cancelada")
                .cancellationReasons(
                        CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("ConditionalCheckFailed").build(),
                        CancellationReason.builder().code("TransactionConflict").build())
                .build();

        assertThatThrownBy(() -> unidade.executar(() -> {
            unidade.adicionar("peca#a", item("a"));
            unidade.adicionar("idempotencia", item("b"));
            unidade.adicionar("outbox#1", item("c"));
        })).isInstanceOfSatisfying(EscritaRecusadaException.class, e -> {
            assertThat(e.rotulos()).containsExactly("idempotencia");
            assertThat(e.recusou("idempot")).isTrue();
            assertThat(e.recusou("peca#")).isFalse();
            assertThat(e.getCause()).isInstanceOf(TransactionCanceledException.class);
        });
        assertThat(unidade.ativa()).isFalse();
    }

    @Test
    void naoPassaDoLimiteDeCemItens() {
        assertThatThrownBy(() -> unidade.executar(() -> {
            for (int i = 0; i <= UnidadeDeTrabalho.LIMITE_ITENS; i++) {
                unidade.adicionar("i" + i, item("i" + i));
            }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("101 itens");

        assertThat(dynamo.transacoes).isEmpty();
    }

    @Test
    void excecaoDeRecusaGuardaSoOsRotulosCopiados() {
        EscritaRecusadaException e = new EscritaRecusadaException(List.of("a"), new RuntimeException());

        assertThat(e.rotulos()).containsExactly("a");
        assertThat(e.getMessage()).contains("[a]");
    }
}
