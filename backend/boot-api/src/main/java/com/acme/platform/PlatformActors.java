package com.acme.platform;

import com.acme.platform.identity.operator.PlatformActorContext;
import com.acme.platform.identity.operator.PlatformActorContextHolder;
import com.acme.platform.identity.operator.PlatformAuthorization;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.audit.PlatformOperatorRef;

/** 平台控制器共用的取值与鉴权入口。 */
final class PlatformActors {

    private PlatformActors() {
    }

    static PlatformActorContext require() {
        return PlatformActorContextHolder.find().orElseThrow(() ->
                new ApiException(401, "PLATFORM_SESSION_REQUIRED", "平台接口必须使用运营账号登录"));
    }

    static PlatformActorContext require(String permission) {
        PlatformActorContext actor = require();
        PlatformAuthorization.require(actor, permission);
        return actor;
    }

    static PlatformActorContext requireManage(String permission) {
        PlatformActorContext actor = require();
        PlatformAuthorization.requireManage(actor, permission);
        return actor;
    }

    static PlatformOperatorRef ref(PlatformActorContext actor) {
        return new PlatformOperatorRef(actor.operatorId(), actor.email());
    }
}
