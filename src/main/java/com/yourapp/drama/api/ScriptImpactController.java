package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class ScriptImpactController {
    private final ScriptImpactAnalyzer impacts;private final SelectiveRebuildService rebuilds;private final PlatformReviewService reviews;
    public ScriptImpactController(ScriptImpactAnalyzer impacts,SelectiveRebuildService rebuilds,PlatformReviewService reviews){this.impacts=impacts;this.rebuilds=rebuilds;this.reviews=reviews;}
    @PostMapping("/script-versions/{from}/impact/{to}") public ObjectNode impact(@PathVariable String from,@PathVariable String to,@RequestBody ObjectNode body){ChangeScope scope=ChangeScope.valueOf(body.path("changeScope").asText("FROM_CURRENT_POINT"));return impacts.analyze(from,to,scope,ChangePoint.from(body.path("changePoint")));}
    @PostMapping("/impact-plans/{id}/rebuild-plan") public ObjectNode rebuild(@PathVariable String id){return rebuilds.prepare(id);}
    @PostMapping("/platform-review-issues") public ObjectNode reject(@RequestBody ObjectNode body){return reviews.reject(body);}
}
