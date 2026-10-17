package br.com.oficina.execution.estoque.infrastructure.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.IntStream;

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
import br.com.oficina.execution.support.persistence.EstoqueEmMemoriaConfig;
import br.com.oficina.execution.support.persistence.PecaInsumoRepositoryEmMemoria;

/** Contrato REST interno (docs/contratos/rest-interno.md): consulta de pecas pelo Billing. */
@SpringBootTest
@Import(EstoqueEmMemoriaConfig.class)
class ConsultaInternaPecasControllerTest {

    private static final String ROTA = "/internal/pecas-insumos/consulta";

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private PecaInsumoRepositoryEmMemoria pecas;

    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        pecas.limpar();
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    }

    private static MockHttpServletRequestBuilder consulta(String corpo) {
        return post(ROTA).header("X-Service-Token", "token-de-servico-de-teste")
                .contentType(MediaType.APPLICATION_JSON).content(corpo);
    }

    @Test
    void semTokenDeServicoOuComTokenErradoResponde401() throws Exception {
        mvc.perform(post(ROTA).contentType(MediaType.APPLICATION_JSON).content("{\"pecaInsumoIds\":[\"a\"]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ROTA).header("X-Service-Token", "errado")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pecaInsumoIds\":[\"a\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeFuncionarioNaoSubstituiOTokenDeServico() throws Exception {
        mvc.perform(post(ROTA).header("Authorization", "Bearer " + TokensDeTeste.funcionarioFase3())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pecaInsumoIds\":[\"a\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void devolvePrecoComDuasCasasNaOrdemPedidaEOsInexistentes() throws Exception {
        pecas.semear("a", "Filtro de oleo", "80", 3);
        pecas.semear("b", "Pastilha", "19.9", 3);

        mvc.perform(consulta("{\"pecaInsumoIds\":[\"b\",\"fantasma\",\"a\",\"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pecas", hasSize(2)))
                .andExpect(jsonPath("$.pecas[0].pecaInsumoId").value("b"))
                .andExpect(jsonPath("$.pecas[0].preco").value("19.90"))
                .andExpect(jsonPath("$.pecas[1].descricao").value("Filtro de oleo"))
                .andExpect(jsonPath("$.pecas[1].preco").value("80.00"))
                .andExpect(jsonPath("$.inexistentes[0]").value("fantasma"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void listaVaziaOuAusenteOuGrandeDemaisResponde400() throws Exception {
        mvc.perform(consulta("{\"pecaInsumoIds\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(consulta("{}")).andExpect(status().isBadRequest());

        String ids = IntStream.rangeClosed(0, 200).mapToObj(i -> "\"id-" + i + "\"").reduce((x, y) -> x + "," + y).orElseThrow();
        mvc.perform(consulta("{\"pecaInsumoIds\":[" + ids + "]}")).andExpect(status().isBadRequest());
    }

    @Test
    void semCorpoResponde400() throws Exception {
        mvc.perform(post(ROTA).header("X-Service-Token", "token-de-servico-de-teste")).andExpect(status().isBadRequest());
    }
}
