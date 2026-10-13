package io.kestra.plugin.quickwit.ingest;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * When documents sent to the ingest API become visible to search.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#controlling-when-the-indexed-documents-will-be-available-for-search">Commit behaviour</a>
 */
@Schema(
    title = "Commit behavior",
    description = """
        When newly ingested documents become visible to search.

        - `AUTO` (default): return as soon as the documents are queued, they are searchable after the
          next split commit.
        - `WAIT_FOR`: wait for the next commit, following the `commit_timeout_secs` and
          `split_num_docs_target` rules.
        - `FORCE`: force a commit as soon as this batch is processed, and wait for it. Use it when the
          documents must be searchable right after the task, for example before an immediate search.
          This has a real performance cost on small batches.
        """
)
public enum Commit {
    AUTO,
    WAIT_FOR,
    FORCE
}