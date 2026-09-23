package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.NovelAnalysisService;
import com.yourapp.drama.workflow.NovelIndexService;
import com.yourapp.drama.workflow.NovelEntityResolutionService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/novels/{novelId}")
public class NovelAnalysisController {
    private final NovelAnalysisService analysis;private final NovelIndexService index;private final NovelEntityResolutionService entities;private final com.yourapp.drama.workflow.NovelIntelligenceRunService runs;
    public NovelAnalysisController(NovelAnalysisService analysis,NovelIndexService index,NovelEntityResolutionService entities,com.yourapp.drama.workflow.NovelIntelligenceRunService runs){this.analysis=analysis;this.index=index;this.entities=entities;this.runs=runs;}
    @GetMapping("/analysis/estimate") public ObjectNode estimate(@PathVariable String novelId,@RequestParam(defaultValue="STANDARD")String profile){return analysis.estimate(novelId,profile);}
    @PostMapping("/analysis") public ObjectNode start(@PathVariable String novelId,@RequestBody ObjectNode body){return analysis.start(novelId,body);}
    @PostMapping("/analysis/resume") public ObjectNode resume(@PathVariable String novelId,@RequestBody ObjectNode body){return analysis.resume(novelId,body);}
    @GetMapping("/analysis/progress") public ObjectNode progress(@PathVariable String novelId){return analysis.progress(novelId);}
    @GetMapping("/intelligence-runs") public List<ObjectNode> runs(@PathVariable String novelId){return runs.list(novelId);}
    @GetMapping("/search") public List<ObjectNode> search(@PathVariable String novelId,@RequestParam String q,@RequestParam(defaultValue="20")int limit){return index.keyword(novelId,q,Math.min(100,Math.max(1,limit)));}
    @PostMapping("/entity-candidates/{candidateId}/confirm") public ObjectNode confirm(@PathVariable String candidateId){return entities.confirm(candidateId);}
}
