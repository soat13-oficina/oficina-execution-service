package br.com.oficina.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import br.com.oficina.execution.support.localstack.LocalStackBase;

/**
 * O que o overlay de nuvem configura (MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE): o pod so fica
 * pronto para receber trafego com o DynamoDB respondendo. Sem Docker, o teste e ignorado.
 */
@TestPropertySource(properties = "management.endpoint.health.group.readiness.include=readinessState,dynamoDb")
class ProntidaoComDynamoLocalStackTest extends LocalStackBase {

    @Autowired
    private WebApplicationContext contexto;

    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    }

    @Test
    void prontoQuandoATabelaDeControleExiste() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void foraDoArQuandoATabelaDeControleSumiu() throws Exception {
        dynamo.deleteTable(r -> r.tableName("oficina-teste-execution-controle"));
        try {
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        } finally {
            // As demais classes compartilham o container: devolve a tabela como o Terraform a cria.
            br.com.oficina.execution.support.dynamodb.TabelasDeTeste.criarControle(dynamo, "teste");
        }
        assertThat(dynamo.describeTable(r -> r.tableName("oficina-teste-execution-controle")).table().tableName())
                .isEqualTo("oficina-teste-execution-controle");
    }
}
