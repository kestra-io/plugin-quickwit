package io.kestra.plugin.quickwit.index;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.models.IndexMetadata;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Reads the metadata of a Quickwit index.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#get-an-index-metadata">Get an index metadata</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a Quickwit index",
    description = """
        Returns the metadata of an index: its effective configuration, its UID, its creation timestamp and
        the sources attached to it.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Read the configuration of an index",
            full = true,
            code = """
                id: quickwit_get_index
                namespace: company.team

                tasks:
                  - id: get
                    type: io.kestra.plugin.quickwit.index.Get
                    url: "http://localhost:7280"
                    index: app-logs

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "Timestamp field: {{ outputs.get.metadata.indexConfig.doc_mapping.timestamp_field }}"
                """
        )
    }
)
public class Get extends AbstractQuickwitIndex implements RunnableTask<Get.Output> {
    @Override
    public Get.Output run(RunContext runContext) throws Exception {
        String rIndex = required(runContext, getIndex(), "index");

        HttpRequest request = request(runContext, "GET", "indexes/" + pathSegment(rIndex), null).build();

        IndexMetadata metadata;
        try (var client = client(runContext)) {
            metadata = execute(client, request, IndexMetadata.class, "read of index '" + rIndex + "'");
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
            description = "ID of the index that was read."
        )
        private String index;

        @Schema(
            title = "Index UID",
            description = "Server-generated unique identifier of the index."
        )
        private String indexUid;

        @Schema(
            title = "Creation timestamp",
            description = "Creation timestamp of the index, in seconds."
        )
        private Long createTimestamp;

        @Schema(
            title = "Index metadata",
            description = "Metadata of the index, including its effective configuration, checkpoints and sources."
        )
        private IndexMetadata metadata;
    }
}