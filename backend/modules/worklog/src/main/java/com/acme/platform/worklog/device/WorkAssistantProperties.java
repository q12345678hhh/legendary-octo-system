package com.acme.platform.worklog.device;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作助手设备链路配置。
 *
 * <p>两个签名私钥只从环境变量读取，源码里没有默认值：缺少私钥时相关端点直接 503，
 * 不允许退化成「不签名也能下发策略」。
 */
@ConfigurationProperties(prefix = "platform.work-assistant")
public class WorkAssistantProperties {

    /** 策略签名私钥（ECDSA P-256，PKCS8 base64）。 */
    private String policyPrivateKey = "";
    /** 对应公钥（X.509 SPKI base64），注册接口下发给设备固定。 */
    private String policyPublicKeySpki = "";
    private String policyKeyId = "local-policy-1";
    /** 更新清单签名私钥（ECDSA P-256，PKCS8 base64）。 */
    private String updatePrivateKey = "";
    private String updatePublicKeySpki = "";
    private String updateKeyId = "local-update-1";
    /** 请求签名允许的时钟偏差。 */
    private Duration clockSkew = Duration.ofMinutes(5);
    /** 低于该版本的工作助手必须升级后才能继续上报。 */
    private String minSupportedVersion = "0.1.0";
    private String updateVersion = "0.1.1";
    private String updatePackageUrl = "";
    private String updatePackageSha256 = "";

    public String getPolicyPrivateKey() {
        return policyPrivateKey;
    }

    public void setPolicyPrivateKey(String policyPrivateKey) {
        this.policyPrivateKey = policyPrivateKey;
    }

    public String getPolicyKeyId() {
        return policyKeyId;
    }

    public void setPolicyKeyId(String policyKeyId) {
        this.policyKeyId = policyKeyId;
    }

    public String getPolicyPublicKeySpki() {
        return policyPublicKeySpki;
    }

    public void setPolicyPublicKeySpki(String policyPublicKeySpki) {
        this.policyPublicKeySpki = policyPublicKeySpki;
    }

    public String getUpdatePrivateKey() {
        return updatePrivateKey;
    }

    public void setUpdatePrivateKey(String updatePrivateKey) {
        this.updatePrivateKey = updatePrivateKey;
    }

    public String getUpdatePublicKeySpki() {
        return updatePublicKeySpki;
    }

    public void setUpdatePublicKeySpki(String updatePublicKeySpki) {
        this.updatePublicKeySpki = updatePublicKeySpki;
    }

    public String getUpdateKeyId() {
        return updateKeyId;
    }

    public void setUpdateKeyId(String updateKeyId) {
        this.updateKeyId = updateKeyId;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public String getMinSupportedVersion() {
        return minSupportedVersion;
    }

    public void setMinSupportedVersion(String minSupportedVersion) {
        this.minSupportedVersion = minSupportedVersion;
    }

    public String getUpdateVersion() {
        return updateVersion;
    }

    public void setUpdateVersion(String updateVersion) {
        this.updateVersion = updateVersion;
    }

    public String getUpdatePackageUrl() {
        return updatePackageUrl;
    }

    public void setUpdatePackageUrl(String updatePackageUrl) {
        this.updatePackageUrl = updatePackageUrl;
    }

    public String getUpdatePackageSha256() {
        return updatePackageSha256;
    }

    public void setUpdatePackageSha256(String updatePackageSha256) {
        this.updatePackageSha256 = updatePackageSha256;
    }
}
