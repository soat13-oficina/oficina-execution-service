package br.com.oficina.execution.execucao.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.UnidadeDeTrabalho;
import br.com.oficina.execution.execucao.domain.model.Diagnostico;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.model.Mecanico;
import br.com.oficina.execution.execucao.domain.model.PecaDiagnostico;
import br.com.oficina.execution.execucao.domain.model.ServicoDiagnostico;
import br.com.oficina.execution.execucao.domain.model.Veiculo;
import br.com.oficina.execution.support.dynamodb.DynamoDbClientFalso;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/** O adapter contra o duplo do cliente: o documento que grava, a condicao de cada escrita e como le a fila. */
class DynamoExecucaoRepositoryTest {

    private static final TableSchema<ExecucaoItem> ESQUEMA = TableSchema.fromBean(ExecucaoItem.class);
    private static final Instant T0 = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID ORDEM = UUID.fromString("3c9a1b2d-4e5f-4a7b-8c9d-0e1f2a3b4c5d");

    private DynamoDbClientFalso dynamo;
    private UnidadeDeTrabalho unidade;
    private DynamoExecucaoRepository repositorio;

    @BeforeEach
    void preparar() {
        dynamo = new DynamoDbClientFalso();
        unidade = new UnidadeDeTrabalho(dynamo);
        repositorio = new DynamoExecucaoRepository(
                DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build(), unidade, new PropriedadesDynamo("hml", null, null));
    }

    private static Execucao completa() {
        return Execucao.nova(ORDEM, UUID.randomUUID(), "OS-1", new Veiculo("ABC1D23", "Toyota", "Corolla", 2020),
                        List.of(new ServicoDiagnostico("Revisao", new BigDecimal("100.00"))), List.of(new PecaDiagnostico("a", 1)), T0)
                .iniciarDiagnostico(new Mecanico("ana@oficina.com", "Ana"), T0.plusSeconds(1))
                .concluirDiagnostico(new Diagnostico("Oleo", List.of(new ServicoDiagnostico("Troca", new BigDecimal("120.50"))),
                        List.of(new PecaDiagnostico("b", 2))), T0.plusSeconds(2))
                .enfileirarExecucao(T0.plusSeconds(3))
                .iniciarExecucao(new Mecanico("beto@oficina.com", "Beto"), T0.plusSeconds(4));
    }

    @Test
    void documentoIdaEVoltaSemPerderNada() {
        Execucao original = completa();

        Execucao volta = ExecucaoItem.de(original, 7).paraDominio();

        assertThat(volta).usingRecursiveComparison().ignoringFields("versao").isEqualTo(original);
        assertThat(volta.versao()).isEqualTo(7);
    }

    @Test
    void execucaoRecemAbertaIdaEVoltaComCamposOpcionaisNulos() {
        Execucao original = Execucao.nova(ORDEM, null, "OS-2", new Veiculo("ABC1D23", "Fiat", "Uno", null), null, null, T0);

        Execucao volta = ExecucaoItem.de(original, 1).paraDominio();

        assertThat(volta.sagaId()).isNull();
        assertThat(volta.diagnostico()).isNull();
        assertThat(volta.mecanicoDiagnostico()).isNull();
        assertThat(volta.veiculo().ano()).isNull();
        assertThat(volta.servicosPrevistos()).isEmpty();
    }

    @Test
    void ChaveDaFilaTemLarguraFixaParaOrdenarComoTexto() {
        String fracionado = ExecucaoItem.de(completa().cancelar(T0.plusMillis(500)), 1).getEntrouNaEtapaEm();
        String redondo = ExecucaoItem.de(completa().cancelar(T0.plusSeconds(1)), 1).getEntrouNaEtapaEm();

        assertThat(fracionado).isEqualTo("2026-10-17T12:00:00.500000Z");
        assertThat(redondo).isEqualTo("2026-10-17T12:00:01.000000Z");
        assertThat(fracionado.compareTo(redondo)).isNegative();
    }

    @Test
    void criarExigeQueAOsNaoTenhaExecucao() {
        unidade.executar(() -> repositorio.criar(completa()));

        var put = dynamo.transacoes.getFirst().transactItems().getFirst().put();
        assertThat(put.tableName()).isEqualTo("oficina-hml-execucoes");
        assertThat(put.conditionExpression()).isEqualTo("attribute_not_exists(ordemServicoId)");
        assertThat(put.item().get("versao").n()).isEqualTo("1");
        assertThat(put.item().get("etapa").s()).isEqualTo("EM_EXECUCAO");
        assertThat(put.item().get("ordemServicoId").s()).isEqualTo(ORDEM.toString());
        assertThat(put.item().get("diagnostico").m()).containsKeys("descricao", "servicos", "pecas");
        assertThat(put.item().get("historico").l()).hasSize(5);
    }

    @Test
    void atualizarGravaAProximaVersaoCondicionadaALida() {
        Execucao lida = new Execucao(ORDEM, null, "OS-1", Etapa.EM_EXECUCAO, completa().veiculo(), List.of(), List.of(), null, null, null,
                completa().historico(), T0, T0, T0, 4);

        unidade.executar(() -> repositorio.atualizar(lida));

        var put = dynamo.transacoes.getFirst().transactItems().getFirst().put();
        assertThat(put.conditionExpression()).isEqualTo("versao = :lida");
        assertThat(put.expressionAttributeValues().get(":lida").n()).isEqualTo("4");
        assertThat(put.item().get("versao").n()).isEqualTo("5");
    }

    @Test
    void escreverForaDeUmaTransacaoFalhaAlto() {
        assertThatThrownBy(() -> repositorio.criar(completa())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repositorio.atualizar(completa())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void leOPorOrdemDeServicoComConsistenciaForte() {
        dynamo.itemLido = ESQUEMA.itemToMap(ExecucaoItem.de(completa(), 3), true);

        Execucao lida = repositorio.buscarPorOrdemDeServico(ORDEM).orElseThrow();

        assertThat(lida.etapa()).isEqualTo(Etapa.EM_EXECUCAO);
        assertThat(lida.versao()).isEqualTo(3);
        assertThat(dynamo.leituras).singleElement().satisfies(r -> {
            assertThat(r.tableName()).isEqualTo("oficina-hml-execucoes");
            assertThat(r.consistentRead()).isTrue();
        });
        dynamo.itemLido = java.util.Map.of();
        assertThat(repositorio.buscarPorOrdemDeServico(UUID.randomUUID())).isEmpty();
    }

    @Test
    void listaAFilaPeloIndiceDaEtapaEmOrdemCrescente() {
        dynamo.itensConsultados = List.of(ESQUEMA.itemToMap(ExecucaoItem.de(completa(), 1), true));

        List<Execucao> fila = repositorio.listarPorEtapa(Etapa.AGUARDANDO_EXECUCAO);

        assertThat(fila).hasSize(1);
        assertThat(dynamo.consultas).singleElement().satisfies(q -> {
            assertThat(q.tableName()).isEqualTo("oficina-hml-execucoes");
            assertThat(q.indexName()).isEqualTo("fila-por-etapa");
            assertThat(q.scanIndexForward()).isNotEqualTo(false);
            assertThat(q.keyConditions().toString() + q.expressionAttributeValues()).contains("AGUARDANDO_EXECUCAO");
        });
    }
}
