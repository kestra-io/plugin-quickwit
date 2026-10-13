package io.kestra.plugin.quickwit.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

/**
 * A split file that was removed from the storage when an index was deleted.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#delete-an-index">Quickwit delete index</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
@NoArgsConstructor
@Schema(
    title = "Deleted split",
    description = "A split file deleted along with its index."
)
public class DeletedSplit {
    @Schema(
        title = "Split ID",
        description = "Unique identifier of the deleted split."
    )
    @JsonProperty("split_id")
    private String splitId;

    @Schema(
        title = "Number of documents",
        description = "Number of documents held by the split before deletion."
    )
    @JsonProperty("num_docs")
    private Long numDocs;

    @Schema(
        title = "Uncompressed documents size",
        description = "Size in bytes of the documents held by the split, uncompressed."
    )
    @JsonProperty("uncompressed_docs_size_bytes")
    private Long uncompressedDocsSizeBytes;

    @Schema(
        title = "File name",
        description = "Name of the split file."
    )
    @JsonProperty("file_name")
    private String fileName;

    @Schema(
        title = "File size",
        description = "Size in bytes of the split file."
    )
    @JsonProperty("file_size_bytes")
    private Long fileSizeBytes;
}