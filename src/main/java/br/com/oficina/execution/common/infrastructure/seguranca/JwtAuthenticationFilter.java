package br.com.oficina.execution.common.infrastructure.seguranca;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica a requisicao pelo {@code Authorization: Bearer <jwt>}. Token ausente ou invalido
 * segue como anonimo; a decisao de recusar fica com as regras do {@link SecurityConfig}.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIXO_BEARER = "Bearer ";

    private final JwtValidador validador;

    public JwtAuthenticationFilter(JwtValidador validador) {
        this.validador = validador;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String cabecalho = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (cabecalho != null && cabecalho.startsWith(PREFIXO_BEARER)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            validador.validar(cabecalho.substring(PREFIXO_BEARER.length())).ifPresent(autenticacao -> {
                var token = new UsernamePasswordAuthenticationToken(
                        autenticacao.usuario(),
                        null,
                        autenticacao.papeis().stream().map(papel -> new SimpleGrantedAuthority("ROLE_" + papel)).toList());
                token.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(token);
            });
        }
        filterChain.doFilter(request, response);
    }
}
