package com.zengbohan.aurora.payment.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 渠道签名契约：同参有效、金额篡改无效、缺签名无效、确定性（同参同签）。
class ChannelSignatureVerifierTest {

    private final ChannelSignatureVerifier verifier = new ChannelSignatureVerifier("unit-secret");

    @Test
    void validSignaturePasses() {
        String signature = verifier.sign(1001L, "39.80");
        assertThat(verifier.isValid(1001L, "39.80", signature)).isTrue();
    }

    @Test
    void tamperedAmountFails() {
        String signature = verifier.sign(1001L, "39.80");
        assertThat(verifier.isValid(1001L, "999.99", signature)).isFalse();
    }

    @Test
    void missingSignatureFails() {
        assertThat(verifier.isValid(1001L, "39.80", null)).isFalse();
        assertThat(verifier.isValid(1001L, "39.80", "")).isFalse();
    }

    @Test
    void signingIsDeterministic() {
        assertThat(verifier.sign(1001L, "39.80")).isEqualTo(verifier.sign(1001L, "39.80"));
    }
}
