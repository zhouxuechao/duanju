package com.yourapp.drama.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import com.yourapp.drama.provider.StructuredJson;
import jakarta.validation.Validator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Durable semantic episode QA. It never re-submits the already successful script request. */
@Service
public class StoryQualityService {
    private final DocumentStore store; private final JobService jobs; private final LlmGateway llm;
    private final ObjectMapper mapper; private final StructuredJson structured;
    private final EpisodeFormatResolver formats; private final ScreenwritingRuleResolver rules;
    private final StoryQualityPolicy policy;
    public StoryQualityService(DocumentStore store,JobService jobs,LlmGateway llm,ObjectMapper mapper,Validator validator,
                               EpisodeFormatResolver formats,ScreenwritingRuleResolver rules,StoryQualityPolicy policy){this.store=store;this.jobs=jobs;this.llm=llm;this.mapper=mapper;this.structured=new StructuredJson(mapper,validator);this.formats=formats;this.rules=rules;this.policy=policy;}

    public ObjectNode schedule(ObjectNode document){
        String contentHash=contentHash(document.path("content"));
        if(contentHash.equals(Documents.text(document,"qaContentHash"))&&document.path("storyQa").isObject())return document;
        ObjectNode input=input(document);input.put("contentHash",contentHash);
        ObjectNode job=jobs.enqueue(Documents.project(document),null,"STORY_QA",input,"story-qa:"+Documents.id(document)+":"+Documents.revision(document)+":"+contentHash);
        jobs.mutate(Documents.id(job),j->j.put("maxAttempts",1));
        return store.transaction(()->{ObjectNode current=store.getForUpdate(ResourceKind.STORY_DOCUMENT,Documents.id(document));
            if(!contentHash.equals(contentHash(current.path("content")))){jobs.cancel(Documents.id(job));return current;}
            return store.update(ResourceKind.STORY_DOCUMENT,Documents.id(current),Documents.revision(current),current.deepCopy().put("qaJobId",Documents.id(job)).put("reviewStatus","QA_PENDING"));});
    }

    public void process(ObjectNode job){
        JsonNode input=job.path("inputSnapshot");String documentId=Documents.required(input,"documentId");
        ObjectNode document=store.get(ResourceKind.STORY_DOCUMENT,documentId);
        if(!Documents.id(job).equals(Documents.text(document,"qaJobId"))||!Documents.text(input,"contentHash").equals(contentHash(document.path("content")))){jobs.mutate(Documents.id(job),j->j.put("status","CANCELLED"));return;}
        LlmGateway.StructuredRequest request=new LlmGateway.StructuredRequest(prompt(),input.toString(),mapper.convertValue(StoryQualitySchemas.schema(),new TypeReference<Map<String,Object>>(){}),Map.of("modelRole","story_qa"));
        LlmGateway.StructuredResult<JsonNode> result=llm.generate(request,JsonNode.class);
        JsonNode parsed;
        try{parsed=structured.parse(result.value().toString(),StoryQualitySchemas.schema(),JsonNode.class,result.requestId());}
        catch(ProviderException error){throw error;}
        ObjectNode qa=policy.apply((ObjectNode)parsed.deepCopy(),input);boolean passed=qa.path("passed").asBoolean();qa.put("evaluatedAt",java.time.Instant.now().toString());
        store.transaction(()->{ObjectNode latest=store.getForUpdate(ResourceKind.STORY_DOCUMENT,documentId);if(!Documents.id(job).equals(Documents.text(latest,"qaJobId")))return null;
            ObjectNode next=latest.deepCopy().put("reviewStatus","REVIEW").put("qaProviderRequestId",result.requestId()).put("qaModel",result.model()).put("qaContentHash",Documents.text(input,"contentHash"));next.set("storyQa",qa);store.update(ResourceKind.STORY_DOCUMENT,documentId,Documents.revision(latest),next);
            jobs.mutate(Documents.id(job),j->j.put("providerRequestId",result.requestId()).put("model",result.model()).put("simulated",result.simulated()));jobs.succeed(Documents.id(job),Documents.obj().put("documentId",documentId).put("passed",passed));return null;});
    }

    public void syncFailure(ObjectNode job){if(!"STORY_QA".equals(Documents.text(job,"type"))||!java.util.Set.of("FAILED","RETRY_WAIT").contains(Documents.text(job,"status")))return;String documentId=Documents.text(job.path("inputSnapshot"),"documentId");if(documentId.isBlank())return;
        store.transaction(()->{ObjectNode doc=store.getForUpdate(ResourceKind.STORY_DOCUMENT,documentId);if(!Documents.id(job).equals(Documents.text(doc,"qaJobId")))return null;ObjectNode next=doc.deepCopy().put("reviewStatus","QA_FAILED").put("qaFailureCode",Documents.text(job,"failureCode")).put("qaFailureReason",Documents.text(job,"failureReason"));store.update(ResourceKind.STORY_DOCUMENT,documentId,Documents.revision(doc),next);return null;});}

    public ObjectNode markSchedulingFailure(ObjectNode document,RuntimeException error){
        String message=error.getMessage()==null||error.getMessage().isBlank()?error.getClass().getSimpleName():error.getMessage();
        return store.transaction(()->{ObjectNode current=store.getForUpdate(ResourceKind.STORY_DOCUMENT,Documents.id(document));ObjectNode next=current.deepCopy().put("reviewStatus","QA_FAILED").put("qaFailureCode","STORY_QA_SCHEDULE_FAILED").put("qaFailureReason",message);return store.update(ResourceKind.STORY_DOCUMENT,Documents.id(current),Documents.revision(current),next);});
    }

    private ObjectNode input(ObjectNode document){ObjectNode input=Documents.obj().put("pipelineVersion",2).put("phase","STORY_QA").put("documentId",Documents.id(document)).put("episodeNo",document.path("episodeNo").asInt());
        ObjectNode core=store.get(ResourceKind.STORY_DOCUMENT,Documents.required(document,"coreId"));JsonNode project=document.path("projectSnapshot");JsonNode format=formats.resolve(project);
        input.set("episodeFormat",format);input.set("storyProfile",project.path("storyProfile").deepCopy());input.set("rulePack",rules.resolve("STORY_QA",project.path("storyProfile"),format,project.path("distributionProfile").asText("GENERAL")));
        input.set("episodeOutline",document.path("sourceSnapshot").path("episodeOutline").deepCopy());input.set("episodeScript",document.path("content").deepCopy());
        ObjectNode contract=Documents.obj();for(String field:List.of("emotionContract","storyEngine","worldRules","seasonArc","unitArcs","characters","foreshadowingRules","continuityRules"))contract.set(field,core.path("content").path(field).deepCopy());input.set("showrunnerContract",contract);
        ArrayNode recent=input.putArray("recentStructuralSummaries");store.list(ResourceKind.STORY_DOCUMENT,Documents.project(document),null).stream().filter(d->"EPISODE_SCRIPT".equals(Documents.text(d,"documentType"))&&d.path("episodeNo").asInt()<document.path("episodeNo").asInt()&&!d.path("stale").asBoolean()).sorted(Comparator.comparingInt((ObjectNode d)->d.path("episodeNo").asInt()).reversed()).limit(5).sorted(Comparator.comparingInt(d->d.path("episodeNo").asInt())).forEach(d->{ObjectNode summary=Documents.obj().put("episodeNo",d.path("episodeNo").asInt());JsonNode outline=d.path("sourceSnapshot").path("episodeOutline");for(String field:List.of("episodeFunction","hook","mainConflict","payoff","cliffhanger"))summary.set(field,outline.path(field).deepCopy());if(d.path("storyQa").has("episodeDelta"))summary.set("episodeDelta",d.path("storyQa").path("episodeDelta").deepCopy());recent.add(summary);});return input;}

    private String prompt(){try(InputStream stream=new ClassPathResource("development-skills/05-story-quality/prompt.md").getInputStream()){return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("故事质检技能加载失败",e);}}
    public String contentHash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
