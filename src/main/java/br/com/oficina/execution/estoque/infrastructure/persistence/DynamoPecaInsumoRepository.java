package br.com.oficina.execution.estoque.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException;
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.Tabelas;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.estoque.domain.repository.PecaInsumoRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.DeleteItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Pecas no DynamoDB (ADR 0006). Cadastro e leituras pelo Enhanced Client, com
 * {@code @DynamoDbVersionAttribute} como lock otimista; entrada e saida de estoque pelo cliente de
 * baixo nivel, porque precisam de {@code UpdateExpression} atomica e de condicao sobre o disponivel.
 */
@Repository
public class DynamoPecaInsumoRepository implements PecaInsumoRepository {

    private final DynamoDbTable<PecaItem> tabela;
    private final DynamoDbClient dynamo;
    private final String nomeTabela;

    public DynamoPecaInsumoRepository(DynamoDbEnhancedClient enhanced, DynamoDbClient dynamo, PropriedadesDynamo propriedades) {
        this.nomeTabela = propriedades.tabela(Tabelas.PECAS);
        this.tabela = enhanced.table(nomeTabela, TableSchema.fromBean(PecaItem.class));
        this.dynamo = dynamo;
    }

    @Override
    public void criar(PecaInsumo peca) {
        try {
            tabela.putItem(PecaItem.de(peca));
        } catch (ConditionalCheckFailedException e) {
            throw new ConflitoDeRecursoException("Ja existe peca/insumo com o ID: " + peca.getId());
        }
    }

    @Override
    public void substituir(PecaInsumo peca) {
        try {
            tabela.putItem(PecaItem.de(peca));
        } catch (ConditionalCheckFailedException e) {
            if (buscarPorId(peca.getId()).isEmpty()) {
                throw new RecursoNaoEncontradoException("Peça/Insumo não encontrada com o ID: " + peca.getId());
            }
            throw new ConflitoDeRecursoException("A peça/insumo foi alterada por outra requisição; consulte e tente novamente.");
        }
    }

    @Override
    public Optional<PecaInsumo> buscarPorId(String id) {
        PecaItem item = tabela.getItem(r -> r.key(Key.builder().partitionValue(id).build()).consistentRead(true));
        return Optional.ofNullable(item).map(PecaItem::paraDominio);
    }

    @Override
    public List<PecaInsumo> buscarTodos() {
        return tabela.scan(r -> r.consistentRead(true)).items().stream().map(PecaItem::paraDominio).toList();
    }

    @Override
    public void excluirPorId(String id) {
        Expression semReserva = Expression.builder()
                .expression("quantidadeReservada = :zero")
                .putExpressionValue(":zero", Atributos.numero(0))
                .build();
        try {
            tabela.deleteItem(DeleteItemEnhancedRequest.builder()
                    .key(Key.builder().partitionValue(id).build())
                    .conditionExpression(semReserva)
                    .build());
        } catch (ConditionalCheckFailedException e) {
            throw new RegraDeNegocioException("Não é possível excluir peça/insumo com reserva ativa.");
        }
    }

    @Override
    public void adicionarEstoque(String id, int quantidade) {
        try {
            atualizar(ExpressoesEstoque.entradaDeEstoque(nomeTabela, id, quantidade));
        } catch (ConditionalCheckFailedException e) {
            throw naoEncontrada(id);
        }
    }

    @Override
    public void removerEstoque(String id, int quantidade) {
        try {
            atualizar(ExpressoesEstoque.saidaDeEstoque(nomeTabela, id, quantidade));
        } catch (ConditionalCheckFailedException e) {
            PecaInsumo peca = buscarPorId(id).orElseThrow(() -> naoEncontrada(id));
            throw new RegraDeNegocioException("Estoque disponível insuficiente. Disponível: " + peca.getQuantidadeDisponivel()
                    + ", quantidade solicitada: " + quantidade);
        }
    }

    private void atualizar(Update update) {
        dynamo.updateItem(r -> r.tableName(update.tableName())
                .key(update.key())
                .updateExpression(update.updateExpression())
                .conditionExpression(update.conditionExpression())
                .expressionAttributeValues(update.expressionAttributeValues()));
    }

    private static RecursoNaoEncontradoException naoEncontrada(String id) {
        return new RecursoNaoEncontradoException("Peça/Insumo não encontrada com o ID: " + id);
    }
}
