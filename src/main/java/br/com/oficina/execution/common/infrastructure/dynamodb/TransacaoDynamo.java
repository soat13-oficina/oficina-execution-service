package br.com.oficina.execution.common.infrastructure.dynamodb;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.application.Transacao;
import br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException;

/**
 * {@link Transacao} sobre a {@link UnidadeDeTrabalho}. Pelo HTTP, uma transacao recusada significa que
 * outra requisicao mexeu no mesmo registro no meio do caminho: 409, para o cliente reler e tentar de
 * novo. (O consumidor de mensagens usa a unidade de trabalho direto: la a recusa devolve a mensagem a
 * fila.)
 */
@Component
public class TransacaoDynamo implements Transacao {

    private final UnidadeDeTrabalho unidade;

    public TransacaoDynamo(UnidadeDeTrabalho unidade) {
        this.unidade = unidade;
    }

    @Override
    public <T> T executar(Supplier<T> acao) {
        try {
            return unidade.executar(acao);
        } catch (EscritaRecusadaException e) {
            throw new ConflitoDeRecursoException("O registro foi alterado por outra requisição; consulte e tente novamente.");
        }
    }
}
