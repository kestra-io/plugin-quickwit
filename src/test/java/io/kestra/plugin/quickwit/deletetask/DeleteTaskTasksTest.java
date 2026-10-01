package io.kestra.plugin.quickwit.deletetask;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickwit.AbstractQuickwitTest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Happy-path and error coverage for the delete task tasks.
 */
class DeleteTaskTasksTest extends AbstractQuickwitTest {
    private static final String DELETE_TASK = """
        {
          "create_timestamp": 1700000000,
          "opstamp": 42,
          "delete_query": {
            "query": "message:trash",
            "search_field": ["message"],
            "start_timestamp": 1699000000,
            "end_timestamp": 1700000000
          }
        }
        """;

    @Test
    void createsDeleteTask(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/delete-tasks")).willReturn(okJson(DELETE_TASK)));

        var output = Create.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("message:trash"))
            .startTimestamp(Property.ofValue(1699000000L))
            .endTimestamp(Property.ofValue(1700000000L))
            .searchField(Property.ofValue(List.of("message")))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));
        assertThat(output.getCreateTimestamp(), is(1700000000L));
        assertThat(output.getOpstamp(), is(42L));
        assertThat(output.getDeleteTask().getDeleteQuery().getQuery(), is("message:trash"));
        assertThat(output.getDeleteTask().getDeleteQuery().getSearchField(), is(List.of("message")));

        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/delete-tasks"))
            .withRequestBody(equalToJson("""
                {
                  "query": "message:trash",
                  "start_timestamp": 1699000000,
                  "end_timestamp": 1700000000,
                  "search_field": ["message"]
                }
                """))
        );
    }

    @Test
    void createOmitsUnsetOptionalParameters(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/delete-tasks")).willReturn(okJson(DELETE_TASK)));

        Create.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("*"))
            .build()
            .run(runContextFactory.of());

        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/delete-tasks"))
            .withRequestBody(equalToJson("{\"query\": \"*\"}"))
        );
    }

    @Test
    void listsDeleteTasks(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/app-logs/delete-tasks")).willReturn(okJson("[" + DELETE_TASK + "]")));

        var output = io.kestra.plugin.quickwit.deletetask.List.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));
        assertThat(output.getSize(), is(1));
        assertThat(output.getDeleteTasks(), hasSize(1));
        assertThat(output.getDeleteTasks().getFirst().getOpstamp(), is(42L));

        verify(getRequestedFor(urlPathEqualTo("/api/v1/app-logs/delete-tasks")));
    }

    @Test
    void listHandlesAnIndexWithoutDeleteTasks(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/app-logs/delete-tasks")).willReturn(okJson("[]")));

        var output = io.kestra.plugin.quickwit.deletetask.List.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getSize(), is(0));
        assertThat(output.getDeleteTasks(), hasSize(0));
    }

    @Test
    void surfacesTheQuickwitErrorOnAnUnknownIndex(WireMockRuntimeInfo wireMock) {
        stubFor(post(urlPathEqualTo("/api/v1/unknown/delete-tasks"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Index unknown does not exist\"}")
            )
        );

        var task = Create.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("unknown"))
            .query(Property.ofValue("*"))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(
            thrown.getMessage(),
            is("Quickwit creation of a delete task on index 'unknown' failed with HTTP 404: Index unknown does not exist")
        );
    }
}