package com.acme.platform;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 个人工作页的授权文件分区：本人所在项目下的文件引用数。 */
@Component
class WorkPageFilesSectionProvider implements WorkPageSectionProvider {

    private final WorkPageWorkStore store;

    WorkPageFilesSectionProvider(WorkPageWorkStore store) {
        this.store = store;
    }

    @Override
    public String key() {
        return "files";
    }

    @Override
    public Object load(UUID subjectPersonId, boolean self) {
        return Map.of("count", store.authorizedFileCount(subjectPersonId));
    }
}
