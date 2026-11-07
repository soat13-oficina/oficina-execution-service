package br.com.oficina.execution.estoque.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException;
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.UnidadeDeTrabalho;
import br.com.oficina.execution.estoque.domain.model.CategoriaPeca;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import br.com.oficina.execution.support.dynamodb.DynamoDbClientFalso;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Os adapters do DynamoDB contra o duplo do cliente: confere as requisicoes que eles montam e a
 * traducao das recusas do banco em erros de dominio. A semantica das expressoes (o que o DynamoDB
 * aceita e recusa) fica com {@code EstoqueDynamoLocalStackTest}.
 */
class PersistenciaEstoqueDynamoTest {

    private static final PropriedadesDynamo PROPRIEDADES = new PropriedadesDynamo("hml", null, null);
    private static final Instant AGORA = Instant.parse("2026-10-17T12:00:00Z");

    private DynamoDbClientFalso dynamo;

    @BeforeEach
    void preparar() {
        dynamo = new DynamoDbClientFalso();
    }

    private static Map<String, AttributeValue> itemDaPeca(String id, int estoque, int reservada, long versao) {
        return Map.ofEntries(
                Map.entry("pecaId", Atributos.texto(id)),
                Map.entry("descricao", Atributos.texto("Filtro")),
                Map.entry("marca", Atributos.texto("Bosch")),
                Map.entry("preco", AttributeValue.builder().n("45.90").build()),
                Map.entry("quantidadeEstoque", Atributos.numero(estoque)),
                Map.entry("quantidadeReservada", Atributos.numero(reservada)),
                Map.entry("quantidadeDisponivel", Atributos.numero(estoque - reservada)),
                Map.entry("codigoReferencia", Atributos.texto("REF")),
                Map.entry("categoria", Atributos.texto("FILTROS")),
                Map.entry("versao", Atributos.numero(versao)));
    }

    private static PecaInsumo peca(String id, Long versao) {
        return new PecaInsumo(id, "Filtro", "Bosch", new BigDecimal("45.90"), 10, 2, "REF", CategoriaPeca.FILTROS, versao);
    }

    @Nested
    class Pecas {

        private DynamoPecaInsumoRepository repositorio;

        @BeforeEach
        void preparar() {
            repositorio = new DynamoPecaInsumoRepository(
                    DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build(), dynamo, PROPRIEDADES);
        }

        @Test
        void criarGravaOItemComODisponivelCalculadoEExigeQueNaoExista() {
            repositorio.criar(peca("p-1", null));

            assertThat(dynamo.gravacoes).singleElement().satisfies(p -> {
                assertThat(p.tableName()).isEqualTo("oficina-hml-pecas");
                assertThat(p.item().get("pecaId").s()).isEqualTo("p-1");
                assertThat(p.item().get("quantidadeDisponivel").n()).isEqualTo("8");
                assertThat(p.item().get("categoria").s()).isEqualTo("FILTROS");
                assertThat(p.conditionExpression()).contains("attribute_not_exists");
            });
        }

        @Test
        void criarComIdRepetidoViraConflito() {
            dynamo.falhaNaGravacao = () -> ConditionalCheckFailedException.builder().message("existe").build();

            assertThatThrownBy(() -> repositorio.criar(peca("p-1", null))).isInstanceOf(ConflitoDeRecursoException.class);
        }

        @Test
        void substituirCondicionaAEscritaAVersaoLida() {
            repositorio.substituir(peca("p-1", 3L));

            assertThat(dynamo.gravacoes).singleElement().satisfies(p -> {
                assertThat(p.conditionExpression()).contains("versao");
                assertThat(p.item().get("versao").n()).isEqualTo("4");
            });
        }

        @Test
        void substituirDePecaApagadaResponde404() {
            dynamo.falhaNaGravacao = () -> ConditionalCheckFailedException.builder().message("versao").build();

            assertThatThrownBy(() -> repositorio.substituir(peca("p-1", 3L))).isInstanceOf(RecursoNaoEncontradoException.class);
        }

        @Test
        void substituirComVersaoDesatualizadaViraConflito() {
            dynamo.falhaNaGravacao = () -> ConditionalCheckFailedException.builder().message("versao").build();
            dynamo.itemLido = itemDaPeca("p-1", 10, 2, 9);

            assertThatThrownBy(() -> repositorio.substituir(peca("p-1", 3L)))
                    .isInstanceOf(ConflitoDeRecursoException.class)
                    .hasMessageContaining("outra requisição");
        }

        @Test
        void buscaPorIdComConsistenciaForte() {
            dynamo.itemLido = itemDaPeca("p-1", 10, 2, 5);

            PecaInsumo achada = repositorio.buscarPorId("p-1").orElseThrow();

            assertThat(achada.getId()).isEqualTo("p-1");
            assertThat(achada.getQuantidadeDisponivel()).isEqualTo(8);
            assertThat(achada.getPreco()).isEqualByComparingTo("45.90");
            assertThat(achada.getCategoria()).isEqualTo(CategoriaPeca.FILTROS);
            assertThat(achada.getVersao()).isEqualTo(5L);
            assertThat(dynamo.leituras).singleElement().satisfies(r -> {
                assertThat(r.tableName()).isEqualTo("oficina-hml-pecas");
                assertThat(r.consistentRead()).isTrue();
            });
        }

        @Test
        void buscaDeIdInexistenteVeioVazia() {
            assertThat(repositorio.buscarPorId("nao-existe")).isEmpty();
        }

        @Test
        void listaVarrendoATabelaComConsistenciaForte() {
            dynamo.itensVarridos = List.of(itemDaPeca("a", 5, 0, 1), itemDaPeca("b", 3, 1, 2));

            assertThat(repositorio.buscarTodos()).extracting(PecaInsumo::getId).containsExactly("a", "b");
            assertThat(dynamo.varreduras).singleElement().satisfies(s -> assertThat(s.consistentRead()).isTrue());
        }

        @Test
        void excluirExigeQueNaoHajaReserva() {
            repositorio.excluirPorId("p-1");

            assertThat(dynamo.exclusoes).singleElement().satisfies(d -> {
                assertThat(d.key().get("pecaId").s()).isEqualTo("p-1");
                assertThat(d.conditionExpression()).isEqualTo("quantidadeReservada = :zero");
            });
        }

        @Test
        void excluirPecaReservadaNoMeioDoCaminhoEhRecusado() {
            dynamo.falhaNaExclusao = () -> ConditionalCheckFailedException.builder().message("reservada").build();

            assertThatThrownBy(() -> repositorio.excluirPorId("p-1"))
                    .isInstanceOf(RegraDeNegocioException.class)
                    .hasMessageContaining("reserva ativa");
        }

        @Test
        void entradaDeEstoqueEUmaAtualizacaoAtomica() {
            repositorio.adicionarEstoque("p-1", 5);

            assertThat(dynamo.atualizacoes).singleElement().satisfies(u -> {
                assertThat(u.tableName()).isEqualTo("oficina-hml-pecas");
                assertThat(u.updateExpression()).contains("quantidadeEstoque = quantidadeEstoque + :q")
                        .contains("quantidadeDisponivel = quantidadeDisponivel + :q").contains("ADD versao :um");
                assertThat(u.conditionExpression()).isEqualTo("attribute_exists(pecaId)");
                assertThat(u.expressionAttributeValues().get(":q").n()).isEqualTo("5");
            });
        }

        @Test
        void entradaEmPecaInexistenteResponde404() {
            dynamo.falhaNaAtualizacao = () -> ConditionalCheckFailedException.builder().message("nao existe").build();

            assertThatThrownBy(() -> repositorio.adicionarEstoque("p-1", 5)).isInstanceOf(RecursoNaoEncontradoException.class);
        }

        @Test
        void saidaCondicionaAoDisponivel() {
            repositorio.removerEstoque("p-1", 3);

            assertThat(dynamo.atualizacoes).singleElement().satisfies(u -> {
                assertThat(u.conditionExpression()).isEqualTo("quantidadeDisponivel >= :q");
                assertThat(u.updateExpression()).contains("quantidadeEstoque = quantidadeEstoque - :q");
            });
        }

        @Test
        void saidaAcimaDoDisponivelInformaQuantoHa() {
            dynamo.falhaNaAtualizacao = () -> ConditionalCheckFailedException.builder().message("pouco").build();
            dynamo.itemLido = itemDaPeca("p-1", 10, 8, 1);

            assertThatThrownBy(() -> repositorio.removerEstoque("p-1", 3))
                    .isInstanceOf(RegraDeNegocioException.class)
                    .hasMessageContaining("Disponível: 2")
                    .hasMessageContaining("solicitada: 3");
        }

        @Test
        void saidaDePecaInexistenteResponde404() {
            dynamo.falhaNaAtualizacao = () -> ConditionalCheckFailedException.builder().message("nao existe").build();

            assertThatThrownBy(() -> repositorio.removerEstoque("p-1", 3)).isInstanceOf(RecursoNaoEncontradoException.class);
        }
    }

    @Nested
    class Reservas {

        private final UUID ordem = UUID.fromString("3c9a1b2d-4e5f-4a7b-8c9d-0e1f2a3b4c5d");
        private UnidadeDeTrabalho unidade;
        private DynamoReservaPecaRepository repositorio;

        @BeforeEach
        void preparar() {
            unidade = new UnidadeDeTrabalho(dynamo);
            repositorio = new DynamoReservaPecaRepository(
                    DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build(), unidade, PROPRIEDADES);
        }

        @Test
        void reservaPorPecaEUmUpdateCondicionalMaisUmPutNaMesmaTransacao() {
            unidade.executar(() -> repositorio.reservar(ordem, Map.of("a", 2), AGORA));

            var itens = dynamo.transacoes.getFirst().transactItems();
            assertThat(itens).hasSize(2);
            var update = itens.get(0).update();
            assertThat(update.tableName()).isEqualTo("oficina-hml-pecas");
            assertThat(update.key().get("pecaId").s()).isEqualTo("a");
            assertThat(update.updateExpression()).isEqualTo(
                    "SET quantidadeDisponivel = quantidadeDisponivel - :q, quantidadeReservada = quantidadeReservada + :q ADD versao :um");
            assertThat(update.conditionExpression()).isEqualTo("quantidadeDisponivel >= :q");
            assertThat(update.expressionAttributeValues().get(":q").n()).isEqualTo("2");
            var put = itens.get(1).put();
            assertThat(put.tableName()).isEqualTo("oficina-hml-reservas");
            assertThat(put.item().get("ordemServicoId").s()).isEqualTo(ordem.toString());
            assertThat(put.item().get("pecaId").s()).isEqualTo("a");
            assertThat(put.item().get("status").s()).isEqualTo("RESERVADA");
            assertThat(put.item().get("quantidade").n()).isEqualTo("2");
        }

        @Test
        void liberarDevolveAPecaEMarcaAReservaCondicionadaAoStatus() {
            ReservaPeca reserva = ReservaPeca.nova(ordem, "a", 2, AGORA);

            unidade.executar(() -> repositorio.liberar(List.of(reserva), AGORA.plusSeconds(60)));

            var itens = dynamo.transacoes.getFirst().transactItems();
            assertThat(itens.get(0).update().updateExpression()).isEqualTo(
                    "SET quantidadeDisponivel = quantidadeDisponivel + :q, quantidadeReservada = quantidadeReservada - :q ADD versao :um");
            assertThat(itens.get(0).update().conditionExpression()).isEqualTo("quantidadeReservada >= :q");
            var marcar = itens.get(1).update();
            assertThat(marcar.tableName()).isEqualTo("oficina-hml-reservas");
            assertThat(marcar.updateExpression()).isEqualTo("SET #status = :liberada, atualizadaEm = :agora");
            assertThat(marcar.conditionExpression()).isEqualTo("#status = :reservada");
            assertThat(marcar.expressionAttributeNames()).containsEntry("#status", "status");
            assertThat(marcar.expressionAttributeValues().get(":agora").s()).isEqualTo("2026-10-17T12:01:00Z");
        }

        @Test
        void reservaForaDeUmaUnidadeDeTrabalhoFalhaAlto() {
            assertThatThrownBy(() -> repositorio.reservar(ordem, Map.of("a", 1), AGORA))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void lePorOrdemDeServicoComConsistenciaForte() {
            dynamo.itensConsultados = List.of(Map.of(
                    "ordemServicoId", Atributos.texto(ordem.toString()),
                    "pecaId", Atributos.texto("a"),
                    "quantidade", Atributos.numero(2),
                    "status", Atributos.texto("CONSUMIDA"),
                    "criadaEm", Atributos.texto("2026-10-17T12:00:00Z"),
                    "atualizadaEm", Atributos.texto("2026-10-17T12:05:00Z")));

            List<ReservaPeca> reservas = repositorio.buscarPorOrdemDeServico(ordem);

            assertThat(reservas).singleElement().satisfies(r -> {
                assertThat(r.pecaInsumoId()).isEqualTo("a");
                assertThat(r.quantidade()).isEqualTo(2);
                assertThat(r.status()).isEqualTo(ReservaPeca.Status.CONSUMIDA);
                assertThat(r.atualizadaEm()).isEqualTo(Instant.parse("2026-10-17T12:05:00Z"));
                assertThat(r.ativa()).isFalse();
            });
            assertThat(dynamo.consultas).singleElement().satisfies(q -> {
                assertThat(q.tableName()).isEqualTo("oficina-hml-reservas");
                assertThat(q.consistentRead()).isTrue();
            });
        }
    }
}
