package br.com.oficina.execution.estoque.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import br.com.oficina.execution.common.infrastructure.observabilidade.CorrelationIdFilter;
import br.com.oficina.execution.common.infrastructure.seguranca.TokensDeTeste;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.support.persistence.EstoqueEmMemoriaConfig;
import br.com.oficina.execution.support.persistence.PecaInsumoRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.ReservaPecaRepositoryEmMemoria;

/** Catalogo e estoque pela API: seguranca, validacao, formato das respostas e erros. */
@SpringBootTest
@Import(EstoqueEmMemoriaConfig.class)
class PecaInsumoControllerTest {

    private static final String CADASTRO = """
            {"descricao":"Filtro de oleo","marca":"Bosch","preco":45.90,"quantidadeEstoque":10,
             "codigoReferencia":"OB0986B01044","categoria":"FILTROS"}""";

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private PecaInsumoRepositoryEmMemoria pecas;

    @Autowired
    private ReservaPecaRepositoryEmMemoria reservas;

    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        pecas.limpar();
        reservas.limpar();
        mvc = MockMvcBuilders.webAppContextSetup(contexto).addFilters(correlationIdFilter).apply(springSecurity()).build();
    }

    private static MockHttpServletRequestBuilder comoFuncionario(MockHttpServletRequestBuilder requisicao) {
        return requisicao.header("Authorization", "Bearer " + TokensDeTeste.funcionarioFase3());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder requisicao, String corpo) {
        return comoFuncionario(requisicao).contentType(MediaType.APPLICATION_JSON).content(corpo);
    }

    @Test
    void semTokenResponde401ECompapelDeClienteResponde403() throws Exception {
        mvc.perform(get("/pecas-insumos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/pecas-insumos").header("Authorization", "Bearer " + TokensDeTeste.cliente()))
                .andExpect(status().isForbidden());
    }

    @Test
    void cadastraEDevolveALocalizacaoDaPeca() throws Exception {
        String location = mvc.perform(json(post("/pecas-insumos"), CADASTRO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/pecas-insumos/")))
                .andReturn().getResponse().getHeader("Location");

        String id = location.substring(location.lastIndexOf('/') + 1);
        mvc.perform(comoFuncionario(get("/pecas-insumos/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.descricao").value("Filtro de oleo"))
                .andExpect(jsonPath("$.quantidadeEstoque").value(10))
                .andExpect(jsonPath("$.quantidadeReservada").value(0))
                .andExpect(jsonPath("$.quantidadeDisponivel").value(10))
                .andExpect(jsonPath("$.categoria").value("FILTROS"));
    }

    @Test
    void cadastroInvalidoListaOsCamposComErro() throws Exception {
        mvc.perform(json(post("/pecas-insumos"), """
                        {"descricao":"","marca":"Bosch","preco":0,"quantidadeEstoque":-1,"codigoReferencia":"X"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[*].campo").value(org.hamcrest.Matchers.hasItems("descricao", "preco", "quantidadeEstoque", "categoria")));
    }

    @Test
    void corpoQueNaoEJsonResponde400() throws Exception {
        mvc.perform(json(post("/pecas-insumos"), "{nao e json")).andExpect(status().isBadRequest());
    }

    @Test
    void buscaDeIdInexistenteResponde404ComMensagem() throws Exception {
        mvc.perform(comoFuncionario(get("/pecas-insumos/nao-existe")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("nao-existe")));
    }

    @Test
    void listaComFiltros() throws Exception {
        pecas.semear("a", "Filtro", "10.00", 5);
        pecas.semear("b", "Pastilha", "20.00", 5);
        pecas.reservar("b", 2);

        mvc.perform(comoFuncionario(get("/pecas-insumos"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(comoFuncionario(get("/pecas-insumos").param("possuiReserva", "true")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("b"));
        mvc.perform(comoFuncionario(get("/pecas-insumos").param("marca", "Marca").param("categoria", "FILTROS")))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void categoriaInvalidaNaListagemResponde400() throws Exception {
        mvc.perform(comoFuncionario(get("/pecas-insumos").param("categoria", "NAO_EXISTE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A requisicao enviada e invalida."));
    }

    @Test
    void alteraIgnorandoAQuantidadeReservadaDoCorpo() throws Exception {
        pecas.semear("a", "Filtro", "10.00", 10);
        pecas.reservar("a", 4);

        mvc.perform(json(put("/pecas-insumos/a"), """
                        {"descricao":"Filtro novo","marca":"Mann","preco":12.50,"quantidadeEstoque":12,"quantidadeReservada":0,
                         "codigoReferencia":"R2","categoria":"FREIOS"}"""))
                .andExpect(status().isNoContent());

        PecaInsumo alterada = pecas.buscarPorId("a").orElseThrow();
        assertThat(alterada.getDescricao()).isEqualTo("Filtro novo");
        assertThat(alterada.getQuantidadeReservada()).isEqualTo(4);
        assertThat(alterada.getQuantidadeEstoque()).isEqualTo(12);
    }

    @Test
    void alterarEstoqueAbaixoDoReservadoResponde400() throws Exception {
        pecas.semear("a", "Filtro", "10.00", 10);
        pecas.reservar("a", 4);

        mvc.perform(json(put("/pecas-insumos/a"), CADASTRO.replace("\"quantidadeEstoque\":10", "\"quantidadeEstoque\":3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("nao pode exceder o estoque")));
    }

    @Test
    void alterarInexistenteResponde404() throws Exception {
        mvc.perform(json(put("/pecas-insumos/nao-existe"), CADASTRO)).andExpect(status().isNotFound());
    }

    @Test
    void excluiPecaSemReservaERecusaComReserva() throws Exception {
        pecas.semear("livre", "Filtro", "10.00", 5);
        pecas.semear("presa", "Pastilha", "10.00", 5);
        pecas.reservar("presa", 1);

        mvc.perform(comoFuncionario(delete("/pecas-insumos/livre"))).andExpect(status().isNoContent());
        mvc.perform(comoFuncionario(delete("/pecas-insumos/presa")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reserva ativa")));
        assertThat(pecas.buscarPorId("livre")).isEmpty();
        assertThat(pecas.buscarPorId("presa")).isPresent();
    }

    @Test
    void entradaESaidaDeEstoque() throws Exception {
        pecas.semear("a", "Filtro", "10.00", 10);

        mvc.perform(json(post("/pecas-insumos/a/adicionar-estoque"), "{\"quantidade\":5}")).andExpect(status().isNoContent());
        mvc.perform(json(post("/pecas-insumos/a/remover-estoque"), "{\"quantidade\":3}")).andExpect(status().isNoContent());

        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeEstoque()).isEqualTo(12);
    }

    @Test
    void quantidadeInvalidaOuAcimaDoDisponivelResponde400() throws Exception {
        pecas.semear("a", "Filtro", "10.00", 2);

        mvc.perform(json(post("/pecas-insumos/a/adicionar-estoque"), "{\"quantidade\":0}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/pecas-insumos/a/remover-estoque"), "{\"quantidade\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("insuficiente")));
    }

    @Test
    void naoExistemEndpointsParaReservarLiberarOuConsumirPorFora() throws Exception {
        pecas.semear("a", "Filtro", "10.00", 10);

        mvc.perform(json(post("/pecas-insumos/a/reservar"), "{\"quantidade\":1}")).andExpect(status().isNotFound());
        assertThat(pecas.buscarPorId("a").orElseThrow().getQuantidadeReservada()).isZero();
    }
}
