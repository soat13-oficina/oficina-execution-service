package br.com.oficina.execution.common.infrastructure.seguranca;

import br.com.oficina.execution.common.infrastructure.seguranca.UsuarioAutenticado.TipoUsuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Valida o JWT (assinatura HMAC e expiracao) e traduz as claims em {@link UsuarioAutenticado}.
 *
 * <p>So valida — quem emite e o OS Service ({@code POST /api/auth/login}) e a Lambda
 * {@code token} do {@code oficina-lambda-auth}. Regras de tipo:
 * <ul>
 *   <li>{@code tipo = CLIENTE}: token da Lambda; traz {@code clienteId} e {@code nome}.</li>
 *   <li>{@code tipo = FUNCIONARIO} <b>ou ausente</b>: token de funcionario do OS Service. A
 *       ausencia e aceita porque os tokens da Fase 3 ainda nao trazem a claim.</li>
 *   <li>qualquer outro valor: token recusado.</li>
 * </ul>
 */
@Component
public class JwtValidador {

    private static final String CLAIM_TIPO = "tipo";

    private final SecretKey chave;

    public JwtValidador(@Value("${jwt.secret}") String segredo) {
        this.chave = Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return o usuario do token, ou vazio se o token for invalido, expirado, sem subject ou de
     *         tipo desconhecido. Nunca lanca: token ruim equivale a requisicao anonima.
     */
    public Optional<Autenticacao> validar(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(chave).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        return tipo(claims.get(CLAIM_TIPO, String.class))
                .map(tipo -> new Autenticacao(
                        new UsuarioAutenticado(subject, tipo, claims.get("clienteId", String.class), claims.get("nome", String.class)),
                        papeis(claims, tipo)));
    }

    private static Optional<TipoUsuario> tipo(String claim) {
        if (claim == null) {
            return Optional.of(TipoUsuario.FUNCIONARIO);
        }
        try {
            return Optional.of(TipoUsuario.valueOf(claim));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Papel do tipo ({@code CLIENTE}/{@code FUNCIONARIO}) mais os da claim {@code roles}, sem prefixo. */
    private static List<String> papeis(Claims claims, TipoUsuario tipo) {
        Object roles = claims.get("roles");
        List<String> declarados = roles instanceof Collection<?> colecao
                ? colecao.stream().filter(Objects::nonNull).map(String::valueOf).filter(p -> !p.isBlank()).toList()
                : List.of();
        return Stream.concat(Stream.of(tipo.name()), declarados.stream())
                .distinct()
                .toList();
    }

    public record Autenticacao(UsuarioAutenticado usuario, List<String> papeis) {
    }
}
