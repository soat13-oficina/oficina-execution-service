package br.com.oficina.execution.execucao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.common.infrastructure.mensageria.ConsumidorMensagens;
import br.com.oficina.execution.common.infrastructure.mensageria.PublicadorOutbox;
import br.com.oficina.execution.common.infrastructure.mensageria.sqs.ReceptorSqs;
import br.com.oficina.execution.estoque.domain.model.CategoriaPeca;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import br.com.oficina.execution.estoque.domain.repository.PecaInsumoRepository;
import br.com.oficina.execution.estoque.domain.repository.ReservaPecaRepository;
import br.com.oficina.execution.execucao.application.ExecucaoService;
import br.com.oficina.execution.execucao.application.ExecucaoService.DadosDiagnostico;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.model.Mecanico;
import br.com.oficina.execution.execucao.domain.model.PecaDiagnostico;
import br.com.oficina.execution.execucao.domain.model.ServicoDiagnostico;
import br.com.oficina.execution.execucao.domain.repository.ExecucaoRepository;
import br.com.oficina.execution.support.contratos.ContratosDeMensagens;
import br.com.oficina.execution.support.localstack.LocalStackBase;
import tools.jackson.databind.node.ObjectNode;

/**
 * A execucao contra DynamoDB e SQS de verdade (LocalStack): o documento da OS com o diagnostico aninhado, a
 * fila pelo GSI {@code fila-por-etapa}, a atomicidade de "muda a etapa + evento no outbox + baixa do
 * estoque", a corrida entre duas acoes sobre a mesma OS e o caminho comando -> resposta pelo SQS. Sem
 * Docker, o teste e ignorado.
 */
class ExecucaoDynamoLocalStackTest extends LocalStackBase {

    private static final ContratosDeMensagens CONTRATOS = new ContratosDeMensagens();
    private static final Mecanico ANA = new Mecanico("ana@oficina.com", "Ana");

    @Autowired
    private ExecucaoService servico;
    @Autowired
    private ExecucaoRepository execucoes;
    @Autowired
    private PecaInsumoRepository pecas;
    @Autowired
    private ReservaPecaRepository reservas;
    @Autowired
    private ConsumidorMensagens consumidor;
    @Autowired
    private PublicadorOutbox publicadorOutbox;
    @Autowired
    private ReceptorSqs receptor;

    private ObjectNode comando(String tipo, UUID ordem) {
        ObjectNode envelope = (ObjectNode) CONTRATOS.exemplo(tipo);
        envelope.put("messageId", UUID.randomUUID().toString());
        envelope.put("ordemServicoId", ordem.toString());
        return envelope;
    }

    private void enfileirarDiagnostico(UUID ordem) {
        consumidor.consumir(comando("EnfileirarDiagnostico", ordem).toString());
    }

    private DadosDiagnostico diagnostico(String... pecaIds) {
        return new DadosDiagnostico("Oleo vencido", List.of(new ServicoDiagnostico("Troca de oleo", new BigDecimal("120.50"))),
                java.util.Arrays.stream(pecaIds).map(id -> new PecaDiagnostico(id, 2)).toList());
    }

    private String cadastrarPeca(int estoque) {
        String id = UUID.randomUUID().toString();
        pecas.criar(new PecaInsumo(id, "Peca " + id, "Bosch", new BigDecimal("80.00"), estoque, 0, "REF", CategoriaPeca.FILTROS, null));
        return id;
    }

    private List<Envelope> eventosNoOutbox() {
        List<Envelope> eventos = new ArrayList<>();
        dynamo.scanPaginator(r -> r.tableName("oficina-teste-execution-controle")).items().forEach(item -> {
            if (item.get("pk").s().startsWith("OUTBOX#")) {
                eventos.add(jsonMapper.readValue(item.get("corpo").s(), Envelope.class));
            }
        });
        return eventos.stream().sorted(java.util.Comparator.comparing(Envelope::occurredAt)).toList();
    }

    @Test
    void enfileirarDiagnosticoGravaODocumentoNaFilaERespondeAPosicao() {
        UUID ordem = UUID.randomUUID();

        enfileirarDiagnostico(ordem);

        Execucao e = execucoes.buscarPorOrdemDeServico(ordem).orElseThrow();
        assertThat(e.etapa()).isEqualTo(Etapa.AGUARDANDO_DIAGNOSTICO);
        assertThat(e.versao()).isEqualTo(1);
        assertThat(e.veiculo().placa()).isNotBlank();
        assertThat(e.historico()).extracting(h -> h.etapa()).containsExactly(Etapa.AGUARDANDO_DIAGNOSTICO);
        assertThat(execucoes.listarPorEtapa(Etapa.AGUARDANDO_DIAGNOSTICO)).extracting(Execucao::ordemServicoId).containsExactly(ordem);
        assertThat(eventosNoOutbox()).singleElement().satisfies(evento -> {
            assertThat(evento.type()).isEqualTo("DiagnosticoEnfileirado");
            assertThat(CONTRATOS.validar(jsonMapper.writeValueAsString(evento))).isEmpty();
        });
    }

    @Test
    void mensagemRepetidaNaoDuplicaAExecucaoNemOEvento() {
        UUID ordem = UUID.randomUUID();
        ObjectNode enfileirar = comando("EnfileirarDiagnostico", ordem);

        consumidor.consumir(enfileirar.toString());
        consumidor.consumir(enfileirar.toString());
        enfileirarDiagnostico(ordem); // reenvio por timeout: messageId novo, mesma OS

        assertThat(execucoes.listarPorEtapa(Etapa.AGUARDANDO_DIAGNOSTICO)).hasSize(1);
        assertThat(eventosNoOutbox()).hasSize(2).allMatch(e -> e.type().equals("DiagnosticoEnfileirado"));
    }

    @Test
    void aFilaSaiEmOrdemDeChegadaEReabrirMandaParaOFim() throws Exception {
        UUID primeira = UUID.randomUUID();
        UUID segunda = UUID.randomUUID();
        UUID terceira = UUID.randomUUID();
        for (UUID ordem : List.of(primeira, segunda, terceira)) {
            enfileirarDiagnostico(ordem);
            Thread.sleep(5);
        }
        assertThat(servico.listar(Etapa.AGUARDANDO_DIAGNOSTICO)).extracting(Execucao::ordemServicoId)
                .containsExactly(primeira, segunda, terceira);

        servico.iniciarDiagnostico(primeira, ANA);
        servico.concluirDiagnostico(primeira, ANA, diagnostico());
        consumidor.consumir(comando("ReabrirDiagnostico", primeira).toString());

        assertThat(servico.listar(Etapa.AGUARDANDO_DIAGNOSTICO)).extracting(Execucao::ordemServicoId)
                .containsExactly(segunda, terceira, primeira);
        assertThat(servico.buscar(primeira).diagnostico()).isNull();
        assertThat(servico.buscar(primeira).servicosPrevistos()).extracting(ServicoDiagnostico::descricao).containsExactly("Troca de oleo");
    }

    @Test
    void cadaAcaoDoMecanicoMudaAEtapaEGravaOEventoNaMesmaTransacao() {
        UUID ordem = UUID.randomUUID();
        enfileirarDiagnostico(ordem);
        String peca = cadastrarPeca(10);

        servico.iniciarDiagnostico(ordem, ANA);
        servico.concluirDiagnostico(ordem, ANA, diagnostico(peca));
        consumidor.consumir(comando("EnfileirarExecucao", ordem).toString());
        servico.iniciarExecucao(ordem, ANA);
        servico.concluirExecucao(ordem, ANA);

        Execucao e = servico.buscar(ordem);
        assertThat(e.etapa()).isEqualTo(Etapa.CONCLUIDA);
        assertThat(e.versao()).isEqualTo(6);
        assertThat(e.diagnostico().servicos().getFirst().valorMaoDeObra()).isEqualByComparingTo("120.50");
        assertThat(e.diagnostico().pecas()).containsExactly(new PecaDiagnostico(peca, 2));
        assertThat(e.historico()).extracting(h -> h.etapa()).containsExactly(
                Etapa.AGUARDANDO_DIAGNOSTICO, Etapa.EM_DIAGNOSTICO, Etapa.DIAGNOSTICO_CONCLUIDO,
                Etapa.AGUARDANDO_EXECUCAO, Etapa.EM_EXECUCAO, Etapa.CONCLUIDA);
        List<Envelope> eventos = eventosNoOutbox();
        assertThat(eventos).extracting(Envelope::type).containsExactly(
                "DiagnosticoEnfileirado", "DiagnosticoIniciado", "DiagnosticoConcluido", "ExecucaoEnfileirada",
                "ExecucaoIniciada", "ExecucaoConcluida");
        assertThat(eventos).allSatisfy(evento -> assertThat(CONTRATOS.validar(jsonMapper.writeValueAsString(evento))).isEmpty());
    }

    @Test
    void concluirExecucaoBaixaOEstoqueDasPecasReservadasNaMesmaTransacao() {
        UUID ordem = UUID.randomUUID();
        String peca = cadastrarPeca(10);
        enfileirarDiagnostico(ordem);
        servico.iniciarDiagnostico(ordem, ANA);
        servico.concluirDiagnostico(ordem, ANA, diagnostico(peca));
        consumidor.consumir(comando("EnfileirarExecucao", ordem).toString());
        servico.iniciarExecucao(ordem, ANA);
        ObjectNode reservar = comando("ReservarPecas", ordem);
        ((ObjectNode) reservar.get("payload")).putArray("itens").addObject().put("pecaInsumoId", peca).put("quantidade", 3);
        consumidor.consumir(reservar.toString());
        assertThat(pecas.buscarPorId(peca).orElseThrow().getQuantidadeReservada()).isEqualTo(3);

        servico.concluirExecucao(ordem, ANA);

        PecaInsumo depois = pecas.buscarPorId(peca).orElseThrow();
        assertThat(depois.getQuantidadeEstoque()).isEqualTo(7);
        assertThat(depois.getQuantidadeReservada()).isZero();
        assertThat(depois.getQuantidadeDisponivel()).isEqualTo(7);
        assertThat(reservas.buscarPorOrdemDeServico(ordem)).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ReservaPeca.Status.CONSUMIDA);
            assertThat(r.quantidade()).isEqualTo(3);
        });
        Envelope concluida = eventosNoOutbox().stream().filter(e -> e.type().equals("ExecucaoConcluida")).findFirst().orElseThrow();
        assertThat(concluida.payload().get("pecasConsumidas").get(0).get("quantidade").asInt()).isEqualTo(3);
    }

    @Test
    void abortarDeixaAsReservasEACompensacaoDaSagaAsLibera() {
        UUID ordem = UUID.randomUUID();
        String peca = cadastrarPeca(10);
        enfileirarDiagnostico(ordem);
        servico.iniciarDiagnostico(ordem, ANA);
        servico.concluirDiagnostico(ordem, ANA, diagnostico(peca));
        consumidor.consumir(comando("EnfileirarExecucao", ordem).toString());
        servico.iniciarExecucao(ordem, ANA);
        ObjectNode reservar = comando("ReservarPecas", ordem);
        ((ObjectNode) reservar.get("payload")).putArray("itens").addObject().put("pecaInsumoId", peca).put("quantidade", 3);
        consumidor.consumir(reservar.toString());

        servico.abortarExecucao(ordem, ANA, "Peca com defeito");
        assertThat(pecas.buscarPorId(peca).orElseThrow().getQuantidadeReservada()).isEqualTo(3);
        consumidor.consumir(comando("LiberarReserva", ordem).toString());

        assertThat(servico.buscar(ordem).etapa()).isEqualTo(Etapa.ABORTADA);
        assertThat(pecas.buscarPorId(peca).orElseThrow().getQuantidadeDisponivel()).isEqualTo(10);
        assertThat(eventosNoOutbox()).extracting(Envelope::type).contains("ExecucaoFalhou", "ReservaLiberada");
    }

    @Test
    void cancelarExecucaoMudaAEtapaEConfirma() {
        UUID ordem = UUID.randomUUID();
        enfileirarDiagnostico(ordem);

        consumidor.consumir(comando("CancelarExecucao", ordem).toString());

        assertThat(servico.buscar(ordem).etapa()).isEqualTo(Etapa.CANCELADA);
        assertThat(execucoes.listarPorEtapa(Etapa.AGUARDANDO_DIAGNOSTICO)).isEmpty();
        assertThat(eventosNoOutbox()).extracting(Envelope::type).containsExactly("DiagnosticoEnfileirado", "ExecucaoCancelada");
    }

    @Test
    void duasAcoesSimultaneasNaMesmaOsNaoSeAtropelam() throws Exception {
        UUID ordem = UUID.randomUUID();
        enfileirarDiagnostico(ordem);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);

        List<Future<Throwable>> resultados = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            resultados.add(pool.submit(() -> {
                largada.await();
                try {
                    servico.iniciarDiagnostico(ordem, ANA);
                    return null;
                } catch (ConflitoDeRecursoException | RegraDeNegocioException e) {
                    return e; // perdeu a corrida (409) ou chegou depois da outra confirmar (400)
                }
            }));
        }
        largada.countDown();
        int sucessos = 0;
        for (Future<Throwable> f : resultados) {
            sucessos += f.get() == null ? 1 : 0;
        }
        pool.shutdown();

        assertThat(sucessos).as("so uma inicia o diagnostico").isEqualTo(1);
        assertThat(servico.buscar(ordem).historico().stream().filter(h -> h.etapa() == Etapa.EM_DIAGNOSTICO)).hasSize(1);
        assertThat(eventosNoOutbox().stream().filter(e -> e.type().equals("DiagnosticoIniciado"))).hasSize(1);
    }

    @Test
    void acaoRecusadaNaoDeixaEventoNemMudanca() {
        UUID ordem = UUID.randomUUID();
        enfileirarDiagnostico(ordem);

        assertThatThrownBy(() -> servico.concluirExecucao(ordem, ANA)).isInstanceOf(RegraDeNegocioException.class);

        assertThat(servico.buscar(ordem).etapa()).isEqualTo(Etapa.AGUARDANDO_DIAGNOSTICO);
        assertThat(eventosNoOutbox()).hasSize(1);
    }

    @Test
    void receptorLeOComandoDaFilaEARespostaVoltaPeloOutboxParaOSqs() {
        UUID ordem = UUID.randomUUID();
        ObjectNode enfileirar = comando("EnfileirarDiagnostico", ordem);
        sqsDoTeste.sendMessage(r -> r.queueUrl(urlComandos).messageGroupId(ordem.toString())
                .messageDeduplicationId(enfileirar.get("messageId").asString()).messageBody(enfileirar.toString()));

        assertThat(receptor.receberLote("execution-commands")).isEqualTo(1);
        assertThat(publicadorOutbox.publicarPendentes()).isEqualTo(1);

        assertThat(receber(urlComandos)).as("comando apagado da fila").isEmpty();
        assertThat(receber(urlRespostas)).singleElement().satisfies(m -> {
            Envelope resposta = jsonMapper.readValue(m.body(), Envelope.class);
            assertThat(resposta.type()).isEqualTo("DiagnosticoEnfileirado");
            assertThat(resposta.causationId()).hasToString(enfileirar.get("messageId").asString());
        });
        assertThat(execucoes.buscarPorOrdemDeServico(ordem)).isPresent();
    }
}
