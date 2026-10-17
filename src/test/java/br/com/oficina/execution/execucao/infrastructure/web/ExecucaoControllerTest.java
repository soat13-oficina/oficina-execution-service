package br.com.oficina.execution.execucao.infrastructure.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import br.com.oficina.execution.common.infrastructure.seguranca.TokensDeTeste;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.model.Veiculo;
import br.com.oficina.execution.support.mensageria.PublicadorMensagensEmMemoria;
import br.com.oficina.execution.support.persistence.EstoqueEmMemoriaConfig;
import br.com.oficina.execution.support.persistence.ExecucaoRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.PecaInsumoRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.ReservaPecaRepositoryEmMemoria;

/** O trabalho do mecanico pela API: seguranca, validacao, formato das respostas, erros e eventos publicados. */
@SpringBootTest
@Import(EstoqueEmMemoriaConfig.class)
class ExecucaoControllerTest {

    private static final UUID ORDEM = UUID.fromString("3c9a1b2d-4e5f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final String DIAGNOSTICO = """
            {"descricaoDiagnostico":"Oleo vencido",
             "servicos":[{"descricao":"Troca de oleo","valorMaoDeObra":120.00}],
             "pecas":[{"pecaInsumoId":"a","quantidade":2}]}""";

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private ExecucaoRepositoryEmMemoria execucoes;
    @Autowired
    private PecaInsumoRepositoryEmMemoria pecas;
    @Autowired
    private ReservaPecaRepositoryEmMemoria reservas;
    @Autowired
    private PublicadorMensagensEmMemoria publicador;

    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        execucoes.limpar();
        pecas.limpar();
        reservas.limpar();
        publicador.limpar();
        pecas.semear("a", "Filtro", "80.00", 10);
        execucoes.criar(Execucao.nova(ORDEM, UUID.randomUUID(), "OS-1", new Veiculo("ABC1D23", "Toyota", "Corolla", 2020),
                List.of(), List.of(), Instant.parse("2026-10-17T12:00:00Z")));
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    }

    private static MockHttpServletRequestBuilder comoFuncionario(MockHttpServletRequestBuilder requisicao) {
        return requisicao.header("Authorization", "Bearer " + TokensDeTeste.emitir("ana@oficina.com", Map.of("nome", "Ana Mecanica")));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder requisicao, String corpo) {
        return comoFuncionario(requisicao).contentType(MediaType.APPLICATION_JSON).content(corpo);
    }

    @Test
    void semTokenResponde401EClienteResponde403() throws Exception {
        mvc.perform(get("/execucoes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/execucoes/" + ORDEM).header("Authorization", "Bearer " + TokensDeTeste.cliente()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/execucoes/" + ORDEM + "/iniciar").header("Authorization", "Bearer " + TokensDeTeste.cliente()))
                .andExpect(status().isForbidden());
    }

    @Test
    void listaAFilaEConsultaUmaExecucao() throws Exception {
        mvc.perform(comoFuncionario(get("/execucoes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ordemServicoId").value(ORDEM.toString()))
                .andExpect(jsonPath("$[0].etapa").value("AGUARDANDO_DIAGNOSTICO"));
        mvc.perform(comoFuncionario(get("/execucoes").param("etapa", "EM_EXECUCAO")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(comoFuncionario(get("/execucoes/" + ORDEM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numeroOrdemServico").value("OS-1"))
                .andExpect(jsonPath("$.veiculo.placa").value("ABC1D23"))
                .andExpect(jsonPath("$.diagnostico").doesNotExist())
                .andExpect(jsonPath("$.historico[0].etapa").value("AGUARDANDO_DIAGNOSTICO"));
    }

    @Test
    void etapaInvalidaResponde400EOsSemExecucaoResponde404() throws Exception {
        mvc.perform(comoFuncionario(get("/execucoes").param("etapa", "NAO_EXISTE"))).andExpect(status().isBadRequest());
        mvc.perform(comoFuncionario(get("/execucoes/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(comoFuncionario(get("/execucoes/nao-e-uuid"))).andExpect(status().isBadRequest());
    }

    @Test
    void fluxoCompletoDoMecanicoPublicaUmEventoPorAcao() throws Exception {
        mvc.perform(comoFuncionario(post("/execucoes/" + ORDEM + "/diagnostico/iniciar")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etapa").value("EM_DIAGNOSTICO"))
                .andExpect(jsonPath("$.mecanicoDiagnostico.id").value("ana@oficina.com"))
                .andExpect(jsonPath("$.mecanicoDiagnostico.nome").value("Ana Mecanica"));
        mvc.perform(json(post("/execucoes/" + ORDEM + "/diagnostico/concluir"), DIAGNOSTICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etapa").value("DIAGNOSTICO_CONCLUIDO"))
                .andExpect(jsonPath("$.diagnostico.servicos[0].valorMaoDeObra").value(120.00))
                .andExpect(jsonPath("$.diagnostico.pecas[0].quantidade").value(2));
        // A fila de execucao so abre por comando da Saga (orcamento aprovado e pago).
        execucoes.atualizar(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().enfileirarExecucao(Instant.now()));
        mvc.perform(comoFuncionario(post("/execucoes/" + ORDEM + "/iniciar")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("EM_EXECUCAO"));
        mvc.perform(comoFuncionario(post("/execucoes/" + ORDEM + "/concluir")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("CONCLUIDA"));

        org.assertj.core.api.Assertions.assertThat(publicador.tipos())
                .containsExactly("DiagnosticoIniciado", "DiagnosticoConcluido", "ExecucaoIniciada", "ExecucaoConcluida");
    }

    @Test
    void abortarExigeMotivoEPublicaExecucaoFalhou() throws Exception {
        mvc.perform(comoFuncionario(post("/execucoes/" + ORDEM + "/diagnostico/iniciar")));
        mvc.perform(json(post("/execucoes/" + ORDEM + "/diagnostico/concluir"), DIAGNOSTICO));
        execucoes.atualizar(execucoes.buscarPorOrdemDeServico(ORDEM).orElseThrow().enfileirarExecucao(Instant.now()));
        mvc.perform(comoFuncionario(post("/execucoes/" + ORDEM + "/iniciar")));
        publicador.limpar();

        mvc.perform(json(post("/execucoes/" + ORDEM + "/abortar"), "{\"motivo\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/execucoes/" + ORDEM + "/abortar"), "{\"motivo\":\"Peca com defeito\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("ABORTADA"));

        org.assertj.core.api.Assertions.assertThat(publicador.tipos()).containsExactly("ExecucaoFalhou");
    }

    @Test
    void acaoForaDaEtapaResponde400() throws Exception {
        mvc.perform(comoFuncionario(post("/execucoes/" + ORDEM + "/concluir")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("EM_EXECUCAO")));
    }

    @Test
    void diagnosticoInvalidoListaOsCampos() throws Exception {
        mvc.perform(comoFuncionario(post("/execucoes/" + ORDEM + "/diagnostico/iniciar")));

        mvc.perform(json(post("/execucoes/" + ORDEM + "/diagnostico/concluir"), """
                        {"descricaoDiagnostico":"","servicos":[],"pecas":[{"pecaInsumoId":"a","quantidade":0}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[*].campo").value(org.hamcrest.Matchers.hasItems(
                        "descricaoDiagnostico", "servicos", "pecas[0].quantidade")));
        mvc.perform(json(post("/execucoes/" + ORDEM + "/diagnostico/concluir"), """
                        {"descricaoDiagnostico":"x","servicos":[{"descricao":"s","valorMaoDeObra":-1}],"pecas":[]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void diagnosticoComPecaForaDoCatalogoResponde400() throws Exception {
        mvc.perform(comoFuncionario(post("/execucoes/" + ORDEM + "/diagnostico/iniciar")));

        mvc.perform(json(post("/execucoes/" + ORDEM + "/diagnostico/concluir"), DIAGNOSTICO.replace("\"a\"", "\"fantasma\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("fantasma")));
    }

    @Test
    void usuarioSemNomeNaClaimUsaOEmailComoNome() throws Exception {
        mvc.perform(post("/execucoes/" + ORDEM + "/diagnostico/iniciar")
                        .header("Authorization", "Bearer " + TokensDeTeste.funcionarioFase3()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mecanicoDiagnostico.nome").value("ana@oficina.com"));
    }
}
