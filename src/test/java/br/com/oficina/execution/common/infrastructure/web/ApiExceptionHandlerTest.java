package br.com.oficina.execution.common.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException;
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void traduzCadaExcecaoDeNegocioNoStatusDoContrato() {
        var naoEncontrado = handler.handleResourceNotFound(new RecursoNaoEncontradoException("sumiu"));
        var regra = handler.handleBusinessRule(new RegraDeNegocioException("nao pode"));
        var conflito = handler.handleConflict(new ConflitoDeRecursoException("mudou"));

        assertThat(naoEncontrado.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(naoEncontrado.getBody().message()).isEqualTo("sumiu");
        assertThat(regra.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(regra.getBody().message()).isEqualTo("nao pode");
        assertThat(conflito.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflito.getBody().status()).isEqualTo(409);
    }

    @Test
    void argumentoInvalidoNaoVazaAMensagemInterna() {
        var resposta = handler.handleIllegalArgument(new IllegalArgumentException("detalhe interno"));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resposta.getBody().message()).doesNotContain("detalhe interno");
    }

    @Test
    void erroInesperadoResponde500ComMensagemGenerica() {
        var resposta = handler.handleUnexpected(new IllegalStateException("senha=123"));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resposta.getBody().message()).isEqualTo("Erro inesperado ao processar a requisicao.");
    }
}
