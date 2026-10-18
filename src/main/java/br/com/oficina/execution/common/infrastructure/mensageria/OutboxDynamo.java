package br.com.oficina.execution.common.infrastructure.mensageria;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.Tabelas;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Outbox no DynamoDB (ADR 0005 e 0006). O item entra na transacao que muda o estado, com o atributo
 * {@code pendente}; o GSI esparso {@code outbox-pendente} so enxerga os itens que o tem. Publicada a
 * mensagem, {@code pendente} sai e o item fica para auditoria ate o TTL.
 */
@Component
public class OutboxDynamo {

    /** Rotulo do item na transacao. */
    public static final String ROTULO = "outbox";
    static final String PREFIXO = "OUTBOX#";
    static final Duration RETENCAO = Duration.ofDays(7);
    /** Microssegundos fixos: a ordem textual e a cronologica. */
    private static final DateTimeFormatter ORDEM = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** Desempata mensagens gravadas no mesmo microssegundo, na ordem em que foram publicadas. */
    private final AtomicInteger sequencia = new AtomicInteger();

    /** Mensagens que esgotaram as tentativas desde a subida (MetricasOutbox): esgotada sai do indice. */
    private final AtomicLong esgotadas = new AtomicLong();

    private final DynamoDbClient dynamo;
    private final String tabela;
    private final Clock relogio;

    /** Uma mensagem pendente, como lida do indice. */
    public record Pendente(String pk, String fila, String grupo, String tipo, String corpo, int tentativas) {

        public String messageId() {
            return pk.substring(PREFIXO.length());
        }
    }

    @Autowired
    public OutboxDynamo(DynamoDbClient dynamo, PropriedadesDynamo propriedades) {
        this(dynamo, propriedades, Clock.systemUTC());
    }

    OutboxDynamo(DynamoDbClient dynamo, PropriedadesDynamo propriedades, Clock relogio) {
        this.dynamo = dynamo;
        this.tabela = propriedades.tabela(Tabelas.CONTROLE);
        this.relogio = relogio;
    }

    /** Item a acrescentar a transacao do efeito. */
    public TransactWriteItem item(String fila, String messageId, String grupo, String tipo, String corpo) {
        Instant agora = relogio.instant();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", Atributos.texto(PREFIXO + messageId));
        item.put("fila", Atributos.texto(fila));
        item.put("grupo", Atributos.texto(grupo));
        item.put("tipo", Atributos.texto(tipo));
        item.put("corpo", Atributos.texto(corpo));
        item.put("criadoEm", Atributos.texto(ORDEM.format(agora) + "-" + String.format("%04d", Math.floorMod(sequencia.getAndIncrement(), 10_000))));
        item.put("pendente", Atributos.texto("1"));
        item.put("tentativas", Atributos.numero(0));
        item.put("expiraEm", Atributos.numero(agora.plus(RETENCAO).getEpochSecond()));
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tabela)
                .item(item)
                .conditionExpression("attribute_not_exists(pk)")
                .build()).build();
    }

    /** As mais antigas primeiro. O indice e eventualmente consistente: o atraso e de milissegundos. */
    public List<Pendente> pendentes(int limite) {
        QueryResponse resposta = dynamo.query(r -> r.tableName(tabela)
                .indexName(Tabelas.INDICE_OUTBOX_PENDENTE)
                .keyConditionExpression("pendente = :um")
                .expressionAttributeValues(Map.of(":um", Atributos.texto("1")))
                .scanIndexForward(true)
                .limit(limite));
        return resposta.items().stream()
                .map(i -> new Pendente(
                        i.get("pk").s(),
                        i.get("fila").s(),
                        i.get("grupo").s(),
                        i.get("tipo").s(),
                        i.get("corpo").s(),
                        i.containsKey("tentativas") ? Integer.parseInt(i.get("tentativas").n()) : 0))
                .toList();
    }

    /** Quantas mensagens esperam entrega (itens do indice esparso). Para a metrica; pagina ate o fim. */
    public long contarPendentes() {
        long total = 0;
        Map<String, AttributeValue> inicio = null;
        do {
            Map<String, AttributeValue> a = inicio;
            QueryResponse resposta = dynamo.query(r -> {
                r.tableName(tabela)
                        .indexName(Tabelas.INDICE_OUTBOX_PENDENTE)
                        .keyConditionExpression("pendente = :um")
                        .expressionAttributeValues(Map.of(":um", Atributos.texto("1")))
                        .select("COUNT");
                if (a != null) {
                    r.exclusiveStartKey(a);
                }
            });
            total += resposta.count();
            inicio = resposta.hasLastEvaluatedKey() ? resposta.lastEvaluatedKey() : null;
        } while (inicio != null);
        return total;
    }

    /** Quando foi gravada a pendente mais antiga; vazio se nao ha nenhuma. */
    public Optional<Instant> maisAntigaPendente() {
        QueryResponse resposta = dynamo.query(r -> r.tableName(tabela)
                .indexName(Tabelas.INDICE_OUTBOX_PENDENTE)
                .keyConditionExpression("pendente = :um")
                .expressionAttributeValues(Map.of(":um", Atributos.texto("1")))
                .scanIndexForward(true)
                .limit(1));
        return resposta.items().stream().findFirst().map(i -> {
            // criadoEm = "<instante com microssegundos>Z-<sequencia>": o instante e o que vem antes do ultimo hifen.
            String criadoEm = i.get("criadoEm").s();
            return Instant.parse(criadoEm.substring(0, criadoEm.lastIndexOf('-')));
        });
    }

    public long esgotadasDesdeAPartida() {
        return esgotadas.get();
    }

    /** Sai do indice e guarda quando foi publicada. */
    public void marcarPublicada(String pk) {
        dynamo.updateItem(r -> r.tableName(tabela)
                .key(Map.of("pk", Atributos.texto(pk)))
                .updateExpression("SET publicadaEm = :agora REMOVE pendente")
                .expressionAttributeValues(Map.of(":agora", Atributos.texto(relogio.instant().toString()))));
    }

    /**
     * Conta a tentativa e guarda o erro. Esgotadas as tentativas o item sai do indice (equivale a
     * DLQ): continua na tabela, com {@code esgotada}, para quem investigar.
     */
    public void registrarFalha(String pk, String erro, boolean esgotada) {
        String expressao = "ADD tentativas :um SET ultimoErro = :erro" + (esgotada ? ", esgotada = :verdadeiro REMOVE pendente" : "");
        Map<String, AttributeValue> valores = new HashMap<>();
        valores.put(":um", Atributos.numero(1));
        valores.put(":erro", Atributos.texto(erro.length() <= 500 ? erro : erro.substring(0, 500)));
        if (esgotada) {
            valores.put(":verdadeiro", AttributeValue.builder().bool(true).build());
        }
        dynamo.updateItem(r -> r.tableName(tabela)
                .key(Map.of("pk", Atributos.texto(pk)))
                .updateExpression(expressao)
                .expressionAttributeValues(valores));
        if (esgotada) {
            esgotadas.incrementAndGet();
        }
    }
}
