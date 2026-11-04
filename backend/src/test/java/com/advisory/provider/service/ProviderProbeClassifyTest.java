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
 * 对 {@link ProviderProbe#classify(IOException)} 的确定性验证：不联网、不看时序、不起线程，
 * 每个分支都只喂一个异常对象。
 *
 * <p>那两条 {@code isAssignableFrom} 断言是为了钉住 JDK 的继承关系——评审时曾对此存疑：
 * {@code HttpConnectTimeoutException < HttpTimeoutException}，所以仅凭
 * {@code HttpTimeoutException} 一个分支，就能让 <em>连接</em> 超时也返回 {@code TIMEOUT}。
 * 若二者其实无继承关系，下面几条连接超时的断言就会失败。</p>
 */
class ProviderProbeClassifyTest {

    // -------------------------------------------------------------- 继承关系的证据

    @Test
    void httpConnectTimeoutExceptionIsAnHttpTimeoutExceptionInTheRunningJdk() {
        assertTrue(HttpTimeoutException.class.isAssignableFrom(HttpConnectTimeoutException.class),
                "JDK contract: a connect timeout IS a timeout exception");
        assertTrue(IOException.class.isAssignableFrom(HttpTimeoutException.class),
                "JDK contract: a timeout exception IS an IOException, so classify() sees it");
    }

    // -------------------------------------------------------------- 超时分支

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
        // JDK 在包装后的 send 里实际抛出的形态：连接超时被裹在普通 IOException 里，
        // 因此 classify() 必须沿 cause 链向下钻才能找到它。
        IOException wrapped = new IOException("send failed",
                new HttpConnectTimeoutException("connect timed out"));
        assertEquals(ProbeResult.TIMEOUT, ProviderProbe.classify(wrapped));
    }

    // -------------------------------------------------------------- 不可达分支

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
