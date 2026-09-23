package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.ResourceKind;
import com.yourapp.drama.workflow.StudioService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {
    private final StudioService studio;
    public ResourceController(StudioService studio){this.studio=studio;}
    @GetMapping("/{kind}") public List<ObjectNode> list(@PathVariable String kind,@RequestParam(required=false)String projectId,@RequestParam(required=false)String parentId){return studio.list(ResourceKind.fromPath(kind),projectId,parentId);}
    @GetMapping("/{kind}/{id}") public ObjectNode get(@PathVariable String kind,@PathVariable String id){return studio.get(ResourceKind.fromPath(kind),id);}
    @PostMapping("/{kind}") public ObjectNode create(@PathVariable String kind,@RequestBody ObjectNode body){return studio.create(ResourceKind.fromPath(kind),body);}
    @PostMapping("/character-knowledge/transition") public ObjectNode transitionCharacterKnowledge(@RequestBody ObjectNode body){return studio.transitionCharacterKnowledge(body);}
    @PutMapping("/{kind}/{id}") public ObjectNode update(@PathVariable String kind,@PathVariable String id,@RequestBody ObjectNode body){return studio.update(ResourceKind.fromPath(kind),id,body);}
    @GetMapping("/projects/{id}/workspace") public ObjectNode workspace(@PathVariable String id){return studio.workspace(id);}
}
