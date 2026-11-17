package com.acme.platform;

import com.acme.platform.tenancy.retention.LegalHold;
import com.acme.platform.tenancy.retention.RetentionBinItem;
import com.acme.platform.tenancy.retention.RetentionService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回收站接口：只操作当前租户的条目，RLS 兜底。
 */
@RestController
@RequestMapping("/api/v1/retention")
class RetentionController {

    record BinItemView(
            UUID id,
            String dataClass,
            UUID objectId,
            String objectRef,
            String status,
            String deletedBy,
            OffsetDateTime deletedAt,
            OffsetDateTime purgeAfter,
            OffsetDateTime restoredAt,
            OffsetDateTime purgedAt) {

        static BinItemView from(RetentionBinItem item) {
            return new BinItemView(item.id(), item.dataClass(), item.objectId(), item.objectRef(),
                    item.status(), item.deletedBy(), item.deletedAt(), item.purgeAfter(),
                    item.restoredAt(), item.purgedAt());
        }
    }

    record HoldView(UUID id, String dataClass, UUID objectId, String reason,
                    String createdBy, OffsetDateTime createdAt) {

        static HoldView from(LegalHold hold) {
            return new HoldView(hold.id(), hold.dataClass(), hold.objectId(), hold.reason(),
                    hold.createdBy(), hold.createdAt());
        }
    }

    private final RetentionService service;

    RetentionController(RetentionService service) {
        this.service = service;
    }

    @GetMapping("/bin")
    List<BinItemView> bin(@RequestParam(defaultValue = "50") int limit) {
        return service.list(limit).stream().map(BinItemView::from).toList();
    }

    @PostMapping("/bin/{id}/restore")
    BinItemView restore(@PathVariable UUID id) {
        return BinItemView.from(service.restore(id));
    }

    @DeleteMapping("/bin/{id}")
    BinItemView purge(@PathVariable UUID id) {
        return BinItemView.from(service.purgeNow(id));
    }

    @GetMapping("/holds")
    List<HoldView> holds() {
        return service.holds().stream().map(HoldView::from).toList();
    }
}
