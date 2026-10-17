package br.com.oficina.execution.execucao.application;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.oficina.execution.common.application.Transacao;
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import br.com.oficina.execution.estoque.domain.repository.PecaInsumoRepository;
import br.com.oficina.execution.estoque.domain.repository.ReservaPecaRepository;
import br.com.oficina.execution.execucao.domain.model.Diagnostico;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.model.Mecanico;
import br.com.oficina.execution.execucao.domain.model.PecaDiagnostico;
import br.com.oficina.execution.execucao.domain.model.ServicoDiagnostico;
import br.com.oficina.execution.execucao.domain.repository.ExecucaoRepository;

/**
 * O que o mecanico faz pela API: ver a fila, iniciar e concluir o diagnostico, iniciar, concluir e abortar
 * a execucao. Cada acao muda a execucao e grava o evento da Saga no outbox na MESMA transacao do
 * DynamoDB; a conclusao baixa tambem o estoque das pecas reservadas para a OS.
 */
@Service
public class ExecucaoService {
    private static final Logger log = LoggerFactory.getLogger(ExecucaoService.class);

    private final ExecucaoRepository execucoes;
    private final PecaInsumoRepository pecas;
    private final ReservaPecaRepository reservas;
    private final EventosExecucao eventos;
    private final Transacao transacao;
    private final Clock relogio;

    @Autowired
    public ExecucaoService(
            ExecucaoRepository execucoes,
            PecaInsumoRepository pecas,
            ReservaPecaRepository reservas,
            EventosExecucao eventos,
            Transacao transacao) {
        this(execucoes, pecas, reservas, eventos, transacao, Clock.systemUTC());
    }

    ExecucaoService(
            ExecucaoRepository execucoes,
            PecaInsumoRepository pecas,
            ReservaPecaRepository reservas,
            EventosExecucao eventos,
            Transacao transacao,
            Clock relogio) {
        this.execucoes = execucoes;
        this.pecas = pecas;
        this.reservas = reservas;
        this.eventos = eventos;
        this.transacao = transacao;
        this.relogio = relogio;
    }

    /** O que o mecanico informa ao concluir o diagnostico. */
    public record DadosDiagnostico(String descricao, List<ServicoDiagnostico> servicos, List<PecaDiagnostico> pecas) {
    }

    public Execucao buscar(UUID ordemServicoId) {
        return carregar(ordemServicoId);
    }

    /** A fila de uma etapa em ordem de chegada; sem etapa, as duas filas do mecanico (diagnostico, depois execucao). */
    public List<Execucao> listar(Etapa etapa) {
        if (etapa != null) {
            return execucoes.listarPorEtapa(etapa);
        }
        return java.util.stream.Stream.concat(
                        execucoes.listarPorEtapa(Etapa.AGUARDANDO_DIAGNOSTICO).stream(),
                        execucoes.listarPorEtapa(Etapa.AGUARDANDO_EXECUCAO).stream())
                .toList();
    }

    public Execucao iniciarDiagnostico(UUID ordemServicoId, Mecanico mecanico) {
        return transacao.executar(() -> {
            Instant agora = Instant.now(relogio);
            Execucao nova = carregar(ordemServicoId).iniciarDiagnostico(mecanico, agora);
            execucoes.atualizar(nova);
            eventos.espontaneo(nova, MensagensExecucao.DIAGNOSTICO_INICIADO,
                    new MensagensExecucao.DiagnosticoIniciado(paraMensagem(mecanico), agora));
            log.info("Diagnostico iniciado. ordemServicoId={}, mecanico={}", ordemServicoId, mecanico.id());
            return nova;
        });
    }

    public Execucao concluirDiagnostico(UUID ordemServicoId, Mecanico mecanico, DadosDiagnostico dados) {
        validarPecas(dados.pecas());
        return transacao.executar(() -> {
            Instant agora = Instant.now(relogio);
            Execucao nova = carregar(ordemServicoId)
                    .concluirDiagnostico(new Diagnostico(dados.descricao(), dados.servicos(), dados.pecas()), agora);
            execucoes.atualizar(nova);
            eventos.espontaneo(nova, MensagensExecucao.DIAGNOSTICO_CONCLUIDO, new MensagensExecucao.DiagnosticoConcluido(
                    paraMensagem(mecanico),
                    dados.descricao(),
                    dados.servicos().stream().map(ExecucaoService::paraMensagem).toList(),
                    dados.pecas().stream().map(ExecucaoService::paraMensagem).toList(),
                    agora));
            log.info("Diagnostico concluido. ordemServicoId={}, servicos={}, pecas={}",
                    ordemServicoId, dados.servicos().size(), dados.pecas().size());
            return nova;
        });
    }

    public Execucao iniciarExecucao(UUID ordemServicoId, Mecanico mecanico) {
        return transacao.executar(() -> {
            Instant agora = Instant.now(relogio);
            Execucao nova = carregar(ordemServicoId).iniciarExecucao(mecanico, agora);
            execucoes.atualizar(nova);
            eventos.espontaneo(nova, MensagensExecucao.EXECUCAO_INICIADA,
                    new MensagensExecucao.ExecucaoIniciada(paraMensagem(mecanico), agora));
            log.info("Execucao iniciada. ordemServicoId={}, mecanico={}", ordemServicoId, mecanico.id());
            return nova;
        });
    }

    /** Conclui o servico e baixa do estoque as pecas que a Saga reservou para a OS. */
    public Execucao concluirExecucao(UUID ordemServicoId, Mecanico mecanico) {
        return transacao.executar(() -> {
            Instant agora = Instant.now(relogio);
            Execucao nova = carregar(ordemServicoId).concluirExecucao(agora);
            List<ReservaPeca> ativas = reservasAtivas(ordemServicoId);
            execucoes.atualizar(nova);
            if (!ativas.isEmpty()) {
                reservas.consumir(ativas, agora);
            }
            eventos.espontaneo(nova, MensagensExecucao.EXECUCAO_CONCLUIDA,
                    new MensagensExecucao.ExecucaoConcluida(paraMensagem(mecanico), itens(ativas), agora));
            log.info("Execucao concluida. ordemServicoId={}, pecasConsumidas={}", ordemServicoId, ativas.size());
            return nova;
        });
    }

    /** Aborta a execucao; as reservas ficam como estao - quem as libera e a compensacao {@code LiberarReserva} da Saga. */
    public Execucao abortarExecucao(UUID ordemServicoId, Mecanico mecanico, String motivo) {
        return transacao.executar(() -> {
            Instant agora = Instant.now(relogio);
            Execucao nova = carregar(ordemServicoId).abortarExecucao(agora);
            execucoes.atualizar(nova);
            eventos.espontaneo(nova, MensagensExecucao.EXECUCAO_FALHOU,
                    new MensagensExecucao.ExecucaoFalhou(paraMensagem(mecanico), motivo, List.of(), agora));
            log.info("Execucao abortada. ordemServicoId={}, mecanico={}, motivo={}", ordemServicoId, mecanico.id(), motivo);
            return nova;
        });
    }

    private Execucao carregar(UUID ordemServicoId) {
        return execucoes.buscarPorOrdemDeServico(ordemServicoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Execução não encontrada para a OS: " + ordemServicoId));
    }

    private List<ReservaPeca> reservasAtivas(UUID ordemServicoId) {
        return reservas.buscarPorOrdemDeServico(ordemServicoId).stream().filter(ReservaPeca::ativa).toList();
    }

    /** O diagnostico so aceita pecas que existem no catalogo (a validacao era do OS, que lia o cadastro). */
    private void validarPecas(List<PecaDiagnostico> pecasDoDiagnostico) {
        for (String id : new LinkedHashSet<>(pecasDoDiagnostico.stream().map(PecaDiagnostico::pecaInsumoId).toList())) {
            if (pecas.buscarPorId(id).isEmpty()) {
                throw new RegraDeNegocioException("Peça/Insumo não encontrada com o ID: " + id);
            }
        }
        if (pecasDoDiagnostico.stream().anyMatch(p -> p.quantidade() < 1)) {
            throw new RegraDeNegocioException("A quantidade de cada peça deve ser no mínimo 1.");
        }
    }

    private static MensagensExecucao.Mecanico paraMensagem(Mecanico mecanico) {
        return new MensagensExecucao.Mecanico(mecanico.id(), mecanico.nome());
    }

    static MensagensExecucao.Servico paraMensagem(ServicoDiagnostico servico) {
        return new MensagensExecucao.Servico(servico.descricao(), servico.valorMaoDeObra().setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    static MensagensExecucao.ItemPeca paraMensagem(PecaDiagnostico peca) {
        return new MensagensExecucao.ItemPeca(peca.pecaInsumoId(), peca.quantidade());
    }

    private static List<MensagensExecucao.ItemPeca> itens(List<ReservaPeca> reservas) {
        return reservas.stream().map(r -> new MensagensExecucao.ItemPeca(r.pecaInsumoId(), r.quantidade())).toList();
    }
}
