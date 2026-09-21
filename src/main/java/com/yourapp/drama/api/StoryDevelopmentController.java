package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.StoryDevelopmentService;
import org.springframework.web.bind.annotation.*;
import static com.yourapp.drama.workflow.Documents.obj;

@RestController
@RequestMapping("/api/story-development")
public class StoryDevelopmentController {
    private final StoryDevelopmentService service;
    public StoryDevelopmentController(StoryDevelopmentService service){this.service=service;}
    @PostMapping("/projects/{id}/core") public ObjectNode core(@PathVariable String id,@RequestBody(required=false) ObjectNode body){return service.start(id,body==null?obj():body);}
    @PutMapping("/documents/{id}") public ObjectNode edit(@PathVariable String id,@RequestBody ObjectNode body){return service.edit(id,body);}
    @PostMapping("/documents/{id}/confirm") public ObjectNode confirm(@PathVariable String id,@RequestBody ObjectNode body){return service.confirm(id,body);}
    @PostMapping("/documents/{id}/retry") public ObjectNode retry(@PathVariable String id){return service.retry(id);}
    @PostMapping("/documents/{id}/rewrite") public ObjectNode rewrite(@PathVariable String id,@RequestBody ObjectNode body){return service.rewrite(id,body);}
    @PostMapping("/documents/{id}/premise") public ObjectNode premise(@PathVariable String id,@RequestBody ObjectNode body){return service.reviewPremise(id,body);}
}
