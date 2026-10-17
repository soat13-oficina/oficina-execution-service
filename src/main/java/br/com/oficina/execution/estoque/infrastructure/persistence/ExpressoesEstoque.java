package br.com.oficina.execution.estoque.infrastructure.persistence;

import static br.com.oficina.execution.common.infrastructure.dynamodb.Atributos.numero;
import static br.com.oficina.execution.common.infrastructure.dynamodb.Atributos.texto;

import java.time.Instant;
import java.util.Map;

import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * As escritas do estoque como itens de {@code TransactWriteItems}, com a expressao explicita (ADR
 * 0006). A tabela abaixo mostra o que cada operacao faz com os tres campos que mantem
 * {@code disponivel = estoque - reservada}; toda escrita soma 1 a {@code versao}, para o lock
 * otimista do cadastro enxergar a mudanca.
 *
 * <pre>
 * reservar    disponivel -= q, reservada += q   condicao: disponivel >= q
 * liberar     disponivel += q, reservada -= q   condicao: reservada >= q
 * entrada     estoque += q,    disponivel += q  condicao: a peca existe
 * saida       estoque -= q,    disponivel -= q  condicao: disponivel >= q
 * </pre>
 */
final class ExpressoesEstoque {

    static final String CHAVE_PECA = "pecaId";
    static final String CHAVE_RESERVA_OS = "ordemServicoId";
    private static final TableSchema<ReservaItem> ESQUEMA_RESERVA = TableSchema.fromBean(ReservaItem.class);

    private ExpressoesEstoque() {
    }

    static Update reservarPeca(String tabela, String pecaId, int quantidade) {
        return Update.builder()
                .tableName(tabela)
                .key(Map.of(CHAVE_PECA, texto(pecaId)))
                .updateExpression("SET quantidadeDisponivel = quantidadeDisponivel - :q, quantidadeReservada = quantidadeReservada + :q ADD versao :um")
                .conditionExpression("quantidadeDisponivel >= :q")
                .expressionAttributeValues(Map.of(":q", numero(quantidade), ":um", numero(1)))
                .build();
    }

    static Update devolverPeca(String tabela, String pecaId, int quantidade) {
        return Update.builder()
                .tableName(tabela)
                .key(Map.of(CHAVE_PECA, texto(pecaId)))
                .updateExpression("SET quantidadeDisponivel = quantidadeDisponivel + :q, quantidadeReservada = quantidadeReservada - :q ADD versao :um")
                .conditionExpression("quantidadeReservada >= :q")
                .expressionAttributeValues(Map.of(":q", numero(quantidade), ":um", numero(1)))
                .build();
    }

    static Update entradaDeEstoque(String tabela, String pecaId, int quantidade) {
        return Update.builder()
                .tableName(tabela)
                .key(Map.of(CHAVE_PECA, texto(pecaId)))
                .updateExpression("SET quantidadeEstoque = quantidadeEstoque + :q, quantidadeDisponivel = quantidadeDisponivel + :q ADD versao :um")
                .conditionExpression("attribute_exists(pecaId)")
                .expressionAttributeValues(Map.of(":q", numero(quantidade), ":um", numero(1)))
                .build();
    }

    static Update saidaDeEstoque(String tabela, String pecaId, int quantidade) {
        return Update.builder()
                .tableName(tabela)
                .key(Map.of(CHAVE_PECA, texto(pecaId)))
                .updateExpression("SET quantidadeEstoque = quantidadeEstoque - :q, quantidadeDisponivel = quantidadeDisponivel - :q ADD versao :um")
                .conditionExpression("quantidadeDisponivel >= :q")
                .expressionAttributeValues(Map.of(":q", numero(quantidade), ":um", numero(1)))
                .build();
    }

    static Put registrarReserva(String tabela, ReservaPeca reserva) {
        return Put.builder()
                .tableName(tabela)
                .item(ESQUEMA_RESERVA.itemToMap(ReservaItem.de(reserva), true))
                .build();
    }

    /** {@code status} e palavra reservada do DynamoDB, por isso o alias. */
    static Update liberarReserva(String tabela, ReservaPeca reserva, Instant agora) {
        return Update.builder()
                .tableName(tabela)
                .key(Map.of(
                        CHAVE_RESERVA_OS, texto(reserva.ordemDeServicoId().toString()),
                        CHAVE_PECA, texto(reserva.pecaInsumoId())))
                .updateExpression("SET #status = :liberada, atualizadaEm = :agora")
                .conditionExpression("#status = :reservada")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":liberada", texto(ReservaPeca.Status.LIBERADA.name()),
                        ":reservada", texto(ReservaPeca.Status.RESERVADA.name()),
                        ":agora", texto(agora.toString())))
                .build();
    }

    static TransactWriteItem emTransacao(Update update) {
        return TransactWriteItem.builder().update(update).build();
    }

    static TransactWriteItem emTransacao(Put put) {
        return TransactWriteItem.builder().put(put).build();
    }
}
