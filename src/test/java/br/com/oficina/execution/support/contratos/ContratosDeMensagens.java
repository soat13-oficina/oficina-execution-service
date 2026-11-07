package br.com.oficina.execution.support.contratos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON Schemas copiados do OS Service ({@code src/test/resources/contratos}), com as
 * referencias relativas resolvidas pelo {@code $id} de cada arquivo e {@code format} validado.
 */
public final class ContratosDeMensagens {

    public static final Path PASTA = Path.of("src", "test", "resources", "contratos", "schemas");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SchemaRegistry registro;
    private final Map<String, String> idPorTipo = new HashMap<>();

    public ContratosDeMensagens() {
        Map<String, String> conteudoPorId = new HashMap<>();
        try (Stream<Path> arquivos = Files.walk(PASTA)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".schema.json")).toList()) {
                String conteudo = Files.readString(arquivo);
                JsonNode schema = JSON.readTree(conteudo);
                String id = schema.get("$id").asString();
                conteudoPorId.put(id, conteudo);
                if (schema.has("x-categoria")) {
                    idPorTipo.put(schema.get("title").asString(), id);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel ler " + PASTA.toAbsolutePath(), e);
        }
        this.registro = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(conteudoPorId));
    }

    /** Tipos (comandos e eventos) que tem schema. */
    public java.util.Set<String> tipos() {
        return java.util.Set.copyOf(idPorTipo.keySet());
    }

    /** Primeiro exemplo ({@code examples[0]}) do schema do tipo: o envelope que o contrato documenta. */
    public JsonNode exemplo(String tipo) {
        String id = idPorTipo.get(tipo);
        if (id == null) {
            throw new IllegalArgumentException("Tipo sem schema: " + tipo);
        }
        return registro.getSchema(SchemaLocation.of(id)).getSchemaNode().get("examples").get(0).deepCopy();
    }

    /** Erros do envelope completo contra o schema do seu {@code type}; vazio = dentro do contrato. */
    public List<String> validar(String envelopeJson) {
        JsonNode envelope = JSON.readTree(envelopeJson);
        String tipo = envelope.path("type").asString();
        String id = idPorTipo.get(tipo);
        if (id == null) {
            return List.of("Tipo sem schema em docs/contratos: " + tipo);
        }
        Schema schema = registro.getSchema(SchemaLocation.of(id));
        List<Error> erros = schema.validate(envelope,
                contexto -> contexto.executionConfig(config -> config.formatAssertionsEnabled(true)));
        return erros.stream().map(e -> tipo + " " + e.getInstanceLocation() + ": " + e.getMessage()).toList();
    }
}
