package br.com.oficina.execution.execucao.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;

class ExecucaoTest {

    private static final Instant T0 = Instant.parse("2026-10-17T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final Instant T2 = T0.plusSeconds(120);
    private static final Mecanico ANA = new Mecanico("ana@oficina.com", "Ana");
    private static final Mecanico BETO = new Mecanico("beto@oficina.com", "Beto");

    private static Execucao nova() {
        return Execucao.nova(UUID.randomUUID(), UUID.randomUUID(), "OS-1", new Veiculo("ABC1D23", "Toyota", "Corolla", 2020),
                List.of(new ServicoDiagnostico("Revisao", new BigDecimal("100.00"))), List.of(new PecaDiagnostico("a", 1)), T0);
    }

    private static Diagnostico diagnostico() {
        return new Diagnostico("Oleo vencido", List.of(new ServicoDiagnostico("Troca de oleo", new BigDecimal("120.00"))),
                List.of(new PecaDiagnostico("a", 1), new PecaDiagnostico("b", 2)));
    }

    private static Execucao comDiagnosticoConcluido() {
        return nova().iniciarDiagnostico(ANA, T1).concluirDiagnostico(diagnostico(), T2);
    }

    @Test
    void nasceNaFilaDeDiagnosticoComHistorico() {
        Execucao e = nova();

        assertThat(e.etapa()).isEqualTo(Etapa.AGUARDANDO_DIAGNOSTICO);
        assertThat(e.historico()).containsExactly(new TransicaoDeEtapa(Etapa.AGUARDANDO_DIAGNOSTICO, T0));
        assertThat(e.entrouNaEtapaEm()).isEqualTo(T0);
        assertThat(e.diagnostico()).isNull();
        assertThat(e.servicosPrevistos()).hasSize(1);
    }

    @Test
    void caminhoFelizPercorreTodasAsEtapasNaOrdem() {
        Execucao e = nova().iniciarDiagnostico(ANA, T1).concluirDiagnostico(diagnostico(), T1)
                .enfileirarExecucao(T1).iniciarExecucao(BETO, T2).concluirExecucao(T2);

        assertThat(e.etapa()).isEqualTo(Etapa.CONCLUIDA);
        assertThat(e.historico()).extracting(TransicaoDeEtapa::etapa).containsExactly(
                Etapa.AGUARDANDO_DIAGNOSTICO, Etapa.EM_DIAGNOSTICO, Etapa.DIAGNOSTICO_CONCLUIDO,
                Etapa.AGUARDANDO_EXECUCAO, Etapa.EM_EXECUCAO, Etapa.CONCLUIDA);
        assertThat(e.mecanicoDiagnostico()).isEqualTo(ANA);
        assertThat(e.mecanicoExecucao()).isEqualTo(BETO);
        assertThat(e.entrouNaEtapaEm()).isEqualTo(T2);
        assertThat(e.criadaEm()).isEqualTo(T0);
    }

    @Test
    void cadaTransicaoSoValeNaEtapaCerta() {
        Execucao inicial = nova();
        Execucao concluidaDiag = comDiagnosticoConcluido();

        assertThatThrownBy(() -> inicial.concluirDiagnostico(diagnostico(), T1)).isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("AGUARDANDO_DIAGNOSTICO");
        assertThatThrownBy(() -> inicial.enfileirarExecucao(T1)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> inicial.iniciarExecucao(ANA, T1)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> inicial.concluirExecucao(T1)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> inicial.abortarExecucao(T1)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> inicial.reabrirDiagnostico(Set.of(), T1)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> concluidaDiag.iniciarDiagnostico(ANA, T1)).isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    void diagnosticoPrecisaDeServico() {
        Execucao emDiagnostico = nova().iniciarDiagnostico(ANA, T1);

        assertThatThrownBy(() -> emDiagnostico.concluirDiagnostico(new Diagnostico("x", List.of(), List.of()), T2))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("ao menos um servico");
    }

    @Test
    void reabrirVoltaParaAFilaMantendoServicosETirandoPecasInvalidas() {
        Execucao reaberta = comDiagnosticoConcluido().reabrirDiagnostico(Set.of("b"), T2.plusSeconds(1));

        assertThat(reaberta.etapa()).isEqualTo(Etapa.AGUARDANDO_DIAGNOSTICO);
        assertThat(reaberta.diagnostico()).isNull();
        assertThat(reaberta.mecanicoDiagnostico()).isNull();
        assertThat(reaberta.servicosPrevistos()).extracting(ServicoDiagnostico::descricao).containsExactly("Troca de oleo");
        assertThat(reaberta.pecasPrevistas()).containsExactly(new PecaDiagnostico("a", 1));
        assertThat(reaberta.entrouNaEtapaEm()).isEqualTo(T2.plusSeconds(1));
        assertThat(reaberta.historico()).hasSize(4);
    }

    @Test
    void abortarSoEmExecucao() {
        Execucao emExecucao = comDiagnosticoConcluido().enfileirarExecucao(T2).iniciarExecucao(BETO, T2);

        assertThat(emExecucao.abortarExecucao(T2).etapa()).isEqualTo(Etapa.ABORTADA);
        assertThatThrownBy(() -> comDiagnosticoConcluido().abortarExecucao(T2)).isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    void cancelarVaiDeQualquerEtapaNaoFinal() {
        assertThat(nova().cancelar(T1).etapa()).isEqualTo(Etapa.CANCELADA);
        assertThat(comDiagnosticoConcluido().cancelar(T1).etapa()).isEqualTo(Etapa.CANCELADA);
        assertThat(comDiagnosticoConcluido().enfileirarExecucao(T2).cancelar(T2).etapa()).isEqualTo(Etapa.CANCELADA);
    }

    @Test
    void etapaFinalNaoMuda() {
        Execucao cancelada = nova().cancelar(T1);

        assertThat(cancelada.etapa().ehFinal()).isTrue();
        assertThatThrownBy(() -> cancelada.cancelar(T2)).isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("ja terminou");
    }

    @Test
    void soAsEtapasAguardandoSaoFila() {
        for (Etapa etapa : Etapa.values()) {
            assertThat(etapa.ehFila()).isEqualTo(etapa == Etapa.AGUARDANDO_DIAGNOSTICO || etapa == Etapa.AGUARDANDO_EXECUCAO);
            assertThat(etapa.ehFinal()).isEqualTo(etapa == Etapa.CONCLUIDA || etapa == Etapa.ABORTADA || etapa == Etapa.CANCELADA);
        }
    }

    @Test
    void listasNulasViramVazias() {
        Execucao e = Execucao.nova(UUID.randomUUID(), null, "OS-2", new Veiculo("ABC1D23", "Fiat", "Uno", null), null, null, T0);

        assertThat(e.servicosPrevistos()).isEmpty();
        assertThat(e.pecasPrevistas()).isEmpty();
        assertThat(e.sagaId()).isNull();
    }
}
