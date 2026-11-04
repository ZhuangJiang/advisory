package com.advisory.provider.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic proof of {@link ProviderProbe#classify(IOException)}: no network, no timing, no
 * threads — every case is a plain exception object.
 *
 * <p>The point of the two {@code isAssignableFrom} assertions is to pin down the JDK inheritance
 * the reviewer questioned: {@code HttpConnectTimeoutException < HttpTimeoutException}, so the
 * single {@code HttpTimeoutException} branch already returns {@code TIMEOUT} for a <em>connect</em>
 * timeout. Were the two unrelated, the connect-timeout assertions below would fail.</p>
 */
class ProviderProbeClassifyTest {

    // -------------------------------------------------------------- inheritance evidence

    @Test
    void httpConnectTimeoutExceptionIsAnHttpTimeoutExceptionInTheRunningJdk() {
        assertTrue(HttpTimeoutException.class.isAssignableFrom(HttpConnectTimeoutException.class),
                "JDK contract: a connect timeout IS a timeout exception");
        assertTrue(IOException.class.isAssignableFrom(HttpTimeoutException.class),
                "JDK contract: a timeout exception IS an IOException, so classify() sees it");
    }

    // -------------------------------------------------------------- timeout branch

    @Test
    void requestTimeoutClassifiesAsTimeout() {
        assertEquals(ProbeResult.TIMEOUT,
                ProviderProbe.classify(new HttpTimeoutException("request timed out")));
    }

    @Test
    void connectTimeoutClassifiesAsTimeout() {
        assertEquals(ProbeResult.TIMEOUT,
                ProviderProbe.classify(new HttpConnectTimeoutException("connect timed out")));
    }

    @Test
    void rawSocketTimeoutClassifiesAsTimeout() {
        assertEquals(ProbeResult.TIMEOUT,
                ProviderProbe.classify(new SocketTimeoutException("Read timed out")));
    }

    @Test
    void wrappedConnectTimeoutClassifiesAsTimeout() {
        // What the JDK actually hands back from a wrapped send: the connect timeout is nested
        // inside a plain IOException, so classify() has to walk the cause chain to find it.
        IOException wrapped = new IOException("send failed",
                new HttpConnectTimeoutException("connect timed out"));
        assertEquals(ProbeResult.TIMEOUT, ProviderProbe.classify(wrapped));
    }

    // -------------------------------------------------------------- unreachable branch

    @Test
    void connectRefusedClassifiesAsUnreachable() {
        assertEquals(ProbeResult.NETWORK_UNREACHABLE,
                ProviderProbe.classify(new ConnectException("Connection refused")));
    }

    @Test
    void unknownHostClassifiesAsUnreachable() {
        assertEquals(ProbeResult.NETWORK_UNREACHABLE,
                ProviderProbe.classify(new UnknownHostException("no-such-host.invalid")));
    }

    @Test
    void wrappedUnknownHostClassifiesAsUnreachable() {
        IOException wrapped = new IOException("send failed",
                new UnknownHostException("no-such-host.invalid"));
        assertEquals(ProbeResult.NETWORK_UNREACHABLE, ProviderProbe.classify(wrapped));
    }

    @Test
    void unrelatedIoExceptionClassifiesAsUnreachable() {
        assertEquals(ProbeResult.NETWORK_UNREACHABLE, ProviderProbe.classify(new IOException("boom")));
    }

    @Test
    void causeChainIsBoundedSoACyclicCauseCannotHangTheProbe() {
        IOException selfReferencing = new IOException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals(ProbeResult.NETWORK_UNREACHABLE, ProviderProbe.classify(selfReferencing));
    }
}
