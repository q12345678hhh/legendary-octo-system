package com.acme.platform.worklog.policy;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.worklog.device.CanonicalForms;
import com.acme.platform.worklog.device.WorkAssistantKeys;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 策略签发。
 *
 * <p>有效策略 = 平台基线 ∪ 租户追加项，版本单调递增；接口只能追加，不能删除或覆盖基线。
 * 每次签发都重新签名，客户端固定公钥并拒绝旧版本，因此服务端回滚策略也会被客户端拒绝。
 */
@Service
public class PolicyService {

    private static final Pattern PROCESS_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_ADDITIONS_PER_REQUEST = 50;

    private final PolicyStore policyStore;
    private final PolicyBaseline baseline;
    private final WorkAssistantKeys keys;
    private final TenantTransactions transactions;

    public PolicyService(PolicyStore policyStore, PolicyBaseline baseline,
                         WorkAssistantKeys keys, TenantTransactions transactions) {
        this.policyStore = policyStore;
        this.baseline = baseline;
        this.keys = keys;
        this.transactions = transactions;
    }

    /** 读取当前租户的签名策略；必须在租户事务内调用。 */
    public SignedPolicy current() {
        return transactions.inTenant(context -> sign(context.tenantId()));
    }

    /** 当前有效策略版本；无租户追加项时基线版本为 1。 */
    public int currentVersion(UUID tenantId) {
        return policyStore.find(tenantId).map(PolicyStore.TenantPolicy::version).orElse(1);
    }

    /** 追加排除项：只接受新增，空集合、通配符、路径分隔符与未知字符一律拒绝。 */
    public SignedPolicy append(List<String> additions, String updatedBy) {
        if (additions == null || additions.isEmpty()) {
            throw ApiException.unprocessable("POLICY_ADDITIONS_EMPTY", "至少提供一个要追加的排除进程");
        }
        if (additions.size() > MAX_ADDITIONS_PER_REQUEST) {
            throw ApiException.unprocessable("POLICY_ADDITIONS_TOO_MANY",
                    "单次最多追加 " + MAX_ADDITIONS_PER_REQUEST + " 项");
        }
        List<String> normalized = normalize(additions);
        return transactions.inTenant(context -> {
            PolicyStore.TenantPolicy stored = policyStore.lock(context.tenantId()).orElse(null);
            List<String> merged = merge(stored == null ? List.of() : stored.exclusions(), normalized);
            int version = stored == null ? 2 : stored.version() + 1;
            if (stored == null) {
                policyStore.insert(context.tenantId(), version, merged, updatedBy);
            } else {
                policyStore.update(context.tenantId(), version, merged, updatedBy);
            }
            return sign(context.tenantId());
        });
    }

    private SignedPolicy sign(UUID tenantId) {
        PolicyStore.TenantPolicy stored = policyStore.find(tenantId).orElse(null);
        int version = stored == null ? 1 : stored.version();
        List<String> processes = merge(baseline.processes(), stored == null ? List.of() : stored.exclusions());
        List<String> keywords = List.copyOf(baseline.titleKeywords());
        String issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String canonical = CanonicalForms.policy(version, issuedAt, processes, keywords, true);
        return new SignedPolicy(version, issuedAt, keys.policyKeyId(), processes, keywords,
                true, keys.signPolicy(canonical));
    }

    private static List<String> normalize(List<String> additions) {
        List<String> result = new ArrayList<>();
        for (String value : additions) {
            String trimmed = value == null ? "" : value.trim();
            if (!PROCESS_NAME.matcher(trimmed).matches()) {
                throw ApiException.unprocessable("POLICY_ADDITION_INVALID",
                        "排除项必须是 1-64 位字母、数字、点、下划线或连字符: " + trimmed);
            }
            result.add(trimmed);
        }
        return result;
    }

    /** 按不区分大小写去重，保留原有大小写；顺序不影响签名（签名前会按序数排序）。 */
    private static List<String> merge(List<String> base, List<String> additions) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : base) {
            if (seen.add(value.toLowerCase(Locale.ROOT))) {
                result.add(value);
            }
        }
        for (String value : additions) {
            if (seen.add(value.toLowerCase(Locale.ROOT))) {
                result.add(value);
            }
        }
        return result;
    }
}
