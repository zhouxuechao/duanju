package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.NovelAdaptationContextAssembler;
import com.yourapp.drama.workflow.NovelAdaptationService;
import com.yourapp.drama.workflow.EpisodeAdaptationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController @RequestMapping("/api")
public class NovelAdaptationController {
    private final NovelAdaptationService adaptations;private final NovelAdaptationContextAssembler contexts;private final EpisodeAdaptationService episodes;
    public NovelAdaptationController(NovelAdaptationService adaptations,NovelAdaptationContextAssembler contexts,EpisodeAdaptationService episodes){this.adaptations=adaptations;this.contexts=contexts;this.episodes=episodes;}
    @PostMapping("/novels/{novelId}/adaptation-plans") public ObjectNode create(@PathVariable String novelId,@RequestBody ObjectNode body){return adaptations.create(novelId,body);}
    @GetMapping("/novels/{novelId}/adaptation-plans") public List<ObjectNode> plans(@PathVariable String novelId){return adaptations.plans(novelId);}
    @GetMapping("/adaptation-plans/{planId}/episodes") public List<ObjectNode> episodes(@PathVariable String planId){return adaptations.episodes(planId);}
    @PatchMapping("/adaptation-plans/{planId}/episodes/{episodeNo}") public ObjectNode edit(@PathVariable String planId,@PathVariable int episodeNo,@RequestBody ObjectNode body){return adaptations.editEpisode(planId,episodeNo,body);}
    @PostMapping("/adaptation-plans/{planId}/confirm") public ObjectNode confirm(@PathVariable String planId,@RequestBody ObjectNode body){return adaptations.confirm(planId,body);}
    @GetMapping("/adaptation-plans/{planId}/episodes/{episodeNo}/context") public ObjectNode context(@PathVariable String planId,@PathVariable int episodeNo,@RequestParam(defaultValue="12000")int budgetChars){return contexts.assemble(planId,episodeNo,budgetChars);}
    @PostMapping("/adaptation-plans/{planId}/episodes/{episodeNo}/script") public ObjectNode script(@PathVariable String planId,@PathVariable int episodeNo,@RequestBody ObjectNode body){return episodes.generate(planId,episodeNo,body);}
}
