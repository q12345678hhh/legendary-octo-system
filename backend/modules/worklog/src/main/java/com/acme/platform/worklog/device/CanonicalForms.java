package com.acme.platform.worklog.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 签名规范串：客户端与服务端必须逐字一致，否则验签结果没有意义。
 *
 * <p>所有列表都按序数排序（区分大小写），用逗号连接；时间一律用 UTC 秒级 ISO-8601。
 */
public final class CanonicalForms {

    private CanonicalForms() {
    }

    /** 设备请求：方法、路径、时间戳、请求体哈希。 */
    public static String request(String method, String path, String timestamp, String body) {
        return method.toUpperCase() + "\n"
                + path + "\n"
                + timestamp + "\n"
                + sha256Hex(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    /** 策略信封：版本、签发时间、有效排除项、上传开关。 */
    public static String policy(int version, String issuedAt, List<String> processes,
                                List<String> titleKeywords, boolean uploadEnabled) {
        return version + "|" + issuedAt + "|" + join(processes) + "|" + join(titleKeywords) + "|" + uploadEnabled;
    }

    /** 更新清单：版本、包哈希、下载地址、最低支持版本、发布时间。 */
    public static String update(String version, String packageSha256, String packageUrl,
                                String minSupportedVersion, String releasedAt) {
        return version + "|" + packageSha256 + "|" + packageUrl + "|" + minSupportedVersion + "|" + releasedAt;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    private static String join(List<String> values) {
        List<String> sorted = new ArrayList<>(values == null ? List.of() : values);
        sorted.sort(String::compareTo);
        return String.join(",", sorted);
    }
}
