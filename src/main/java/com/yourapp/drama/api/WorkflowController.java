package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.job.*;
import com.yourapp.drama.persistence.ResourceKind;
import com.yourapp.drama.production.ProductionService;
import com.yourapp.drama.production.DirectorContract;
import com.yourapp.drama.workflow.WorkflowService;
import com.yourapp.drama.workflow.PostProductionService;
import com.yourapp.drama.workflow.QualityMetricsService;
import com.yourapp.drama.workflow.AutomaticVisualReviewService;
import com.yourapp.drama.workflow.AutomaticVideoReviewService;
import com.yourapp.drama.workflow.VisualCalibrationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkflowController {
    private final WorkflowService workflow;private final PostProductionService post;private final JobService jobs;private final JobEvents events;private final ProductionService production;private final String mode;private final DirectorContract directorContract;private final QualityMetricsService qualityMetrics;private final AutomaticVisualReviewService automaticVisualReview;private final AutomaticVideoReviewService automaticVideoReview;private final VisualCalibrationService calibration;
    public WorkflowController(WorkflowService workflow,PostProductionService post,JobService jobs,JobEvents events,ProductionService production,DirectorContract directorContract,QualityMetricsService qualityMetrics,AutomaticVisualReviewService automaticVisualReview,AutomaticVideoReviewService automaticVideoReview,VisualCalibrationService calibration,@Value("${drama.provider.mode:mock}")String mode){this.workflow=workflow;this.post=post;this.jobs=jobs;this.events=events;this.production=production;this.directorContract=directorContract;this.qualityMetrics=qualityMetrics;this.automaticVisualReview=automaticVisualReview;this.automaticVideoReview=automaticVideoReview;this.calibration=calibration;this.mode=mode;}
    private ObjectNode body(ObjectNode body){return body==null?JsonNodeFactory.instance.objectNode():body;}
    @GetMapping("/system") public Map<String,Object> system(){return Map.of("name","拾光 · AI 短剧工作室","version","0.1.0","providerMode",mode,"simulated",mode.equals("mock"),"requiresKeyframeQc",true,"providerUrlHandoff","DIRECT_ONLY");}
    @GetMapping("/projects/{id}/quality-metrics") public ObjectNode qualityMetrics(@PathVariable String id){return qualityMetrics.metrics(id);}
    @GetMapping("/projects/{id}/vlm-calibration") public ObjectNode vlmCalibration(@PathVariable String id){return calibration.metrics(id);}
    @PostMapping("/projects/{id}/story")public ObjectNode story(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.story(id,body(b));}
    @PostMapping("/scenes/{id}/plan")public ObjectNode plan(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.plan(id,body(b));}
    @PostMapping("/shots/{id}/storyboard")public ObjectNode storyboard(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.image(id,"STORYBOARD",body(b));}
    @PostMapping("/shots/{id}/keyframe")public ObjectNode keyframe(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.image(id,"KEYFRAME",body(b));}
    @PostMapping("/keyframes/{id}/regenerate")public ObjectNode regenerate(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.regenerateKeyframe(id,body(b));}
    @PostMapping("/keyframes/{id}/replay-original")public ObjectNode replayOriginal(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.regenerateKeyframe(id,body(b));}
    @PostMapping("/keyframes/{id}/regenerate-latest")public ObjectNode regenerateLatest(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.regenerateLatestKeyframe(id,body(b));}
    @PostMapping("/keyframes/{id}/edit-generate")public ObjectNode editGenerate(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.editGenerateKeyframe(id,body(b));}
    @PostMapping("/keyframes/{id}/repair")public ObjectNode repair(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.repairKeyframe(id,body(b));}
    @PostMapping("/keyframes/{id}/automatic-review")public ObjectNode automaticReview(@PathVariable String id,@RequestBody ObjectNode b){return automaticVisualReview.review(id,b);}
    @PostMapping("/video-takes/{id}/automatic-review")public ObjectNode automaticVideoReview(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return automaticVideoReview.review(id,body(b));}
    @PostMapping("/keyframes/{id}/video")public ObjectNode video(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.video(id,body(b));}
    @PostMapping("/{kind}/{id}/review")public ObjectNode review(@PathVariable String kind,@PathVariable String id,@RequestBody ObjectNode b){return workflow.review(ResourceKind.fromPath(kind),id,b);}
    @PostMapping("/{kind}/{id}/lock")public ObjectNode lock(@PathVariable String kind,@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.lock(ResourceKind.fromPath(kind),id,body(b));}
    @PostMapping("/{kind}/{id}/qc")public ObjectNode qc(@PathVariable String kind,@PathVariable String id,@RequestBody(required=false)ObjectNode b){return workflow.qc(kind,id,body(b));}
    @PostMapping("/shots/{id}/revise")public ObjectNode revise(@PathVariable String id){return workflow.reviseShot(id);}
    @PostMapping("/dialogue-lines/{id}/dialect")public ObjectNode dialect(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return post.dialect(id,body(b));}
    @PostMapping("/dialogue-lines/{id}/tts")public ObjectNode tts(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return post.tts(id,body(b));}
    @PostMapping("/video-takes/{id}/lipsync")public ObjectNode lipsync(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return post.lipsync(id,body(b));}
    @PostMapping("/episodes/{id}/sound-design")public JsonNode soundDesign(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return post.soundDesign(id,body(b));}
    @PostMapping("/episodes/{id}/timeline")public ObjectNode timeline(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return post.timeline(id,body(b));}
    @PostMapping("/timelines/{id}/render")public ObjectNode render(@PathVariable String id,@RequestBody(required=false)ObjectNode b){return post.render(id,body(b));}
    @PostMapping("/jobs/{id}/retry")public ObjectNode retry(@PathVariable String id){return jobs.retry(id);}
    @PostMapping("/jobs/{id}/revalidate")public ObjectNode revalidate(@PathVariable String id){return jobs.revalidate(id);}
    @PostMapping("/jobs/{id}/cancel")public ObjectNode cancel(@PathVariable String id){return jobs.cancel(id);}
    @GetMapping(value="/events",produces="text/event-stream")public SseEmitter events(@RequestParam(required=false)String projectId){return events.subscribe(projectId);}
    @PostMapping("/production/{operation}")public JsonNode production(@PathVariable String operation,@RequestBody JsonNode body){return switch(operation){
        case "continuity"->production.planContinuity(body);case "image-prompt"->production.compileImage(body);case "video-prompt"->production.compileVideo(body);
        case "video-strategy"->production.routeVideo(body);case "shot-transition"->production.transitionShot(body);case "job-transition"->production.transitionJob(body);
        case "dialect"->production.renderDialect(body);case "subtitle"->production.subtitle(body);case "sound-design"->production.soundDesign(body);case "timeline"->production.planTimeline(body);default->throw new IllegalArgumentException("未知的生产操作");};}
    @GetMapping("/schemas/agents/{agent}")public JsonNode schema(@PathVariable String agent){return production.agentSchema(agent);}
    @PostMapping("/schemas/agents/director")public JsonNode directorSchema(@RequestBody JsonNode context){return directorContract.schema(context);}
    @GetMapping("/skills")public JsonNode skills(){return production.skillCatalog();}
}
