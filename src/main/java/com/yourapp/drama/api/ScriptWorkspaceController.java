package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.ScriptWorkspaceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ScriptWorkspaceController {
    private final ScriptWorkspaceService scripts;
    public ScriptWorkspaceController(ScriptWorkspaceService scripts){this.scripts=scripts;}
    @GetMapping("/projects/{projectId}/scripts") public List<ObjectNode> project(@PathVariable String projectId){return scripts.projectScripts(projectId);}
    @GetMapping("/episodes/{episodeId}/script") public ObjectNode episode(@PathVariable String episodeId){return scripts.current(episodeId);}
    @GetMapping("/script-versions/{id}") public ObjectNode get(@PathVariable String id){return scripts.get(id);}
    @PostMapping("/episodes/{episodeId}/script/fork") public ObjectNode fork(@PathVariable String episodeId,@RequestBody ObjectNode body){return scripts.fork(episodeId,body);}
    @PutMapping("/script-versions/{id}") public ObjectNode update(@PathVariable String id,@RequestBody ObjectNode body){return scripts.update(id,body);}
    @PostMapping("/script-versions/{id}/confirm") public ObjectNode confirm(@PathVariable String id,@RequestBody ObjectNode body){return scripts.confirm(id,body);}
    @PostMapping("/script-versions/{id}/rollback") public ObjectNode rollback(@PathVariable String id,@RequestBody ObjectNode body){return scripts.rollback(id,body);}
    @GetMapping("/script-versions/{from}/diff/{to}") public ObjectNode diff(@PathVariable String from,@PathVariable String to){return scripts.diff(from,to);}
}
