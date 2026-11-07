package br.com.oficina.execution.execucao.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.model.PecaDiagnostico;
import br.com.oficina.execution.execucao.domain.model.ServicoDiagnostico;
import br.com.oficina.execution.execucao.domain.model.Veiculo;
import br.com.oficina.execution.execucao.domain.repository.ExecucaoRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Os comandos do orquestrador que movem a execucao (ADR 0004, passos 1, 2 e 5): entrar na fila de
 * diagnostico, reabri-lo, entrar na fila de execucao e cancelar. Rodam dentro da unidade de trabalho do
 * consumidor: o novo estado e a resposta no outbox confirmam juntos.
 *
 * <p>Idempotentes pelo ESTADO: um comando reenviado por timeout (messageId novo) encontra a execucao
 * ja na etapa pedida e so repete a resposta. Comando que nao cabe na etapa atual lanca - a mensagem
 * volta para a fila e, esgotadas as tentativas, vai para a DLQ, que e monitorada.
 */
@Service
public class ExecucaoPelaSaga {
    private static final Logger log = LoggerFactory.getLogger(ExecucaoPelaSaga.class);

    private final ExecucaoRepository execucoes;
    private final EventosExecucao eventos;
    private final JsonMapper jsonMapper;
    private final Clock relogio;

    @Autowired
    public ExecucaoPelaSaga(ExecucaoRepository execucoes, EventosExecucao eventos, JsonMapper jsonMapper) {
        this(execucoes, eventos, jsonMapper, Clock.systemUTC());
    }

    ExecucaoPelaSaga(ExecucaoRepository execucoes, EventosExecucao eventos, JsonMapper jsonMapper, Clock relogio) {
        this.execucoes = execucoes;
        this.eventos = eventos;
        this.jsonMapper = jsonMapper;
        this.relogio = relogio;
    }

    public void enfileirarDiagnostico(Envelope comando) {
        MensagensExecucao.EnfileirarDiagnostico pedido =
                jsonMapper.treeToValue(comando.payload(), MensagensExecucao.EnfileirarDiagnostico.class);
        UUID ordemId = comando.ordemServicoId();
        Optional<Execucao> existente = execucoes.buscarPorOrdemDeServico(ordemId);
        int posicao;
        if (existente.isPresent()) {
            log.info("Execucao ja existe para a OS; resposta repetida. ordemServicoId={}, etapa={}", ordemId, existente.get().etapa());
            posicao = posicaoNaFila(existente.get());
        } else {
            Execucao nova = Execucao.nova(
                    ordemId, comando.sagaId(), pedido.numeroOrdemServico(),
                    new Veiculo(pedido.veiculo().placa(), pedido.veiculo().marca(), pedido.veiculo().modelo(), pedido.veiculo().ano()),
                    servicos(pedido.servicosPrevistos()), pecas(pedido.pecasPrevistas()), Instant.now(relogio));
            posicao = execucoes.listarPorEtapa(Etapa.AGUARDANDO_DIAGNOSTICO).size() + 1;
            execucoes.criar(nova);
            log.info("OS na fila de diagnostico. ordemServicoId={}, posicao={}", ordemId, posicao);
        }
        eventos.responder(comando, MensagensExecucao.DIAGNOSTICO_ENFILEIRADO, new MensagensExecucao.DiagnosticoEnfileirado(posicao));
    }

    /** Compensacao de {@code OrcamentoFalhou}: o mecanico refaz o diagnostico, sem as pecas invalidas. */
    public void reabrirDiagnostico(Envelope comando) {
        MensagensExecucao.ReabrirDiagnostico pedido =
                jsonMapper.treeToValue(comando.payload(), MensagensExecucao.ReabrirDiagnostico.class);
        Execucao atual = carregar(comando.ordemServicoId());
        Instant agora = Instant.now(relogio);
        if (atual.etapa() == Etapa.AGUARDANDO_DIAGNOSTICO || atual.etapa() == Etapa.EM_DIAGNOSTICO) {
            log.info("Diagnostico ja reaberto; resposta repetida. ordemServicoId={}", comando.ordemServicoId());
        } else {
            execucoes.atualizar(atual.reabrirDiagnostico(pedido.pecasInvalidas() == null ? Set.of() : Set.copyOf(pedido.pecasInvalidas()), agora));
            log.info("Diagnostico reaberto pela Saga. ordemServicoId={}, motivo={}", comando.ordemServicoId(), pedido.motivo());
        }
        eventos.responder(comando, MensagensExecucao.DIAGNOSTICO_REABERTO, new MensagensExecucao.DiagnosticoReaberto(agora));
    }

    /** Passo 5 da Saga: orcamento aprovado e pago, a OS entra na fila de execucao. */
    public void enfileirarExecucao(Envelope comando) {
        jsonMapper.treeToValue(comando.payload(), MensagensExecucao.EnfileirarExecucao.class);
        Execucao atual = carregar(comando.ordemServicoId());
        int posicao;
        if (atual.etapa() == Etapa.AGUARDANDO_EXECUCAO || atual.etapa() == Etapa.EM_EXECUCAO) {
            log.info("Execucao ja enfileirada; resposta repetida. ordemServicoId={}, etapa={}", comando.ordemServicoId(), atual.etapa());
            posicao = posicaoNaFila(atual);
        } else {
            posicao = execucoes.listarPorEtapa(Etapa.AGUARDANDO_EXECUCAO).size() + 1;
            execucoes.atualizar(atual.enfileirarExecucao(Instant.now(relogio)));
            log.info("OS na fila de execucao. ordemServicoId={}, posicao={}", comando.ordemServicoId(), posicao);
        }
        eventos.responder(comando, MensagensExecucao.EXECUCAO_ENFILEIRADA, new MensagensExecucao.ExecucaoEnfileirada(posicao));
    }

    /**
     * Compensacao: a OS nao vai mais ser executada (orcamento recusado ou vencido, pagamento recusado).
     * Sempre confirma - execucao que nao existe, ja cancelada ou ja terminada nao tem o que desfazer.
     */
    public void cancelar(Envelope comando) {
        MensagensExecucao.CancelarExecucao pedido = jsonMapper.treeToValue(comando.payload(), MensagensExecucao.CancelarExecucao.class);
        Instant agora = Instant.now(relogio);
        Optional<Execucao> atual = execucoes.buscarPorOrdemDeServico(comando.ordemServicoId());
        if (atual.isPresent() && !atual.get().etapa().ehFinal()) {
            execucoes.atualizar(atual.get().cancelar(agora));
            log.info("Execucao cancelada pela Saga. ordemServicoId={}, motivo={}", comando.ordemServicoId(), pedido.motivo());
        } else {
            log.info("Nada a cancelar; confirmacao repetida. ordemServicoId={}, etapa={}",
                    comando.ordemServicoId(), atual.map(e -> e.etapa().name()).orElse("inexistente"));
        }
        eventos.responder(comando, MensagensExecucao.EXECUCAO_CANCELADA, new MensagensExecucao.ExecucaoCancelada(agora));
    }

    private Execucao carregar(UUID ordemServicoId) {
        return execucoes.buscarPorOrdemDeServico(ordemServicoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Execução não encontrada para a OS: " + ordemServicoId));
    }

    /** Posicao (1 = a proxima) na fila da etapa atual; fora de fila, 1. */
    private int posicaoNaFila(Execucao execucao) {
        if (!execucao.etapa().ehFila()) {
            return 1;
        }
        List<Execucao> fila = execucoes.listarPorEtapa(execucao.etapa());
        for (int i = 0; i < fila.size(); i++) {
            if (fila.get(i).ordemServicoId().equals(execucao.ordemServicoId())) {
                return i + 1;
            }
        }
        return fila.size() + 1;
    }

    private static List<ServicoDiagnostico> servicos(List<MensagensExecucao.Servico> servicos) {
        return servicos == null ? List.of()
                : servicos.stream().map(s -> new ServicoDiagnostico(s.descricao(), new BigDecimal(s.valorMaoDeObra()))).toList();
    }

    private static List<PecaDiagnostico> pecas(List<MensagensExecucao.ItemPeca> itens) {
        return itens == null ? List.of() : itens.stream().map(i -> new PecaDiagnostico(i.pecaInsumoId(), i.quantidade())).toList();
    }
}
