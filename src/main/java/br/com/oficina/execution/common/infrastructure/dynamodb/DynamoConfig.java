package br.com.oficina.execution.common.infrastructure.dynamodb;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * Clientes do DynamoDB (AWS SDK v2 puro, sem Spring Cloud AWS). Credenciais pela cadeia padrao:
 * IRSA no EKS; variaveis {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY} no LocalStack.
 *
 * <p>Criar o cliente nao abre conexao: so a primeira chamada resolve credenciais e endpoint, e por
 * isso o contexto sobe em teste sem AWS.
 */
@Configuration
@EnableConfigurationProperties(PropriedadesDynamo.class)
public class DynamoConfig {

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient(PropriedadesDynamo propriedades) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(propriedades.regiao()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (propriedades.endpoint() != null && !propriedades.endpoint().toString().isBlank()) {
            builder.endpointOverride(propriedades.endpoint());
        }
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    }
}
