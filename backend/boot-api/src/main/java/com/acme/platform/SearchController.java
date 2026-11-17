package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.collaboration.CollaborationStore;
import com.acme.platform.collaboration.SearchService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 协作搜索：SQL 查询时就按频道/项目成员过滤，不返回无权内容。 */
@RestController
@RequestMapping("/api/v1/search")
class SearchController {

    private final SearchService search;
    private final AuthorizationGuard authorization;
    private final CollabActorResolver actors;

    SearchController(SearchService search, AuthorizationGuard authorization,
                     CollabActorResolver actors) {
        this.search = search;
        this.authorization = authorization;
        this.actors = actors;
    }

    @GetMapping
    List<CollaborationStore.SearchHit> search(@RequestParam(name = "q", defaultValue = "") String q,
                                              @RequestParam(defaultValue = "20") int limit) {
        AccessDecision decision = authorization.require("search.read", "search", null, Map.of());
        return search.search(actors.viewer(decision), q, limit);
    }
}
