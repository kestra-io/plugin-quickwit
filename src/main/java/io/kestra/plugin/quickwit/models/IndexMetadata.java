package io.kestra.plugin.quickwit.models;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

/**
 * Metadata of a Quickwit index, as returned by the index management API.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#get-an-index-metadata">Quickwit index API</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
@NoArgsConstructor
@Schema(
    title = "Index metadata",
    description = "Configuration and state of a Quickwit index."
)
public class IndexMetadata {
    @Schema(
        title = "Configuration format version",
        description = "Format version of the index configuration, matching the Quickwit version of the cluster."
    )
    private String version;

    @Schema(
        title = "Index UID",
        description = "Server-generated unique identifier of the index."
    )
    @JsonProperty("index_uid")
    private String indexUid;

    @Schema(
        title = "Index configuration",
        description = "The effective index configuration, including its doc mapping, indexing settings, search settings and retention policy."
    )
    @JsonProperty("index_config")
    private Map<String, Object> indexConfig;

    @Schema(
        title = "Source checkpoints",
        description = "Last committed checkpoint of each source attached to the index, keyed by source ID."
    )
    private Map<String, Object> checkpoint;

    @Schema(
        title = "Creation timestamp",
        description = "Creation timestamp of the index, in seconds."
    )
    @JsonProperty("create_timestamp")
    private Long createTimestamp;

    @Schema(
        title = "Sources",
        description = "Sources attached to this index."
    )
    private List<Source> sources;
}