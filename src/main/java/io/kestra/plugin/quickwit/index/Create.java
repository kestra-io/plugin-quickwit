package io.kestra.plugin.quickwit.index;

import java.util.LinkedHashMap;
import java.util.Map;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.models.IndexMetadata;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Creates a Quickwit index.
 *
 * @see <a href="https://quickwit.io/docs/configuration/index-config">Quickwit index configuration</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a Quickwit index",
    description = """
        Creates an index from a doc mapping, and optional indexing settings, search settings and retention
        policy.

        Quickwit also accepts the index configuration as a YAML document. This task sends JSON, which cannot
        carry YAML comments or anchors.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Create a logs index with a one week retention",
            full = true,
            code = """
                id: quickwit_create_index
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.quickwit.index.Create
                    url: "http://localhost:7280"
                    index: app-logs
                    configVersion: "0.8"
                    docMapping:
                      timestamp_field: timestamp
                      field_mappings:
                        - name: timestamp
                          type: datetime
                          input_formats:
                            - unix_timestamp
                          fast: true
                        - name: service
                          type: text
                          fast: true
                        - name: severity
                          type: text
                          fast: true
                        - name: message
                          type: text
                    searchSettings:
                      default_search_fields:
                        - message
                    retention:
                      period: "7 days"
                      schedule: "@daily"

                  - id: created
                    type: io.kestra.plugin.core.log.Log
                    message: "Created index {{ outputs.create.indexUid }}"
                """
        )
    }
)
public class Create extends AbstractQuickwitIndex implements RunnableTask<Create.Output> {
    @Schema(
        title = "Configuration format version",
        description = """
            Version of the index configuration format, which must match the version of your Quickwit
            cluster, for example `0.8`.

            Named `configVersion` and not `version` because `version` is reserved by Kestra to pin a
            plugin version.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> configVersion;

    @Schema(
        title = "Doc mapping",
        description = """
            Doc mapping of the index: how documents are parsed and which fields are indexed and fast.
            See the [doc mapping documentation](https://quickwit.io/docs/configuration/index-config#doc-mapping).
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> docMapping;

    @Schema(
        title = "Index URI",
        description = """
            Storage URI holding the index files, for example `s3://my-bucket/logs`.
            Defaults to `{default_index_root_uri}/{index}` on the Quickwit side.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> indexUri;

    @Schema(
        title = "Indexing settings",
        description = """
            Merge policy and commit settings of the index.
            See [indexing settings](https://quickwit.io/docs/configuration/index-config#indexing-settings).
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, Object>> indexingSettings;

    @Schema(
        title = "Search settings",
        description = """
            Search settings of the index, for example `default_search_fields`.
            See [search settings](https://quickwit.io/docs/configuration/index-config#search-settings).
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, Object>> searchSettings;

    @Schema(
        title = "Retention",
        description = """
            Retention policy of the index, for example `{period: "30 days", schedule: "@daily"}`.
            See [retention policy](https://quickwit.io/docs/configuration/index-config#retention-policy).
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, Object>> retention;

    @Override
    public Create.Output run(RunContext runContext) throws Exception {
        String rIndex = required(runContext, getIndex(), "index");
        Map<String, Object> docMapping = requiredMap(
            runContext,
            this.docMapping,
            "docMapping",
            "https://quickwit.io/docs/configuration/index-config#doc-mapping"
        );

        // LinkedHashMap keeps the payload readable in logs and in the Quickwit API playground.
        Map<String, Object> configuration = new LinkedHashMap<>();
        putIfPresent(configuration, "version", runContext.render(this.configVersion).as(String.class).orElse(null));
        configuration.put("index_id", rIndex);
        putIfPresent(configuration, "index_uri", runContext.render(this.indexUri).as(String.class).orElse(null));
        configuration.put("doc_mapping", docMapping);
        putIfPresent(configuration, "indexing_settings", runContext.render(this.indexingSettings).asMap(String.class, Object.class));
        putIfPresent(configuration, "search_settings", runContext.render(this.searchSettings).asMap(String.class, Object.class));
        putIfPresent(configuration, "retention", runContext.render(this.retention).asMap(String.class, Object.class));

        HttpRequest request = request(runContext, "POST", "indexes", HttpRequest.JsonRequestBody.of(configuration)).build();

        IndexMetadata metadata;
        try (var client = client(runContext)) {
            metadata = execute(client, request, IndexMetadata.class, "creation of index '" + rIndex + "'");
        }

        return Output.builder()
            .index(rIndex)
            .indexUid(metadata.getIndexUid())
            .createTimestamp(metadata.getCreateTimestamp())
            .metadata(metadata)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Index ID",
            description = "ID of the created index."
        )
        private String index;

        @Schema(
            title = "Index UID",
            description = "Server-generated unique identifier of the created index."
        )
        private String indexUid;

        @Schema(
            title = "Creation timestamp",
            description = "Creation timestamp of the index, in seconds."
        )
        private Long createTimestamp;

        @Schema(
            title = "Index metadata",
            description = "Full metadata of the created index, including its effective configuration and sources."
        )
        private IndexMetadata metadata;
    }
}