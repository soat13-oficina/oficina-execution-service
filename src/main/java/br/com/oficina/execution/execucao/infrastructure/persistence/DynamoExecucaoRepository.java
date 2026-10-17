package br.com.oficina.execution.execucao.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.Tabelas;
import br.com.oficina.execution.common.infrastructure.dynamodb.UnidadeDeTrabalho;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.repository.ExecucaoRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Execucoes no DynamoDB (ADR 0006). Leitura pelo Enhanced Client (consistente por chave; a fila pelo GSI
 * {@code fila-por-etapa}). Escrita acrescenta um {@code Put} ao lote da {@link UnidadeDeTrabalho},
 * condicionado: na criacao, a OS nao pode ter execucao; na atualizacao, a versao gravada tem de ser a
 * que o chamador leu - duas acoes simultaneas sobre a mesma OS nao se atropelam.
 */
@Repository
public class DynamoExecucaoRepository implements ExecucaoRepository {

    private static final TableSchema<ExecucaoItem> ESQUEMA = TableSchema.fromBean(ExecucaoItem.class);

    private final DynamoDbTable<ExecucaoItem> tabela;
    private final UnidadeDeTrabalho unidade;
    private final String nomeTabela;

    public DynamoExecucaoRepository(DynamoDbEnhancedClient enhanced, UnidadeDeTrabalho unidade, PropriedadesDynamo propriedades) {
        this.nomeTabela = propriedades.tabela(Tabelas.EXECUCOES);
        this.tabela = enhanced.table(nomeTabela, ESQUEMA);
        this.unidade = unidade;
    }

    @Override
    public Optional<Execucao> buscarPorOrdemDeServico(UUID ordemServicoId) {
        ExecucaoItem item = tabela.getItem(r -> r.key(Key.builder().partitionValue(ordemServicoId.toString()).build()).consistentRead(true));
        return Optional.ofNullable(item).map(ExecucaoItem::paraDominio);
    }

    @Override
    public void criar(Execucao execucao) {
        Put put = Put.builder()
                .tableName(nomeTabela)
                .item(ESQUEMA.itemToMap(ExecucaoItem.de(execucao, 1), true))
                .conditionExpression("attribute_not_exists(ordemServicoId)")
                .build();
        unidade.adicionar("execucao", TransactWriteItem.builder().put(put).build());
    }

    @Override
    public void atualizar(Execucao execucao) {
        Put put = Put.builder()
                .tableName(nomeTabela)
                .item(ESQUEMA.itemToMap(ExecucaoItem.de(execucao, execucao.versao() + 1), true))
                .conditionExpression("versao = :lida")
                .expressionAttributeValues(Map.of(":lida", Atributos.numero(execucao.versao())))
                .build();
        unidade.adicionar("execucao", TransactWriteItem.builder().put(put).build());
    }

    @Override
    public List<Execucao> listarPorEtapa(Etapa etapa) {
        DynamoDbIndex<ExecucaoItem> fila = tabela.index(ExecucaoItem.INDICE_FILA);
        return fila.query(r -> r.queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(etapa.name()).build())))
                .stream()
                .flatMap((Page<ExecucaoItem> pagina) -> pagina.items().stream())
                .map(ExecucaoItem::paraDominio)
                .toList();
    }
}
