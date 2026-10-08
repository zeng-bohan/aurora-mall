package com.zengbohan.aurora.payment.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * mock 支付渠道的回调签名校验（M4 验收项）：生产环境的第三方渠道
 * 回调不携带用户 token，靠渠道签名证明"回调确实来自渠道"。
 * <p>
 * 签名 = HMAC-SHA256(key = channel-secret, message = orderId + ":" + amount)，
 * amount 为回调 JSON 中金额字段的字符串原样，对应解析后的 BigDecimal#toPlainString（一致性由调用方保证）。
 * 校验用 {@link MessageDigest#isEqual} 常量时间比较。
 */
@Component
public class ChannelSignatureVerifier {

    public static final String HEADER = "X-Channel-Signature";

    private final byte[] secret;

    public ChannelSignatureVerifier(@Value("${aurora.payment.channel-secret}") String channelSecret) {
        if (channelSecret == null || channelSecret.isEmpty()) {
            throw new IllegalArgumentException("channel secret must not be empty");
        }
        this.secret = channelSecret.getBytes(StandardCharsets.UTF_8);
    }

    // 渠道侧对同一规范化串做 HMAC 的对偶方法（smoke/测试用）。
    public String sign(long orderId, String amount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(message(orderId, amount).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("hmac computation failed", e);
        }
    }

    // 常量时间校验：签名缺失直接 false。
    public boolean isValid(long orderId, String amount, String provided) {
        if (provided == null || provided.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(orderId, amount).getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String message(long orderId, String amount) {
        return orderId + ":" + amount;
    }
}
