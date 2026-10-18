package br.com.oficina.execution.execucao.infrastructure.mensageria;

import java.util.Set;

import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.ManipuladorMensagem;
import br.com.oficina.execution.execucao.application.ExecucaoPelaSaga;
import br.com.oficina.execution.execucao.application.MensagensExecucao;

/** Comandos do orquestrador ({@code execution-commands}) que movem a execucao. */
@Component
public class ManipuladorComandosExecucao implements ManipuladorMensagem {

    private final ExecucaoPelaSaga saga;

    public ManipuladorComandosExecucao(ExecucaoPelaSaga saga) {
        this.saga = saga;
    }

    @Override
    public Set<String> tipos() {
        return Set.of(
                MensagensExecucao.ENFILEIRAR_DIAGNOSTICO,
                MensagensExecucao.REABRIR_DIAGNOSTICO,
                MensagensExecucao.ENFILEIRAR_EXECUCAO,
                MensagensExecucao.CANCELAR_EXECUCAO);
    }

    @Override
    public void tratar(Envelope envelope) {
        switch (envelope.type()) {
            case MensagensExecucao.ENFILEIRAR_DIAGNOSTICO -> saga.enfileirarDiagnostico(envelope);
            case MensagensExecucao.REABRIR_DIAGNOSTICO -> saga.reabrirDiagnostico(envelope);
            case MensagensExecucao.ENFILEIRAR_EXECUCAO -> saga.enfileirarExecucao(envelope);
            case MensagensExecucao.CANCELAR_EXECUCAO -> saga.cancelar(envelope);
            default -> throw new IllegalArgumentException("Tipo nao tratado: " + envelope.type());
        }
    }
}
