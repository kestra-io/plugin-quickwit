package io.kestra.plugin.quickwit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.kestra.core.serializers.JacksonMapper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Guards the invariant that every subpackage holding plugin classes is described by a metadata file
 * declaring exactly that package as its group.
 *
 * <p>This replaces the META-004 rule of {@code lintPluginDocs}, which {@code build.gradle} disables
 * because a subpackage of this plugin is named {@code index}: the linter resolves its metadata to
 * {@code metadata/index.yaml}, which is also the file that rule requires for the plugin root, so both
 * expectations can never hold at once. Unlike the linter, this test reads the package-to-file mapping
 * directly, keyed by the {@code group} each file declares, so there is no collision to resolve.
 *
 * <p>It also guards the example snippets against templates that Kestra cannot render, for the same
 * reason: the linter checks that an example is valid YAML, not that its expressions evaluate.
 */
class MetadataConsistencyTest extends AbstractQuickwitTest {
    private static final String ROOT = "io.kestra.plugin.quickwit";

    private static final List<String> SUBPACKAGES = List.of(
        ROOT + ".search",
        ROOT + ".ingest",
        ROOT + ".index",
        ROOT + ".source",
        ROOT + ".deletetask"
    );

    @Test
    void everySubpackageWithPluginsIsDescribedByItsOwnMetadata() throws IOException {
        Map<String, Path> metadata = metadataByGroup();

        // discovered from the source tree, mirroring how the linter derives packages from the classes
        assertThat(subpackagesWithPlugins(), containsInAnyOrder(SUBPACKAGES.toArray()));

        for (String subpackage : SUBPACKAGES) {
            Path file = metadata.get(subpackage);
            assertThat("no metadata describes " + subpackage, file, is(notNullValue()));
            assertThat("group of " + subpackage + " must be the package itself", group(file), is(subpackage));
        }
    }

    @Test
    void rootMetadataDeclaresThePluginRoot() throws IOException {
        Map<String, Path> metadata = metadataByGroup();

        assertThat(metadata, hasKey(ROOT));
        assertThat(group(metadata.get(ROOT)), is(ROOT));
    }

    @Test
    void metadataDeclaresTheRequiredFields() throws IOException {
        for (Map.Entry<String, Path> entry : metadataByGroup().entrySet()) {
            Map<String, Object> yaml = readYaml(entry.getValue());

            for (String field : List.of("group", "name", "title", "description")) {
                Object value = yaml.get(field);
                assertThat(
                    entry.getKey() + " must declare a non-empty `" + field + "`",
                    value != null && !value.toString().isBlank(),
                    is(true)
                );
            }

            // META-003 requires the key to be present but, like every compliant plugin, allows an empty value
            assertThat(entry.getKey() + " must declare a `body` field", yaml.containsKey("body"), is(true));
        }
    }

    @Test
    void everySubpackageHasAnIcon() throws IOException {
        Path icons = Path.of("src", "main", "resources", "icons");

        assertThat("the plugin icon is required by ICON-001", Files.exists(icons.resolve("plugin-icon.svg")), is(true));

        for (String subpackage : subpackagesWithPlugins()) {
            assertThat("missing icon for " + subpackage + " (ICON-002)", Files.exists(icons.resolve(subpackage + ".svg")), is(true));
        }
    }

    @Test
    void rootHowToExists() {
        // DOC-001 and DOC-003: the how-to is required and must not be a stub
        Path doc = Path.of("src", "main", "resources", "doc", ROOT + ".md");

        assertThat(Files.exists(doc), is(true));
        try {
            assertThat("the how-to must be at least 10 lines (DOC-003)", Files.readAllLines(doc).size() >= 10, is(true));
        } catch (IOException e) {
            throw new AssertionError("Unable to read " + doc, e);
        }
    }

    /**
     * Guards the examples against {@code date('X')}, which is not a Kestra filter.
     *
     * <p>Kestra renders with Jinjava, which has no {@code date} filter: {@code now() | date('X')}
     * yields the literal string {@code "Z"}. In a search task that fails loudly on a
     * {@code Long} property, but in an ingest example it is worse, because every document is
     * rejected by Quickwit with {@code failed to parse datetime 'Z'} while the task still reports
     * success. {@code timestamp} is the filter that yields unix seconds. Nothing in
     * {@code lintPluginDocs} can catch this, because the snippets are valid YAML.
     */
    @Test
    void noExampleUsesTheDateXFilter() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("date('X')")) {
                    offenders.add(file.toString());
                }
            }
        }

        Path doc = Path.of("src", "main", "resources", "doc", ROOT + ".md");
        if (Files.readString(doc).contains("date('X')")) {
            offenders.add(doc.toString());
        }

        assertThat("use `now() | timestamp` instead of `now() | date('X')`", offenders, is(List.of()));
    }

    /** Maps each documented package to its metadata file, keyed by the {@code group} the file declares. */
    private static Map<String, Path> metadataByGroup() throws IOException {
        Path dir = Path.of("src", "main", "resources", "metadata");
        Map<String, Path> byGroup = new LinkedHashMap<>();

        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                byGroup.put(group(file), file);
            }
        }

        return byGroup;
    }

    private static String group(Path file) throws IOException {
        Object group = readYaml(file).get("group");

        return group == null ? null : group.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return JacksonMapper.ofYaml().readValue(in, Map.class);
        }
    }

    /** Subpackages of the plugin holding at least one Java source file. */
    private static List<String> subpackagesWithPlugins() throws IOException {
        Path javaRoot = Path.of("src", "main", "java", "io", "kestra", "plugin", "quickwit");
        List<String> packages = new ArrayList<>();

        try (Stream<Path> files = Files.walk(javaRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // relative package directory, e.g. "search" or "ingest"; empty for the root package
                String directory = javaRoot.relativize(file.getParent()).toString().replace('\\', '/');
                if (directory.isEmpty()) {
                    continue;
                }

                // a package is a plugin subpackage only when it declares its @PluginSubGroup, which is
                // what the linter requires of every package holding plugin classes. Shared helper
                // packages such as `models` have no package-info.java and are not plugin subpackages.
                if (!Files.exists(file.getParent().resolve("package-info.java"))) {
                    continue;
                }

                String subpackage = ROOT + "." + directory.replace('/', '.');
                if (!packages.contains(subpackage)) {
                    packages.add(subpackage);
                }
            }
        }

        return packages;
    }
}