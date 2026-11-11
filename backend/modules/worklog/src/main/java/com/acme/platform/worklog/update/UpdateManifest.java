package com.acme.platform.worklog.update;

/**
 * 已签名的更新清单。
 *
 * <p>客户端用注册时固定的更新公钥验签，并校验包 SHA-256；任一步失败都不得安装。
 */
public record UpdateManifest(
        String version,
        String packageSha256,
        String packageUrl,
        String minSupportedVersion,
        String releasedAt,
        String keyId,
        String signature) {
}
