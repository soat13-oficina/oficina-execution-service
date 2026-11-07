package br.com.oficina.execution.common.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import br.com.oficina.execution.support.dynamodb.DynamoDbClientFalso;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

class DynamoConfigTest {

    @Test
    void clienteUsaOEndpointDoLocalStackQuandoInformado() {
        DynamoConfig config = new DynamoConfig();

        try (DynamoDbClient localstack = config.dynamoDbClient(new PropriedadesDynamo("local", URI.create("http://localhost:4566"), "sa-east-1"));
             DynamoDbClient aws = config.dynamoDbClient(new PropriedadesDynamo("hml", null, null))) {
            assertThat(localstack.serviceClientConfiguration().endpointOverride()).hasValue(URI.create("http://localhost:4566"));
            assertThat(localstack.serviceClientConfiguration().region().id()).isEqualTo("sa-east-1");
            assertThat(aws.serviceClientConfiguration().endpointOverride()).isEmpty();
            assertThat(config.dynamoDbEnhancedClient(aws)).isNotNull();
        }
    }

    @Test
    void prontoQuandoATabelaDeControleResponde() {
        DynamoDbClientFalso dynamo = new DynamoDbClientFalso();

        var saude = new DynamoDbHealthIndicator(dynamo, new PropriedadesDynamo("hml", null, null)).health();

        assertThat(saude.getStatus()).isEqualTo(Status.UP);
        assertThat(saude.getDetails()).containsEntry("tabela", "oficina-hml-execution-controle");
        assertThat(dynamo.descricoes).singleElement().satisfies(d -> assertThat(d.tableName()).isEqualTo("oficina-hml-execution-controle"));
    }

    @Test
    void foraDoArQuandoATabelaNaoExisteOuOBancoNaoResponde() {
        DynamoDbClientFalso dynamo = new DynamoDbClientFalso();
        dynamo.falhaNaDescricao = () -> ResourceNotFoundException.builder().message("sem tabela").build();

        var saude = new DynamoDbHealthIndicator(dynamo, new PropriedadesDynamo("hml", null, null)).health();

        assertThat(saude.getStatus()).isEqualTo(Status.DOWN);
    }
}
