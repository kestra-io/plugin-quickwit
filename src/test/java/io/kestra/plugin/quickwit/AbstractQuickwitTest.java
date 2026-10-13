package io.kestra.plugin.quickwit;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

/**
 * Base class for the Quickwit tests.
 *
 * <p>Every test runs against a WireMock server standing in for the Quickwit REST API, so no Quickwit
 * cluster is needed and the assertions can cover error paths as well as the happy path.
 */
@KestraTest
@WireMockTest
public abstract class AbstractQuickwitTest {
    @Inject
    protected RunContextFactory runContextFactory;

    /** Base URL of the WireMock server, to be used as the `url` of a task. */
    protected static String url(WireMockRuntimeInfo wireMock) {
        return "http://localhost:" + wireMock.getHttpPort();
    }
}