package com.acme.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 个人工作页的近期动态分区：只对本人展示站内通知。 */
@Component
class WorkPageActivitySectionProvider implements WorkPageSectionProvider {

    private static final int LIMIT = 5;

    private final WorkPageWorkStore store;

    WorkPageActivitySectionProvider(WorkPageWorkStore store) {
        this.store = store;
    }

    @Override
    public String key() {
        return "activity";
    }

    @Override
    public Object load(UUID subjectPersonId, boolean self) {
        if (!self) {
            return null;
        }
        List<Map<String, Object>> items = store.recentActivity(subjectPersonId, LIMIT).stream()
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", entry.title());
                    item.put("resourceType", entry.resourceType());
                    item.put("read", entry.read());
                    item.put("occurredAt", entry.occurredAt());
                    return item;
                })
                .toList();
        return Map.of("items", items);
    }
}
