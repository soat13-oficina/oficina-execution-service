package br.com.oficina.execution.common.infrastructure.dynamodb;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Pronto para receber trafego so se a tabela de controle responde. Entra no grupo {@code readiness}
 * apenas em nuvem (propriedade do overlay): em teste e no desenvolvimento sem DynamoDB o grupo fica
 * so com o estado da aplicacao.
 */
@Component("dynamoDb")
public class DynamoDbHealthIndicator implements HealthIndicator {

    private final DynamoDbClient dynamo;
    private final String tabela;

    public DynamoDbHealthIndicator(DynamoDbClient dynamo, PropriedadesDynamo propriedades) {
        this.dynamo = dynamo;
        this.tabela = propriedades.tabela(Tabelas.CONTROLE);
    }

    @Override
    public Health health() {
        try {
            dynamo.describeTable(r -> r.tableName(tabela));
            return Health.up().withDetail("tabela", tabela).build();
        } catch (RuntimeException e) {
            return Health.down(e).withDetail("tabela", tabela).build();
        }
    }
}
