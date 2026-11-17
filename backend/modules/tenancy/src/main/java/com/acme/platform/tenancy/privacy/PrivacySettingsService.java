package com.acme.platform.tenancy.privacy;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 租户隐私设置读写：天数必须在 1..3650 之间，驻留地只能取受支持区域。
 *
 * <p>缺行表示尚未配置，读取时回退默认值；写入按租户 upsert 并记录操作人。
 */
@Service
public class PrivacySettingsService {

    private static final Set<String> RESIDENCIES = Set.of("cn", "eu", "us", "sg");

    private final PrivacySettingsStore store;
    private final TenantTransactions transactions;

    public PrivacySettingsService(PrivacySettingsStore store, TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public record Input(String dataResidency, boolean trainingOptOut,
                        int voiceTranscriptRetentionDays, int expenseOcrRetentionDays) {
    }

    public PrivacySetting current() {
        return transactions.inTenant(ctx -> store.find(ctx.tenantId())
                .orElseGet(() -> PrivacySetting.defaults(ctx.tenantId())));
    }

    public PrivacySetting update(Input input) {
        String residency = input.dataResidency() == null ? "" : input.dataResidency().strip();
        if (!RESIDENCIES.contains(residency)) {
            throw ApiException.badRequest("PRIVACY_RESIDENCY_UNSUPPORTED",
                    "数据驻留地只支持 cn/eu/us/sg");
        }
        requireDays(input.voiceTranscriptRetentionDays(), "语音转写保留天数");
        requireDays(input.expenseOcrRetentionDays(), "单据 OCR 保留天数");
        return transactions.inTenant(ctx -> store.upsert(ctx.tenantId(), residency,
                input.trainingOptOut(), input.voiceTranscriptRetentionDays(),
                input.expenseOcrRetentionDays(), ctx.subjectId()));
    }

    private static void requireDays(int days, String label) {
        if (days < 1 || days > 3650) {
            throw ApiException.badRequest("PRIVACY_RETENTION_DAYS_INVALID",
                    label + "必须在 1 到 3650 天之间");
        }
    }
}
