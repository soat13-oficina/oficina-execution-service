package br.com.oficina.execution.common.infrastructure.seguranca;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;

/** Emite tokens como o OS Service e a Lambda emitem, para exercitar a validacao. */
public final class TokensDeTeste {

    public static final String SEGREDO = "chave-secreta-de-teste-para-rodar-os-testes-unitarios-2026";
    private static final SecretKey CHAVE = Keys.hmacShaKeyFor(SEGREDO.getBytes(StandardCharsets.UTF_8));

    private TokensDeTeste() {
    }

    public static String cliente() {
        return emitir("52998224725", Map.of("tipo", "CLIENTE", "clienteId", "7b8c9d0e-1f2a-4b3c-8d4e-5f6a7b8c9d0e", "nome", "Maria Silva"));
    }

    public static String funcionarioFase3() {
        return emitir("ana@oficina.com", Map.of());
    }

    public static String emitir(String subject, Map<String, ?> claims) {
        return emitir(subject, claims, CHAVE, Instant.now().plusSeconds(3600));
    }

    public static String emitir(String subject, Map<String, ?> claims, SecretKey chave, Instant expiraEm) {
        var builder = Jwts.builder()
                .claims(claims)
                .issuedAt(Date.from(Instant.now().minusSeconds(60)))
                .expiration(Date.from(expiraEm))
                .signWith(chave);
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.compact();
    }

    public static String expirado() {
        return emitir("ana@oficina.com", Map.of(), CHAVE, Instant.now().minusSeconds(10));
    }

    public static String assinadoComOutraChave() {
        SecretKey outra = Keys.hmacShaKeyFor("outra-chave-secreta-bem-longa-para-hmac-sha-256-2026".getBytes(StandardCharsets.UTF_8));
        return emitir("ana@oficina.com", Map.of(), outra, Instant.now().plusSeconds(3600));
    }
}
