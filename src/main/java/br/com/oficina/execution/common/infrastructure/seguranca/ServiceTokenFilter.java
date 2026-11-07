package br.com.oficina.execution.common.infrastructure.seguranca;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Protege {@code /internal/**} - chamadas entre servicos, que nunca passam pelo API Gateway - com o
 * header {@code X-Service-Token} (segredo {@code oficina/<env>/service-token}, ADR 0003). Sem token
 * configurado, nenhuma chamada interna e aceita.
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);

    public static final String HEADER = "X-Service-Token";
    private static final String PREFIXO = "/internal/";

    private final byte[] tokenEsperado;

    public ServiceTokenFilter(@Value("${seguranca.service-token:}") String tokenEsperado) {
        this.tokenEsperado = tokenEsperado == null ? new byte[0] : tokenEsperado.getBytes(StandardCharsets.UTF_8);
        if (this.tokenEsperado.length == 0) {
            log.warn("seguranca.service-token vazio: as rotas /internal/** vao recusar todas as chamadas.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIXO);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String recebido = request.getHeader(HEADER);
        boolean valido = tokenEsperado.length > 0 && recebido != null
                && MessageDigest.isEqual(tokenEsperado, recebido.getBytes(StandardCharsets.UTF_8));
        if (!valido) {
            log.warn("Chamada interna recusada: X-Service-Token ausente ou invalido. uri={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
