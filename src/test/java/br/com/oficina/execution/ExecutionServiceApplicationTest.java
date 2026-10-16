package br.com.oficina.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.oficina.execution.common.infrastructure.observabilidade.CorrelationIdFilter;
import br.com.oficina.execution.common.infrastructure.seguranca.TokensDeTeste;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Sobe o contexto inteiro e confere as regras de acesso que valem para todo endpoint futuro. */
@SpringBootTest
class ExecutionServiceApplicationTest {

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    private MockMvc mvc;

    @BeforeEach
    void configurar() {
        // MockMvc nao registra os filtros de servlet do contexto sozinho; o de correlacao entra a mao,
        // na mesma posicao que ocupa no servidor real (antes da seguranca).
        mvc = MockMvcBuilders.webAppContextSetup(contexto).addFilters(correlationIdFilter).apply(springSecurity()).build();
    }

    @Test
    void healthEhPublico() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void contratoOpenApiEhPublico() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Oficina - Execution Service"));
    }

    @Test
    void semTokenOuComTokenInvalidoResponde401() throws Exception {
        mvc.perform(get("/execucoes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/execucoes").header("Authorization", "Bearer " + TokensDeTeste.expirado()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void comTokenValidoPassaDaSegurancaECarregaCorrelationId() throws Exception {
        // Ainda nao ha endpoints de negocio: 404 prova que a seguranca deixou passar.
        mvc.perform(get("/execucoes")
                        .header("Authorization", "Bearer " + TokensDeTeste.cliente())
                        .header("X-Request-Id", "req-1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "req-1"));
    }

    @Test
    void metricasLevamATagDeAmbiente() {
        assertThat(registry.counter("execution.teste").getId().getTag("ambiente")).isEqualTo("teste");
    }
}
