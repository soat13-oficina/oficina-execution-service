package br.com.oficina.execution.common.domain.exception;

/**
 * Representa um conflito de estado de um recurso (ex.: decisão divergente de uma já registrada).
 * Mapeada para HTTP 409 (Conflict) pelo {@code ApiExceptionHandler}.
 */
public class ConflitoDeRecursoException extends RuntimeException {
    public ConflitoDeRecursoException(String message) {
        super(message);
    }
}
