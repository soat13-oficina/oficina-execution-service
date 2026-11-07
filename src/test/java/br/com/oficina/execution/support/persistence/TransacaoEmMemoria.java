package br.com.oficina.execution.support.persistence;

import java.util.function.Supplier;

import br.com.oficina.execution.common.application.Transacao;

/** Duplo da transacao: so executa o bloco. A atomicidade de verdade quem prova e o LocalStack. */
public class TransacaoEmMemoria implements Transacao {

    @Override
    public <T> T executar(Supplier<T> acao) {
        return acao.get();
    }
}
