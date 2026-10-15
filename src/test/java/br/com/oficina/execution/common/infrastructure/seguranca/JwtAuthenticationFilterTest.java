package br.com.oficina.execution.common.infrastructure.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private final JwtAuthenticationFilter filtro = new JwtAuthenticationFilter(new JwtValidador(TokensDeTeste.SEGREDO));

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    private Authentication filtrar(String authorization) throws Exception {
        var request = new MockHttpServletRequest("GET", "/execucoes");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        var chain = new MockFilterChain();
        filtro.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("a cadeia sempre continua").isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void bearerValidoAutenticaComOsPapeisDoToken() throws Exception {
        Authentication autenticacao = filtrar("Bearer " + TokensDeTeste.cliente());

        assertThat(autenticacao.getPrincipal()).isInstanceOf(UsuarioAutenticado.class);
        assertThat(autenticacao.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CLIENTE");
    }

    @Test
    void semCabecalhoOutroEsquemaOuTokenInvalidoSegueAnonimo() throws Exception {
        assertThat(filtrar(null)).isNull();
        assertThat(filtrar("Basic dXNlcjpzZW5oYQ==")).isNull();
        assertThat(filtrar("Bearer invalido")).isNull();
    }

    @Test
    void naoSobrescreveAutenticacaoJaPresente() throws Exception {
        var existente = new TestingAuthenticationToken("outro", null);
        SecurityContextHolder.getContext().setAuthentication(existente);

        assertThat(filtrar("Bearer " + TokensDeTeste.cliente())).isSameAs(existente);
    }
}
