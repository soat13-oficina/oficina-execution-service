package br.com.oficina.execution.common.infrastructure.seguranca;

/**
 * Identidade de quem fez a requisicao, montada so a partir das claims do JWT.
 *
 * <p>Este servico nao tem tabela de usuarios: confia na assinatura do token, emitido pelo
 * OS Service (funcionario) ou pela Lambda de autenticacao (cliente), com o segredo
 * compartilhado {@code oficina/<env>/jwt-secret}.
 *
 * @param subject   e-mail do funcionario ou CPF do cliente
 * @param tipo      {@link TipoUsuario#FUNCIONARIO} ou {@link TipoUsuario#CLIENTE}
 * @param clienteId id do cliente (so em token de cliente)
 * @param nome      nome exibivel, quando o token traz
 */
public record UsuarioAutenticado(String subject, TipoUsuario tipo, String clienteId, String nome) {

    public boolean isCliente() {
        return tipo == TipoUsuario.CLIENTE;
    }

    public enum TipoUsuario {
        FUNCIONARIO,
        CLIENTE
    }
}
