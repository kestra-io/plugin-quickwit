package io.kestra.plugin.quickwit.search;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.quickwit.AbstractQuickwitTest;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TriggerTest extends AbstractQuickwitTest {
    private static final String TWO_HITS = """
        {"num_hits": 2, "elapsed_time_micros": 10, "hits": [{"message": "one"}, {"message": "two"}]}
        """;

    private static final String NO_HIT = """
        {"num_hits": 0, "hits": []}
        """;

    @Test
    void firesOnTheFirstPoll(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(TWO_HITS)));

        var context = contextFor(trigger(wireMock));

        assertThat(context.trigger().evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(true));
    }

    @Test
    void doesNotFireWhenTheQueryMatchesNothing(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(NO_HIT)));

        var context = contextFor(trigger(wireMock));

        assertThat(context.trigger().evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(false));
    }

    @Test
    void exposesTheNewDocumentsAndTheWatermark(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(TWO_HITS)));

        var context = contextFor(trigger(wireMock));
        var execution = context.trigger().evaluate(context.conditionContext(), context.triggerContext()).orElseThrow();

        var variables = execution.getTrigger().getVariables();

        assertThat(variables.get("index"), is("app-logs"));
        assertThat((List<?>) variables.get("documents"), hasSize(2));
        assertThat(variables.get("numHits"), is(2L));
        assertThat((Long) variables.get("watermark"), is(Long.parseLong(watermark(context))));
    }

    @Test
    void storesTheWatermarkSoTheSameDocumentIsNeverDeliveredTwice(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(TWO_HITS)));

        var context = contextFor(trigger(wireMock));

        assertThat(context.trigger().evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(true));

        var stored = watermark(context);
        assertThat("the watermark must be persisted", stored, matchesPattern("\\d+"));

        assertEquals(
            stored,
            new String((byte[]) context.conditionContext().getRunContext()
                .namespaceKv(context.triggerContext().getNamespace())
                .getValue(context.stateKey())
                .orElseThrow()
                .value(), StandardCharsets.UTF_8),
            "the watermark must be persisted under the trigger's state key"
        );
    }

    @Test
    void doesNotFireAgainWhileTheQueryKeepsReturningTheSameHits(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(TWO_HITS)));

        var context = contextFor(trigger(wireMock));
        var trigger = context.trigger();

        assertThat(trigger.evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(true));

        // second poll: Quickwit only returns documents after the watermark it just stored
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(NO_HIT)));

        assertThat(trigger.evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(false));
    }

    @Test
    void searchesOnlyWhatCameAfterTheWatermark(WireMockRuntimeInfo wireMock) throws Exception {
        var trigger = trigger(wireMock);
        var context = contextFor(trigger);

        // a watermark left by a previous poll must be sent as start_timestamp
        putWatermark(context, 1_700_000_000L);

        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(NO_HIT)));
        trigger.evaluate(context.conditionContext(), context.triggerContext());

        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/search"))
            .withRequestBody(equalToJson("{\"query\": \"severity:ERROR\", \"start_timestamp\": 1700000000}"))
        );
    }

    private Trigger trigger(WireMockRuntimeInfo wireMock) {
        return Trigger.builder()
            .id(IdUtils.create())
            .type(TriggerTest.class.getName())
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("severity:ERROR"))
            .build();
    }

    /** The plugin trigger together with the context pair `TestsUtils.mockTrigger` builds for it. */
    private record Context(ConditionContext conditionContext, io.kestra.core.models.triggers.Trigger triggerContext, Trigger trigger) {
        String stateKey() {
            return triggerContext.getNamespace() + "_" + triggerContext.getFlowId() + "_" + trigger.getId();
        }
    }

    private Context contextFor(Trigger trigger) {
        var entry = TestsUtils.mockTrigger(runContextFactory, trigger);

        return new Context(entry.getKey(), entry.getValue(), trigger);
    }

    private String watermark(Context context) throws Exception {
        return new String((byte[]) context.conditionContext().getRunContext()
            .namespaceKv(context.triggerContext().getNamespace())
            .getValue(context.stateKey())
            .orElseThrow()
            .value(), StandardCharsets.UTF_8).trim();
    }

    private void putWatermark(Context context, long watermark) throws Exception {
        context.conditionContext().getRunContext()
            .namespaceKv(context.triggerContext().getNamespace())
            .put(
                context.stateKey(),
                new KVValueAndMetadata(
                    new KVMetadata("watermark", (Duration) null),
                    Long.toString(watermark).getBytes(StandardCharsets.UTF_8)
                )
            );
    }
}