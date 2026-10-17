package br.com.oficina.execution.support.dynamodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

/**
 * Duplo do {@link DynamoDbClient} (interface do SDK): so as operacoes que o servico usa fora do
 * Enhanced Client. Guarda as requisicoes recebidas e deixa o teste escolher o que cada uma devolve ou
 * lanca; a semantica das expressoes quem confere e o LocalStack.
 */
public class DynamoDbClientFalso implements DynamoDbClient {

    public final List<TransactWriteItemsRequest> transacoes = new ArrayList<>();
    public final List<GetItemRequest> leituras = new ArrayList<>();
    public final List<PutItemRequest> gravacoes = new ArrayList<>();
    public final List<UpdateItemRequest> atualizacoes = new ArrayList<>();
    public final List<QueryRequest> consultas = new ArrayList<>();
    public final List<DescribeTableRequest> descricoes = new ArrayList<>();
    public final List<DeleteItemRequest> exclusoes = new ArrayList<>();
    public final List<ScanRequest> varreduras = new ArrayList<>();

    /** O que {@code getItem} devolve; vazio = item inexistente. */
    public Map<String, AttributeValue> itemLido = Map.of();
    /** O que {@code query} devolve. */
    public List<Map<String, AttributeValue>> itensConsultados = List.of();
    /** O que {@code scan} devolve. */
    public List<Map<String, AttributeValue>> itensVarridos = List.of();

    public Supplier<RuntimeException> falhaNaTransacao;
    public Supplier<RuntimeException> falhaNaGravacao;
    public Supplier<RuntimeException> falhaNaAtualizacao;
    public Supplier<RuntimeException> falhaNaDescricao;
    public Supplier<RuntimeException> falhaNaExclusao;

    @Override
    public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
        transacoes.add(request);
        if (falhaNaTransacao != null) {
            throw falhaNaTransacao.get();
        }
        return TransactWriteItemsResponse.builder().build();
    }

    @Override
    public GetItemResponse getItem(GetItemRequest request) {
        leituras.add(request);
        return itemLido.isEmpty() ? GetItemResponse.builder().build() : GetItemResponse.builder().item(itemLido).build();
    }

    @Override
    public PutItemResponse putItem(PutItemRequest request) {
        gravacoes.add(request);
        if (falhaNaGravacao != null) {
            throw falhaNaGravacao.get();
        }
        return PutItemResponse.builder().build();
    }

    @Override
    public UpdateItemResponse updateItem(UpdateItemRequest request) {
        atualizacoes.add(request);
        if (falhaNaAtualizacao != null) {
            throw falhaNaAtualizacao.get();
        }
        return UpdateItemResponse.builder().build();
    }

    @Override
    public QueryResponse query(QueryRequest request) {
        consultas.add(request);
        return QueryResponse.builder().items(itensConsultados).build();
    }

    @Override
    public DeleteItemResponse deleteItem(DeleteItemRequest request) {
        exclusoes.add(request);
        if (falhaNaExclusao != null) {
            throw falhaNaExclusao.get();
        }
        return DeleteItemResponse.builder().build();
    }

    @Override
    public ScanResponse scan(ScanRequest request) {
        varreduras.add(request);
        return ScanResponse.builder().items(itensVarridos).build();
    }

    @Override
    public DescribeTableResponse describeTable(DescribeTableRequest request) {
        descricoes.add(request);
        if (falhaNaDescricao != null) {
            throw falhaNaDescricao.get();
        }
        return DescribeTableResponse.builder().build();
    }

    @Override
    public String serviceName() {
        return "dynamodb";
    }

    @Override
    public void close() {
    }
}
