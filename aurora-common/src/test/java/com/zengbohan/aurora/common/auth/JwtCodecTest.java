package com.zengbohan.aurora.common.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtCodecTest {

    private static final String SECRET = "test-secret-0123456789abcdef-0123456789";
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    private JwtCodec codec(Clock clock) {
        return new JwtCodec(SECRET, clock);
    }

    private JwtCodec.Claims sampleClaims(Instant issuedAt, Duration ttl) {
        return new JwtCodec.Claims("42", "USER", "jti-1", JwtCodec.TYP_ACCESS, issuedAt, issuedAt.plus(ttl));
    }

    @Test
    void roundTripPreservesClaims() {
        JwtCodec codec = codec(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = codec.encode(sampleClaims(NOW, Duration.ofMinutes(30)));

        JwtCodec.Claims parsed = codec.decode(token);

        assertThat(parsed.subject()).isEqualTo("42");
        assertThat(parsed.role()).isEqualTo("USER");
        assertThat(parsed.jti()).isEqualTo("jti-1");
        assertThat(parsed.typ()).isEqualTo(JwtCodec.TYP_ACCESS);
        assertThat(parsed.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtCodec codec = codec(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = codec.encode(sampleClaims(NOW, Duration.ofSeconds(60)));

        JwtCodec afterExpiry = codec(Clock.fixed(NOW.plus(Duration.ofSeconds(61)), ZoneOffset.UTC));

        assertThatThrownBy(() -> afterExpiry.decode(token))
                .isInstanceOf(JwtExpiredException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        JwtCodec codec = codec(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = codec.encode(sampleClaims(NOW, Duration.ofMinutes(30)));
        String[] parts = token.split("\\.");

        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"999\",\"role\":\"ADMIN\"}".getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> codec.decode(forged))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void wrongKeyIsRejected() {
        JwtCodec signer = codec(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = signer.encode(sampleClaims(NOW, Duration.ofMinutes(30)));

        JwtCodec other = new JwtCodec("another-secret-0123456789abcdef-012345",
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> other.decode(token))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void shortSecretIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new JwtCodec("too-short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> codec(Clock.fixed(NOW, ZoneOffset.UTC)).decode("not-a-jwt"))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("malformed");
    }
}
