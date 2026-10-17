package br.com.oficina.execution.support.dynamodb;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * As tabelas do ADR 0006 como o Terraform do {@code infra/} e o {@code scripts/criar-tabelas-local.sh}
 * as criam. Mudou o desenho de uma tabela: muda aqui, no script e no Terraform.
 */
public final class TabelasDeTeste {

    private TabelasDeTeste() {
    }

    public static void criar(DynamoDbClient dynamo, String ambiente) {
        String prefixo = "oficina-" + ambiente + "-";
        dynamo.createTable(r -> r.tableName(prefixo + "pecas")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(atributo("pecaId"))
                .keySchema(chave("pecaId", KeyType.HASH)));
        dynamo.createTable(r -> r.tableName(prefixo + "reservas")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(atributo("ordemServicoId"), atributo("pecaId"))
                .keySchema(chave("ordemServicoId", KeyType.HASH), chave("pecaId", KeyType.RANGE)));
        dynamo.createTable(r -> r.tableName(prefixo + "execution-controle")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(atributo("pk"), atributo("pendente"), atributo("criadoEm"))
                .keySchema(chave("pk", KeyType.HASH))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("outbox-pendente")
                        .keySchema(chave("pendente", KeyType.HASH), chave("criadoEm", KeyType.RANGE))
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build()));
    }

    private static AttributeDefinition atributo(String nome) {
        return AttributeDefinition.builder().attributeName(nome).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement chave(String nome, KeyType tipo) {
        return KeySchemaElement.builder().attributeName(nome).keyType(tipo).build();
    }
}
