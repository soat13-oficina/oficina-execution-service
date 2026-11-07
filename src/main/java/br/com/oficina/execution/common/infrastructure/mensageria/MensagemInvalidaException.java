package br.com.oficina.execution.common.infrastructure.mensageria;

/**
 * Mensagem que nunca vai ser processada, por mais que se tente: fora do contrato ou de tipo sem
 * manipulador. Vai direto para a DLQ (ver {@code ReceptorSqs}).
 */
public class MensagemInvalidaException extends RuntimeException {

    public MensagemInvalidaException(String mensagem) {
        super(mensagem);
    }

    public MensagemInvalidaException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
