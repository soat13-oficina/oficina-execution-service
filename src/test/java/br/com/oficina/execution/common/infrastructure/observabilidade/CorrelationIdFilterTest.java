package br.com.oficina.execution.common.infrastructure.observabilidade;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filtro = new CorrelationIdFilter();

    private String executar(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> noMdc = new AtomicReference<>();
        FilterChain chain = (req, res) -> noMdc.set(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID));
        filtro.doFilter(request, response, chain);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID)).as("MDC limpo ao final").isNull();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_CORRELATION_ID)).isEqualTo(noMdc.get());
        return noMdc.get();
    }

    @Test
    void usaOCorrelationIdRecebido() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_CORRELATION_ID, "abc-123");
        request.addHeader(CorrelationIdFilter.HEADER_REQUEST_ID, "ignorado");

        assertThat(executar(request, new MockHttpServletResponse())).isEqualTo("abc-123");
    }

    @Test
    void caiParaORequestIdDoApiGateway() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_CORRELATION_ID, " ");
        request.addHeader(CorrelationIdFilter.HEADER_REQUEST_ID, "Yx2kLh1MIAMEJbQ=");

        assertThat(executar(request, new MockHttpServletResponse())).isEqualTo("Yx2kLh1MIAMEJbQ=");
    }

    @Test
    void geraUmUuidQuandoNadaChega() throws Exception {
        String gerado = executar(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(UUID.fromString(gerado)).isNotNull();
    }
}
