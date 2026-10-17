package br.com.oficina.execution.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException;
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.EscritaRecusadaException;
import br.com.oficina.execution.common.infrastructure.dynamodb.UnidadeDeTrabalho;
import br.com.oficina.execution.common.infrastructure.mensageria.ConsumidorMensagens;
import br.com.oficina.execution.common.infrastructure.mensageria.PublicadorOutbox;
import br.com.oficina.execution.common.infrastructure.mensageria.sqs.ReceptorSqs;
import br.com.oficina.execution.estoque.application.CatalogoDePecasService;
import br.com.oficina.execution.estoque.application.CatalogoDePecasService.DadosPeca;
import br.com.oficina.execution.estoque.domain.model.CategoriaPeca;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import br.com.oficina.execution.estoque.domain.repository.PecaInsumoRepository;
import br.com.oficina.execution.estoque.domain.repository.ReservaPecaRepository;
import br.com.oficina.execution.support.contratos.ContratosDeMensagens;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O estoque contra DynamoDB e SQS de verdade (LocalStack, ADR 0005 e 0006): o que os duplos em memoria
 * nao provam - expressoes aceitas pelo DynamoDB, condicoes, transacao atomica, idempotencia, outbox
 * publicado no SQS e a corrida pelo ultimo item do estoque. Sem Docker, o teste e ignorado.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "mensageria.sqs.habilitado=true",
        "mensageria.sqs.consumo=execution-commands",
        "mensageria.sqs.ambiente=teste",
        "mensageria.sqs.espera=PT1S",
        "mensageria.sqs.receptor-automatico=false"
})
class EstoqueDynamoLocalStackTest {

    private static final ContratosDeMensagens CONTRATOS = new ContratosDeMensagens();

    static {
        // Cadeia padrao de credenciais do SDK; o LocalStack aceita qualquer par.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.SQS);

    private static SqsClient sqsDoTeste;
    private static String urlRespostas;
    private static String urlComandos;

    @DynamicPropertySource
    static void localstack(DynamicPropertyRegistry registro) {
        registro.add("dynamodb.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registro.add("dynamodb.regiao", LOCALSTACK::getRegion);
        registro.add("mensageria.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registro.add("mensageria.sqs.regiao", LOCALSTACK::getRegion);
    }

    @BeforeAll
    static void criarTabelasEFilas() {
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
        Map<QueueAttributeName, String> todos = new java.util.HashMap<>(atributos);
        todos.put(QueueAttributeName.FIFO_QUEUE, "true");
        return sqsDoTeste.createQueue(r -> r.queueName(nome).attributes(todos)).queueUrl();
    }

    @Autowired
    private DynamoDbClient dynamo;

    @Autowired
    private PecaInsumoRepository pecas;

    @Autowired
    private ReservaPecaRepository reservas;

    @Autowired
    private CatalogoDePecasService catalogo;

    @Autowired
    private ConsumidorMensagens consumidor;

    @Autowired
    private PublicadorOutbox publicadorOutbox;

    @Autowired
    private ReceptorSqs receptor;

    @Autowired
    private UnidadeDeTrabalho unidade;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void limpar() {
        Map<String, List<String>> chaves = Map.of(
                "pecas", List.of("pecaId"),
                "reservas", List.of("ordemServicoId", "pecaId"),
                "execution-controle", List.of("pk"));
        chaves.forEach((tabela, atributosDaChave) -> {
            String nome = "oficina-teste-" + tabela;
            dynamo.scanPaginator(r -> r.tableName(nome)).items().forEach(item -> {
                Map<String, AttributeValue> chave = new java.util.HashMap<>();
                atributosDaChave.forEach(c -> chave.put(c, item.get(c)));
                dynamo.deleteItem(r -> r.tableName(nome).key(chave));
            });
        });
        for (String url : List.of(urlRespostas, urlComandos)) {
            sqsDoTeste.purgeQueue(r -> r.queueUrl(url));
        }
    }

    private String cadastrar(String descricao, int estoque) {
        return catalogo.cadastrar(new DadosPeca(descricao, "Bosch", new BigDecimal("45.90"), estoque, "REF-" + descricao, CategoriaPeca.FILTROS));
    }

    /** O exemplo de ReservarPecas do contrato, com as pecas dele cadastradas e {@code estoque} de cada. */
    private ObjectNode reservarDoContrato(int estoque, UUID ordem) {
        ObjectNode envelope = (ObjectNode) CONTRATOS.exemplo("ReservarPecas");
        envelope.put("messageId", UUID.randomUUID().toString());
        envelope.put("ordemServicoId", ordem.toString());
        for (JsonNode item : envelope.get("payload").get("itens")) {
            String id = item.get("pecaInsumoId").asString();
            if (pecas.buscarPorId(id).isEmpty()) {
                pecas.criar(new PecaInsumo(id, "Peca " + id, "Bosch", new BigDecimal("80.00"), estoque, 0, "REF-" + id, CategoriaPeca.FILTROS, null));
            }
        }
        return envelope;
    }

    private List<Envelope> publicadasNoOutbox() {
        List<Envelope> eventos = new ArrayList<>();
        dynamo.scanPaginator(r -> r.tableName("oficina-teste-execution-controle")).items().forEach(item -> {
            if (item.get("pk").s().startsWith("OUTBOX#")) {
                eventos.add(jsonMapper.readValue(item.get("corpo").s(), Envelope.class));
            }
        });
        return eventos;
    }

    private List<Message> receber(String url) {
        return sqsDoTeste.receiveMessage(r -> r.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1)
                .messageSystemAttributeNames(MessageSystemAttributeName.ALL).messageAttributeNames("All")).messages();
    }

    // --- cadastro e estoque -------------------------------------------------------------------------

    @Test
    void cadastroRoundTripEntradaESaidaMantemODisponivel() {
        String id = cadastrar("Filtro", 10);

        catalogo.adicionarEstoque(id, 5);
        catalogo.removerEstoque(id, 3);

        PecaInsumo peca = pecas.buscarPorId(id).orElseThrow();
        assertThat(peca.getQuantidadeEstoque()).isEqualTo(12);
        assertThat(peca.getQuantidadeDisponivel()).isEqualTo(12);
        assertThat(peca.getPreco()).isEqualByComparingTo("45.90");
        assertThat(peca.getCategoria()).isEqualTo(CategoriaPeca.FILTROS);
        assertThat(peca.getVersao()).as("a entrada e a saida somam 1 a versao").isEqualTo(3L);
        assertThat(pecas.buscarTodos()).extracting(PecaInsumo::getId).containsExactly(id);
    }

    @Test
    void saidaSoDoDisponivelEPecaInexistenteResponde404() {
        String id = cadastrar("Filtro", 2);

        assertThatThrownBy(() -> catalogo.removerEstoque(id, 3)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> catalogo.adicionarEstoque("fantasma", 1)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> catalogo.removerEstoque("fantasma", 1)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThat(pecas.buscarPorId("fantasma")).isEmpty();
    }

    @Test
    void alteracaoDesatualizadaEhRecusadaPeloLockOtimista() {
        String id = cadastrar("Filtro", 10);
        PecaInsumo lida = pecas.buscarPorId(id).orElseThrow();
        catalogo.adicionarEstoque(id, 1); // outra requisicao mexe depois da leitura

        assertThatThrownBy(() -> pecas.substituir(lida)).isInstanceOf(ConflitoDeRecursoException.class);
        catalogo.alterar(id, new DadosPeca("Filtro novo", "Mann", new BigDecimal("50.00"), 20, "R2", CategoriaPeca.FREIOS));
        assertThat(pecas.buscarPorId(id).orElseThrow().getDescricao()).isEqualTo("Filtro novo");
    }

    @Test
    void excluirPecaComReservaEhRecusado() {
        UUID ordem = UUID.randomUUID();
        ObjectNode reservar = reservarDoContrato(5, ordem);
        consumidor.consumir(reservar.toString());
        String reservada = reservar.get("payload").get("itens").get(0).get("pecaInsumoId").asString();

        assertThatThrownBy(() -> catalogo.excluir(reservada)).isInstanceOf(RegraDeNegocioException.class);
        String livre = cadastrar("Livre", 1);
        catalogo.excluir(livre);
        assertThat(pecas.buscarPorId(livre)).isEmpty();
    }

    // --- Saga: reserva e liberacao ------------------------------------------------------------------

    @Test
    void reservaDebitaOEstoqueRegistraAReservaEGravaAResposta_tudoNumaTransacao() {
        UUID ordem = UUID.randomUUID();
        ObjectNode comando = reservarDoContrato(5, ordem);

        consumidor.consumir(comando.toString());

        for (JsonNode item : comando.get("payload").get("itens")) {
            PecaInsumo peca = pecas.buscarPorId(item.get("pecaInsumoId").asString()).orElseThrow();
            int pedida = item.get("quantidade").asInt();
            assertThat(peca.getQuantidadeReservada()).isEqualTo(pedida);
            assertThat(peca.getQuantidadeDisponivel()).isEqualTo(5 - pedida);
            assertThat(peca.getQuantidadeEstoque()).isEqualTo(5);
        }
        assertThat(reservas.buscarPorOrdemDeServico(ordem)).hasSize(2).allMatch(ReservaPeca::ativa);
        List<Envelope> respostas = publicadasNoOutbox();
        assertThat(respostas).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("PecasReservadas");
            assertThat(r.causationId()).hasToString(comando.get("messageId").asString());
            assertThat(CONTRATOS.validar(jsonMapper.writeValueAsString(r))).isEmpty();
        });
    }

    @Test
    void mensagemRepetidaNaoReservaDeNovo() {
        UUID ordem = UUID.randomUUID();
        ObjectNode comando = reservarDoContrato(5, ordem);

        consumidor.consumir(comando.toString());
        consumidor.consumir(comando.toString());

        assertThat(pecas.buscarPorId(comando.get("payload").get("itens").get(0).get("pecaInsumoId").asString()).orElseThrow()
                .getQuantidadeReservada()).isEqualTo(1);
        assertThat(publicadasNoOutbox()).hasSize(1);
    }

    @Test
    void estoqueInsuficienteNaoMudaNadaERespondeReservaFalhou() {
        UUID ordem = UUID.randomUUID();
        ObjectNode comando = reservarDoContrato(1, ordem); // o contrato pede 1 e 2: a segunda peca falta

        consumidor.consumir(comando.toString());

        assertThat(reservas.buscarPorOrdemDeServico(ordem)).isEmpty();
        for (JsonNode item : comando.get("payload").get("itens")) {
            assertThat(pecas.buscarPorId(item.get("pecaInsumoId").asString()).orElseThrow().getQuantidadeReservada()).isZero();
        }
        assertThat(publicadasNoOutbox()).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("ReservaFalhou");
            assertThat(CONTRATOS.validar(jsonMapper.writeValueAsString(r))).isEmpty();
        });
    }

    @Test
    void liberarDevolveAoDisponivelEConfirma() {
        UUID ordem = UUID.randomUUID();
        ObjectNode reservar = reservarDoContrato(5, ordem);
        consumidor.consumir(reservar.toString());
        ObjectNode liberar = (ObjectNode) CONTRATOS.exemplo("LiberarReserva");
        liberar.put("messageId", UUID.randomUUID().toString());
        liberar.put("ordemServicoId", ordem.toString());

        consumidor.consumir(liberar.toString());

        for (JsonNode item : reservar.get("payload").get("itens")) {
            PecaInsumo peca = pecas.buscarPorId(item.get("pecaInsumoId").asString()).orElseThrow();
            assertThat(peca.getQuantidadeReservada()).isZero();
            assertThat(peca.getQuantidadeDisponivel()).isEqualTo(5);
        }
        assertThat(reservas.buscarPorOrdemDeServico(ordem)).allMatch(r -> r.status() == ReservaPeca.Status.LIBERADA);
        assertThat(publicadasNoOutbox()).extracting(Envelope::type).containsExactlyInAnyOrder("PecasReservadas", "ReservaLiberada");
    }

    @Test
    void duasOsDisputandoOUltimoItemSoUmaReserva() throws Exception {
        String pecaId = UUID.randomUUID().toString();
        pecas.criar(new PecaInsumo(pecaId, "Ultima", "Bosch", new BigDecimal("10.00"), 1, 0, "REF", CategoriaPeca.FILTROS, null));
        ObjectNode a = comandoReservando(pecaId, UUID.randomUUID());
        ObjectNode b = comandoReservando(pecaId, UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);

        List<Future<Throwable>> resultados = new ArrayList<>();
        for (ObjectNode comando : List.of(a, b)) {
            resultados.add(pool.submit(() -> {
                largada.await();
                try {
                    consumidor.consumir(comando.toString());
                    return null;
                } catch (EscritaRecusadaException e) {
                    return e; // a transacao do perdedor foi recusada: a mensagem volta para a fila
                }
            }));
        }
        largada.countDown();
        List<Throwable> falhas = new ArrayList<>();
        for (Future<Throwable> f : resultados) {
            falhas.add(f.get());
        }
        pool.shutdown();

        PecaInsumo peca = pecas.buscarPorId(pecaId).orElseThrow();
        assertThat(peca.getQuantidadeReservada()).as("nunca reserva alem do estoque").isLessThanOrEqualTo(1);
        assertThat(peca.getQuantidadeDisponivel()).isGreaterThanOrEqualTo(0);

        // O perdedor, quando a fila o entrega de novo, ja enxerga o estoque de agora: ReservaFalhou.
        ObjectNode perdedor = falhas.get(0) != null ? a : (falhas.get(1) != null ? b : null);
        if (perdedor != null) {
            consumidor.consumir(perdedor.toString());
            assertThat(publicadasNoOutbox()).extracting(Envelope::type).contains("ReservaFalhou");
        }
        assertThat(pecas.buscarPorId(pecaId).orElseThrow().getQuantidadeReservada()).isEqualTo(1);
    }

    private ObjectNode comandoReservando(String pecaId, UUID ordem) {
        ObjectNode envelope = (ObjectNode) CONTRATOS.exemplo("ReservarPecas");
        envelope.put("messageId", UUID.randomUUID().toString());
        envelope.put("ordemServicoId", ordem.toString());
        ((ObjectNode) envelope.get("payload")).putArray("itens").addObject().put("pecaInsumoId", pecaId).put("quantidade", 1);
        return envelope;
    }

    @Test
    void transacaoComItemDeIdempotenciaJaGravadoEhRecusadaComOMotivo() {
        // A prova de que o DynamoDB devolve o motivo por item (CancellationReasons), de que o consumidor depende.
        UUID messageId = UUID.randomUUID();
        dynamo.putItem(r -> r.tableName("oficina-teste-execution-controle").item(Map.of("pk", Atributos.texto("MSG#" + messageId))));
        Envelope envelope = new Envelope(messageId, "ReservarPecas", 1, null, UUID.randomUUID(), "c", null,
                java.time.Instant.now(), "oficina-os-service", jsonMapper.createObjectNode());

        assertThatThrownBy(() -> unidade.executar(() -> unidade.adicionar("idempotencia",
                new br.com.oficina.execution.common.infrastructure.mensageria.ControleDeMensagens(dynamo,
                        new br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo("teste", null, null))
                        .registrar(envelope))))
                .isInstanceOfSatisfying(EscritaRecusadaException.class, e -> assertThat(e.recusou("idempotencia")).isTrue());
    }

    // --- outbox e SQS -------------------------------------------------------------------------------

    @Test
    void outboxPublicaNoSqsComGrupoDeduplicacaoEAtributosESaiDoIndice() {
        UUID ordem = UUID.randomUUID();
        ObjectNode comando = reservarDoContrato(5, ordem);
        consumidor.consumir(comando.toString());

        assertThat(publicadorOutbox.publicarPendentes()).isEqualTo(1);
        assertThat(publicadorOutbox.publicarPendentes()).as("nada mais pendente").isZero();

        assertThat(receber(urlRespostas)).singleElement().satisfies(m -> {
            Envelope evento = jsonMapper.readValue(m.body(), Envelope.class);
            assertThat(evento.type()).isEqualTo("PecasReservadas");
            assertThat(m.attributes()).containsEntry(MessageSystemAttributeName.MESSAGE_GROUP_ID, ordem.toString())
                    .containsEntry(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID, evento.messageId().toString());
            assertThat(m.messageAttributes().get("type").stringValue()).isEqualTo("PecasReservadas");
        });
        var pendentes = dynamo.query(r -> r.tableName("oficina-teste-execution-controle").indexName("outbox-pendente")
                .keyConditionExpression("pendente = :um").expressionAttributeValues(Map.of(":um", Atributos.texto("1")))).items();
        assertThat(pendentes).isEmpty();
    }

    @Test
    void receptorLeOComandoDaFilaProcessaEARespostaVoltaPeloOutbox() {
        UUID ordem = UUID.randomUUID();
        ObjectNode comando = reservarDoContrato(5, ordem);
        sqsDoTeste.sendMessage(r -> r.queueUrl(urlComandos).messageGroupId(ordem.toString())
                .messageDeduplicationId(comando.get("messageId").asString()).messageBody(comando.toString()));

        assertThat(receptor.receberLote("execution-commands")).isEqualTo(1);
        assertThat(publicadorOutbox.publicarPendentes()).isEqualTo(1);

        assertThat(receber(urlComandos)).as("comando apagado da fila").isEmpty();
        assertThat(receber(urlRespostas)).singleElement()
                .satisfies(m -> assertThat(jsonMapper.readValue(m.body(), Envelope.class).type()).isEqualTo("PecasReservadas"));
        assertThat(reservas.buscarPorOrdemDeServico(ordem)).hasSize(2);
    }
}
