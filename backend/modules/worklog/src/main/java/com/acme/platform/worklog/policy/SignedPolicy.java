package com.acme.platform.worklog.policy;

import java.util.List;

/**
 * 已签名的有效策略信封。
 *
 * <p>字段集合是封闭的：没有 {@code capture_enabled}、{@code auto_start} 一类控制位。
 * 客户端对未知字段直接拒收，因此也没有「第二个远程开关」可以藏在策略里。
 */
public record SignedPolicy(
        int policyVersion,
        String issuedAt,
        String keyId,
        List<String> processes,
        List<String> titleKeywords,
        boolean uploadEnabled,
        String signature) {
}
