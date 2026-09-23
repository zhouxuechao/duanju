package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.ResourceKind;
import com.yourapp.drama.workflow.SourceTraceabilityService;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class SourceTraceabilityController {
    private final SourceTraceabilityService traces;
    public SourceTraceabilityController(SourceTraceabilityService traces){this.traces=traces;}
    @GetMapping("/script-versions/{versionId}/source-trace") public ObjectNode script(@PathVariable String versionId){return traces.scriptVersion(versionId);}
    @GetMapping("/script-versions/{versionId}/elements/{type}/{key}/source-trace") public ObjectNode element(@PathVariable String versionId,@PathVariable String type,@PathVariable String key){return traces.scriptElement(versionId,type,key);}
    @GetMapping("/novel-chapters/{chapterId}/adaptations") public ObjectNode reverse(@PathVariable String chapterId){return traces.reverseChapter(chapterId);}
    @GetMapping("/media/{kind}/{id}/source-trace") public ObjectNode media(@PathVariable String kind,@PathVariable String id){return traces.media(ResourceKind.fromPath(kind),id);}
}
