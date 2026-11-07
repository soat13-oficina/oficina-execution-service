package br.com.oficina.execution.common.infrastructure.mensageria;

import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.PublicadorMensagens;
import br.com.oficina.execution.common.infrastructure.dynamodb.UnidadeDeTrabalho;
import tools.jackson.databind.json.JsonMapper;

/**
 * Implementa a porta gravando no outbox, na transacao do DynamoDB em curso. Publicar fora de uma
 * unidade de trabalho quebraria a garantia do padrao, entao e erro de programacao e falha alto.
 */
@Component
public class PublicadorMensagensOutbox implements PublicadorMensagens {

    private final UnidadeDeTrabalho unidade;
    private final OutboxDynamo outbox;
    private final JsonMapper jsonMapper;

    public PublicadorMensagensOutbox(UnidadeDeTrabalho unidade, OutboxDynamo outbox, JsonMapper jsonMapper) {
        this.unidade = unidade;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void publicar(String fila, Envelope envelope) {
        unidade.adicionar(OutboxDynamo.ROTULO + "#" + envelope.messageId(), outbox.item(
                fila,
                envelope.messageId().toString(),
                envelope.ordemServicoId().toString(),
                envelope.type(),
                jsonMapper.writeValueAsString(envelope)));
    }
}
