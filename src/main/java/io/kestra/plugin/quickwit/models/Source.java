package io.kestra.plugin.quickwit.models;

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
 * A source attached to a Quickwit index, for example a Kafka, Kinesis or Pulsar source.
 *
 * @see <a href="https://quickwit.io/docs/configuration/source-config">Quickwit source configuration</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
@NoArgsConstructor
@Schema(
    title = "Index source",
    description = "Configuration of a source feeding a Quickwit index."
)
public class Source {
    @Schema(
        title = "Configuration format version",
        description = "Format version of the source configuration, matching the Quickwit version of the cluster."
    )
    private String version;

    @Schema(
        title = "Source ID",
        description = "Unique identifier of the source within its index."
    )
    @JsonProperty("source_id")
    private String sourceId;

    @Schema(
        title = "Source type",
        description = "Type of the source: `kafka`, `kinesis` or `pulsar`."
    )
    @JsonProperty("source_type")
    private String sourceType;

    @Schema(
        title = "Number of pipelines",
        description = "Number of indexing pipelines running per node for this source. Defaults to `1`."
    )
    @JsonProperty("num_pipelines")
    private Integer numPipelines;

    @Schema(
        title = "Transform",
        description = "VRL transformation applied to incoming documents, if any."
    )
    private String transform;

    @Schema(
        title = "Source parameters",
        description = "Parameters of the source, whose shape depends on the source type."
    )
    private Map<String, Object> params;
}