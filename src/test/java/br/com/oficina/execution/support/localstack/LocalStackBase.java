package br.com.oficina.execution.support.localstack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import br.com.oficina.execution.support.dynamodb.TabelasDeTeste;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.json.JsonMapper;

/**
 * DynamoDB e SQS de verdade (LocalStack 4.14.0) para os testes de integracao: um unico container para a
 * JVM inteira, com as tabelas do ADR 0006 e as filas da Saga criadas uma vez, e o contexto Spring
 * compartilhado entre as classes. Sem Docker, as classes sao ignoradas.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "mensageria.sqs.habilitado=true",
        "mensageria.sqs.consumo=execution-commands",
        "mensageria.sqs.ambiente=teste",
        "mensageria.sqs.espera=PT1S",
        "mensageria.sqs.receptor-automatico=false"
})
public abstract class LocalStackBase {

    static {
        // Cadeia padrao de credenciais do SDK; o LocalStack aceita qualquer par.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    private static final LocalStackContainer LOCALSTACK;
    protected static SqsClient sqsDoTeste;
    protected static String urlRespostas;
    protected static String urlComandos;

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
                    .withServices(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.SQS);
            LOCALSTACK.start();
            criarTabelasEFilas();
        } else {
            LOCALSTACK = null;
        }
    }

    @DynamicPropertySource
    static void localstack(DynamicPropertyRegistry registro) {
        if (LOCALSTACK == null) {
            return;
        }
        registro.add("dynamodb.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registro.add("dynamodb.regiao", LOCALSTACK::getRegion);
        registro.add("mensageria.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registro.add("mensageria.sqs.regiao", LOCALSTACK::getRegion);
    }

    private static void criarTabelasEFilas() {
        var credenciais = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (DynamoDbClient dynamo = DynamoDbClient.builder().endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion())).credentialsProvider(credenciais).build()) {
            TabelasDeTeste.criar(dynamo, "teste");
        }
        sqsDoTeste = SqsClient.builder().endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion())).credentialsProvider(credenciais).build();
        urlRespostas = criarFifo("oficina-teste-os-saga-replies.fifo", Map.of());
        String urlDlq = criarFifo("oficina-teste-execution-commands-dlq.fifo", Map.of());
        String arnDlq = sqsDoTeste.getQueueAttributes(r -> r.queueUrl(urlDlq).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        urlComandos = criarFifo("oficina-teste-execution-commands.fifo", Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                QueueAttributeName.REDRIVE_POLICY, "{\"deadLetterTargetArn\":\"" + arnDlq + "\",\"maxReceiveCount\":\"5\"}"));
    }

    private static String criarFifo(String nome, Map<QueueAttributeName, String> atributos) {
        Map<QueueAttributeName, String> todos = new HashMap<>(atributos);
        todos.put(QueueAttributeName.FIFO_QUEUE, "true");
        return sqsDoTeste.createQueue(r -> r.queueName(nome).attributes(todos)).queueUrl();
    }

    @Autowired
    protected DynamoDbClient dynamo;

    @Autowired
    protected JsonMapper jsonMapper;

    /** Cada teste comeca com as tabelas e as filas vazias. */
    @BeforeEach
    void limparTudo() {
        Map<String, List<String>> chaves = Map.of(
                "pecas", List.of("pecaId"),
                "reservas", List.of("ordemServicoId", "pecaId"),
                "execucoes", List.of("ordemServicoId"),
                "execution-controle", List.of("pk"));
        chaves.forEach((tabela, atributosDaChave) -> {
            String nome = "oficina-teste-" + tabela;
            dynamo.scanPaginator(r -> r.tableName(nome)).items().forEach(item -> {
                Map<String, AttributeValue> chave = new HashMap<>();
                atributosDaChave.forEach(c -> chave.put(c, item.get(c)));
                dynamo.deleteItem(r -> r.tableName(nome).key(chave));
            });
        });
        for (String url : List.of(urlRespostas, urlComandos)) {
            sqsDoTeste.purgeQueue(r -> r.queueUrl(url));
        }
    }

    protected static List<Message> receber(String url) {
        return sqsDoTeste.receiveMessage(r -> r.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1)
                .messageSystemAttributeNames(MessageSystemAttributeName.ALL).messageAttributeNames("All")).messages();
    }
}
