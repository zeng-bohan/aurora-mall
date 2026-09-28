package com.zengbohan.aurora.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Hand-rolled HS256 JWT codec (ADR-0006): deliberately free of JWT libraries
 * so signing, verification and expiry semantics stay inspectable.
 */
public class JwtCodec {

    public static final String TYP_ACCESS = "access";
    public static final String TYP_REFRESH = "refresh";

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String HEADER_B64 = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");

    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] secret;
    private final Clock clock;

    public JwtCodec(String secret) {
        this(secret, Clock.systemUTC());
    }

    JwtCodec(String secret, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("jwt secret must be at least 32 bytes");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String encode(Claims claims) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("sub", claims.subject());
            payload.put("role", claims.role());
            payload.put("jti", claims.jti());
            payload.put("typ", claims.typ());
            payload.put("iat", claims.issuedAt().getEpochSecond());
            payload.put("exp", claims.expiresAt().getEpochSecond());
            String signingInput = HEADER_B64 + "." + B64.encodeToString(mapper.writeValueAsBytes(payload));
            return signingInput + "." + sign(signingInput);
        } catch (Exception e) {
            throw new JwtException("failed to encode token", e);
        }
    }

    public Claims decode(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtException("malformed token");
        }
        String signingInput = parts[0] + "." + parts[1];
        boolean signatureOk = MessageDigest.isEqual(
                sign(signingInput).getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII));
        if (!signatureOk) {
            throw new JwtException("signature mismatch");
        }
        try {
            JsonNode p = mapper.readTree(B64D.decode(parts[1]));
            long exp = p.get("exp").asLong();
            if (clock.instant().getEpochSecond() >= exp) {
                throw new JwtExpiredException();
            }
            return new Claims(
                    p.get("sub").asText(),
                    p.get("role").asText(),
                    p.get("jti").asText(),
                    p.get("typ").asText(),
                    Instant.ofEpochSecond(p.get("iat").asLong()),
                    Instant.ofEpochSecond(exp));
        } catch (JwtException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtException("malformed token", e);
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return B64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new JwtException("failed to sign", e);
        }
    }

    private static String b64(String s) {
        return B64.encodeToString(s.getBytes(StandardCharsets.US_ASCII));
    }

    public record Claims(String subject, String role, String jti, String typ, Instant issuedAt, Instant expiresAt) {
    }
}
