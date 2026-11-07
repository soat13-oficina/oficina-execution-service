package br.com.oficina.execution.common.infrastructure.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.execution.common.infrastructure.seguranca.JwtValidador.Autenticacao;
import br.com.oficina.execution.common.infrastructure.seguranca.UsuarioAutenticado.TipoUsuario;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtValidadorTest {

    private final JwtValidador validador = new JwtValidador(TokensDeTeste.SEGREDO);

    @Test
    void tokenDeClienteDaLambdaViraClienteComClienteIdENome() {
        Autenticacao autenticacao = validador.validar(TokensDeTeste.cliente()).orElseThrow();

        assertThat(autenticacao.usuario()).isEqualTo(new UsuarioAutenticado(
                "52998224725", TipoUsuario.CLIENTE, "7b8c9d0e-1f2a-4b3c-8d4e-5f6a7b8c9d0e", "Maria Silva"));
        assertThat(autenticacao.usuario().isCliente()).isTrue();
        assertThat(autenticacao.papeis()).containsExactly("CLIENTE");
    }

    @Test
    void tokenSemTipoDaFase3EhFuncionario() {
        Autenticacao autenticacao = validador.validar(TokensDeTeste.funcionarioFase3()).orElseThrow();

        assertThat(autenticacao.usuario().tipo()).isEqualTo(TipoUsuario.FUNCIONARIO);
        assertThat(autenticacao.usuario().isCliente()).isFalse();
        assertThat(autenticacao.usuario().subject()).isEqualTo("ana@oficina.com");
        assertThat(autenticacao.papeis()).containsExactly("FUNCIONARIO");
    }

    @Test
    void papeisDaClaimRolesSaoSomadosAoTipoSemRepetirNemAceitarVazios() {
        String token = TokensDeTeste.emitir("ana@oficina.com",
                Map.of("tipo", "FUNCIONARIO", "roles", Arrays.asList("ADMIN", "FUNCIONARIO", " ", null)));

        assertThat(validador.validar(token).orElseThrow().papeis()).containsExactly("FUNCIONARIO", "ADMIN");
    }

    @Test
    void claimRolesQueNaoEhListaEhIgnorada() {
        String token = TokensDeTeste.emitir("ana@oficina.com", Map.of("roles", "ADMIN"));

        assertThat(validador.validar(token).orElseThrow().papeis()).containsExactly("FUNCIONARIO");
    }

    @Test
    void tiposDesconhecidosSubjectAusenteOuEmBrancoSaoRecusados() {
        assertThat(validador.validar(TokensDeTeste.emitir("x", Map.of("tipo", "ROBO")))).isEmpty();
        assertThat(validador.validar(TokensDeTeste.emitir(null, Map.of("tipo", "CLIENTE")))).isEmpty();
        assertThat(validador.validar(TokensDeTeste.emitir(" ", Map.of()))).isEmpty();
    }

    @Test
    void tokenExpiradoComOutraAssinaturaOuMalformadoSaoRecusados() {
        for (String token : List.of(TokensDeTeste.expirado(), TokensDeTeste.assinadoComOutraChave(), "nao-e-jwt", "")) {
            assertThat(validador.validar(token)).as(token).isEmpty();
        }
    }
}
