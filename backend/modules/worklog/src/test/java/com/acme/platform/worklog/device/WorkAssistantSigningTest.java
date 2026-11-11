package com.acme.platform.worklog.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 签名规范串与 P-256 验签：客户端与服务端必须对同一段字节得到相同结论。 */
class WorkAssistantSigningTest {

    @Test
    void 策略规范串与排除项顺序无关() {
        String first = CanonicalForms.policy(3, "2026-11-11T00:00:00Z",
                List.of("WeChat", "DingTalk"), List.of("微信", "Slack"), true);
        String second = CanonicalForms.policy(3, "2026-11-11T00:00:00Z",
                List.of("DingTalk", "WeChat"), List.of("Slack", "微信"), true);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void 请求规范串覆盖方法路径时间戳与请求体() {
        String base = CanonicalForms.request("POST", "/api/v1/work-assistant/segments", "1700000000", "{\"a\":1}");
        assertThat(CanonicalForms.request("GET", "/api/v1/work-assistant/segments", "1700000000", "{\"a\":1}"))
                .isNotEqualTo(base);
        assertThat(CanonicalForms.request("POST", "/api/v1/work-assistant/segments", "1700000001", "{\"a\":1}"))
                .isNotEqualTo(base);
        assertThat(CanonicalForms.request("POST", "/api/v1/work-assistant/segments", "1700000000", "{\"a\":2}"))
                .isNotEqualTo(base);
    }

    @Test
    void 签名可验证且篡改后失败() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("EC").generateKeyPair();
        WorkAssistantProperties properties = new WorkAssistantProperties();
        properties.setPolicyPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        properties.setPolicyPublicKeySpki(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));

        WorkAssistantKeys keys = new WorkAssistantKeys(properties);
        String canonical = CanonicalForms.policy(1, "2026-11-11T00:00:00Z",
                List.of("WeChat"), List.of("微信"), true);
        String signature = keys.signPolicy(canonical);

        assertThat(WorkAssistantKeys.verify(properties.getPolicyPublicKeySpki(), canonical, signature)).isTrue();
        assertThat(WorkAssistantKeys.verify(properties.getPolicyPublicKeySpki(), canonical + "x", signature)).isFalse();
        assertThat(WorkAssistantKeys.verify(properties.getPolicyPublicKeySpki(), canonical, "not-a-signature")).isFalse();
    }

    @Test
    void 未配置私钥时拒绝签发() {
        WorkAssistantKeys keys = new WorkAssistantKeys(new WorkAssistantProperties());
        assertThat(keys.policyConfigured()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> keys.signPolicy("x"))
                .hasMessageContaining("未配置策略签名私钥");
    }

    @Test
    void 版本比较只按数字段() {
        assertThat(DeviceService.compareVersions("0.1.0", "0.1.0")).isZero();
        assertThat(DeviceService.compareVersions("0.0.9", "0.1.0")).isNegative();
        assertThat(DeviceService.compareVersions("1.10.0", "1.9.9")).isPositive();
        assertThat(DeviceService.compareVersions("0.1.0-beta", "0.1.0")).isZero();
    }
}
