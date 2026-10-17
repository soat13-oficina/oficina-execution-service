package br.com.oficina.execution.common.infrastructure.mensageria.sqs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * Cliente SQS (AWS SDK v2 puro, sem Spring Cloud AWS - ADR 0005). Credenciais pela cadeia padrao:
 * IRSA no EKS; variaveis {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY} no LocalStack.
 */
@Configuration
@EnableConfigurationProperties(PropriedadesSqs.class)
public class MensageriaSqsConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "mensageria.sqs.habilitado", havingValue = "true")
    public SqsClient sqsClient(PropriedadesSqs propriedades) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(propriedades.regiao()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (propriedades.endpoint() != null && !propriedades.endpoint().toString().isBlank()) {
            builder.endpointOverride(propriedades.endpoint());
        }
        return builder.build();
    }
}
