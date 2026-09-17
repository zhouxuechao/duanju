package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.AssetViewService;
import org.springframework.web.bind.annotation.*;
import static com.yourapp.drama.workflow.Documents.obj;

@RestController
@RequestMapping("/api/asset-views")
public class AssetViewController {
    private final AssetViewService service;
    public AssetViewController(AssetViewService service){this.service=service;}
    @PostMapping("/projects/{projectId}/generate") public ObjectNode generate(@PathVariable String projectId,@RequestBody(required=false) ObjectNode request){return service.generate(projectId,request==null?obj():request);}
    @PostMapping("/{id}/approve") public ObjectNode approve(@PathVariable String id,@RequestBody ObjectNode request){return service.approve(id,request);}
    @PostMapping("/{id}/reject") public ObjectNode reject(@PathVariable String id,@RequestBody ObjectNode request){return service.reject(id,request);}
    @PostMapping("/{id}/regenerate") public ObjectNode regenerate(@PathVariable String id,@RequestBody ObjectNode request){return service.regenerate(id,request);}
    @PostMapping("/{id}/archive") public ObjectNode archive(@PathVariable String id){return service.archive(id);}
}
