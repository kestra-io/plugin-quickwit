package io.kestra.plugin.quickwit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.JacksonMapper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Sanity-checks the example flows under {@code src/test/resources/flows}: each one must parse as a
 * Kestra flow so a copy-pasted example never ships with invalid YAML or an unknown task type.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlowsValidationTest extends AbstractQuickwitTest {
    @Test
    void flowsDirectoryIsNotEmpty() throws Exception {
        assertThat("no flows found under src/test/resources/flows", flowFiles().isEmpty(), is(false));
    }

    @Test
    void everyFlowParses() throws Exception {
        for (Path file : flowFiles()) {
            Flow flow = JacksonMapper.ofYaml().readValue(file.toFile(), Flow.class);

            assertThat(file + " must declare an id", flow.getId(), is(notNullValue()));
            assertThat(file + " must declare a namespace", flow.getNamespace(), is(notNullValue()));
            assertThat(file + " must declare tasks", flow.getTasks(), is(notNullValue()));
        }
    }

    private static List<Path> flowFiles() throws Exception {
        Path dir = Path.of("src", "test", "resources", "flows");

        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
    }
}
