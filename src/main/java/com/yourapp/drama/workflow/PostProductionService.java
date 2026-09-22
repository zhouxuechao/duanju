package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.production.PromptCompiler;
import com.yourapp.drama.production.ProductionService;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class PostProductionService {
    private final DocumentStore store;private final JobService jobs;private final ProductionService production;
    private final StoryDevelopmentService development;private final WorkflowService workflow; private final VoiceStateResolver voiceStates;private final TimelineQualityService timelineQuality;private final FinalCreativeQualityService creativeQuality;private final PromptCompiler prompts;
    public PostProductionService(DocumentStore store,JobService jobs,ProductionService production,StoryDevelopmentService development,WorkflowService workflow,VoiceStateResolver voiceStates,TimelineQualityService timelineQuality,FinalCreativeQualityService creativeQuality,PromptCompiler prompts){this.store=store;this.jobs=jobs;this.production=production;this.development=development;this.workflow=workflow;this.voiceStates=voiceStates;this.timelineQuality=timelineQuality;this.creativeQuality=creativeQuality;this.prompts=prompts;}

    public ObjectNode dialect(String dialogueId,ObjectNode body){
        return store.transaction(()->{
            ObjectNode line=store.getForUpdate(DIALOGUE_LINE,dialogueId),request=body.deepCopy();
            request.put("semanticText",required(line,"semanticText")).put("subtitleText",required(line,"subtitleText"));request.put("dialect",body.path("dialect").asText(line.path("dialect").asText("MANDARIN")));
            request.put("dialectStrength",body.has("dialectStrength")?body.path("dialectStrength").asDouble():line.path("dialectStrength").asDouble(1));
            ArrayNode entries=request.putArray("knowledgeBase");for(ResourceKind kind:List.of(DIALECT_DICTIONARY,DIALECT_PHRASE,DIALECT_EXAMPLE,DIALECT_CORRECTION))store.list(kind,project(line),null).forEach(entries::add);
            JsonNode result=production.renderDialect(request);
            ObjectNode next=line.deepCopy();for(String field:List.of("spokenText","dialect","dialectStrength","confidence","needsHumanCorrection","status"))if(result.has(field))next.set(field,result.path(field));
            next.set("dialectMatches",result.path("matchedEntryIds"));
            if(body.path("correction").path("approved").asBoolean(false)){
                ObjectNode correction=obj().put("projectId",project(line)).put("dialogueLineId",dialogueId).put("semanticText",required(line,"semanticText"))
                    .put("subtitleText",required(line,"subtitleText")).put("dialect",required(result,"dialect")).put("spokenText",required(result,"spokenText")).put("approved",true).put("humanConfirmed",true);
                store.create(DIALECT_CORRECTION,correction);
            }
            return store.update(DIALOGUE_LINE,dialogueId,revision(line),next);
        });
    }
    public ObjectNode tts(String dialogueId,ObjectNode body){
        ObjectNode line=store.get(DIALOGUE_LINE,dialogueId);if(text(line,"spokenText").isBlank())throw new WorkflowException("SPOKEN_TEXT_REQUIRED","请先完成方言/发音文本确认");
        ensureCurrentShot(required(line,"shotId"));
        ObjectNode profile=findVoice(line);if(profile==null)throw new WorkflowException("VOICE_PROFILE_REQUIRED","配音必须绑定已确认的角色音色或声音参考，不能随机选音色");String voiceId=profile.path("providerVoiceId").asText("");String referenceAudioUrl=profile.path("referenceAudioUrl").asText("");ObjectNode voiceState=obj();ObjectNode shot=store.get(SHOT,required(line,"shotId"));if(shot.path("storyTime").isNumber()){voiceState=voiceStates.resolve(project(line),id(profile),shot.path("storyTime").asDouble());if(voiceState.hasNonNull("providerVoiceId"))voiceId=voiceState.path("providerVoiceId").asText("");if(voiceState.hasNonNull("referenceAudioUrl"))referenceAudioUrl=voiceState.path("referenceAudioUrl").asText("");}if((voiceId.isBlank()&&referenceAudioUrl.isBlank())||(!voiceId.isBlank()&&!referenceAudioUrl.isBlank()))throw new WorkflowException("VOICE_PROFILE_INVALID","角色音色必须在 providerVoiceId 与已批准 referenceAudioUrl 中二选一");ObjectNode input=obj().put("dialogueLineId",dialogueId).put("shotId",required(line,"shotId")).put("spokenText",required(line,"spokenText"))
            .put("semanticText",required(line,"semanticText")).put("subtitleText",required(line,"subtitleText")).put("dialect",line.path("dialect").asText("MANDARIN")).put("speed",line.path("speed").asDouble(.95)).put("providerVoiceId",voiceId);
        input.put("voiceProfileId",id(profile));ObjectNode profileForRequest=profile.deepCopy();profileForRequest.put("providerVoiceId",voiceId);if(referenceAudioUrl.isBlank())profileForRequest.remove("referenceAudioUrl");else profileForRequest.put("referenceAudioUrl",referenceAudioUrl);input.set("voiceProfile",profileForRequest);input.set("voiceState",voiceState);
        ObjectNode compileRequest=obj().put("dialogueId",dialogueId).put("semanticText",required(line,"semanticText")).put("spokenText",required(line,"spokenText")).put("subtitleText",required(line,"subtitleText")).put("dialect",line.path("dialect").asText("MANDARIN")).put("speed",line.path("speed").asDouble(.95)).put("voiceProfileId",id(profile)).put("voiceReferenceMode",referenceAudioUrl.isBlank()?"PROVIDER_VOICE_ID":"REFERENCE_AUDIO");var compiled=prompts.compileVoice(compileRequest);input.put("prompt",compiled.prompt()).put("compilerVersion",compiled.compilerVersion());input.set("promptIR",compiled.promptIRJson().deepCopy());
        input.set("options",body.path("providerOptions").isObject()?body.path("providerOptions"):obj());
        return jobs.enqueue(project(line),required(line,"shotId"),"TTS",input,requestKey(body));
    }
    public ObjectNode lipsync(String takeId,ObjectNode body){
        ObjectNode take=store.get(VIDEO_TAKE,takeId);if(!take.path("locked").asBoolean()||!"PASSED".equals(text(take,"qcStatus")))throw new WorkflowException("TAKE_NOT_APPROVED","口型同步只能使用已质检并采用的视频");
        ensureCurrentShot(required(take,"shotId"));
        String videoUrl=required(take,"videoUrl");List<ObjectNode> clips=new ArrayList<>();
        if(body.hasNonNull("audioClipId"))clips.add(store.get(AUDIO_CLIP,body.path("audioClipId").asText()));else for(ObjectNode clip:store.list(AUDIO_CLIP,project(take),required(take,"shotId")))if(clip.path("locked").asBoolean()&&clip.path("selected").asBoolean())clips.add(clip);
        if(clips.isEmpty())throw new WorkflowException("AUDIO_REQUIRED","口型同步需要已采用的对白音频");
        ObjectNode compileRequest=obj().put("shotId",required(take,"shotId")).put("sourceTakeId",takeId).put("videoUrl",videoUrl);ArrayNode refs=compileRequest.putArray("references");refs.add(obj().put("type","video_url").put("url",videoUrl).put("role","reference_video"));
        for(ObjectNode clip:clips){String audioUrl=text(clip,"providerUrl");if(audioUrl.isBlank())throw new WorkflowException("PROVIDER_AUDIO_URL_REQUIRED","当前音频只有归档副本，服务商无法访问；请配置能返回临时 URL 的配音或对象存储");refs.add(obj().put("type","audio_url").put("url",audioUrl).put("role","reference_audio"));}
        compileRequest.set("audioClipIds",mapperArray(clips));var compiled=prompts.compileLipSync(compileRequest);
        ObjectNode promptData=obj().put("projectId",project(take)).put("shotId",required(take,"shotId")).put("purpose","LIPSYNC").put("version",store.list(PROMPT_VERSION,project(take),null).size()+1).put("prompt",compiled.prompt()).put("compilerVersion",compiled.compilerVersion());promptData.set("promptIR",compiled.promptIRJson().deepCopy());
        ObjectNode promptVersion=store.create(PROMPT_VERSION,promptData);
        ObjectNode input=obj().put("shotId",required(take,"shotId")).put("sourceTakeId",takeId).put("sourceKeyframeId",required(take,"sourceKeyframeId")).put("promptVersionId",id(promptVersion)).put("prompt",compiled.prompt()).put("compilerVersion",compiled.compilerVersion()).put("takeNo",store.list(VIDEO_TAKE,project(take),required(take,"shotId")).size()+1);input.set("references",refs.deepCopy());input.set("promptIR",compiled.promptIRJson().deepCopy());
        input.set("audioClipIds",compileRequest.path("audioClipIds").deepCopy());input.set("providerOptions",body.path("providerOptions").isObject()?body.path("providerOptions"):obj());
        return jobs.enqueue(project(take),required(take,"shotId"),"LIPSYNC",input,requestKey(body));
    }
    public JsonNode soundDesign(String episodeId,ObjectNode body){
        ObjectNode episode=store.get(EPISODE,episodeId),request=obj().put("episodeId",episodeId).put("mood",body.path("mood").asText("克制、服务叙事的器乐底乐"));
        ArrayNode scenes=request.putArray("scenes");
        for(ObjectNode scene:store.list(SCENE,project(episode),episodeId)){ObjectNode copy=scene.deepCopy();ArrayNode shots=copy.putArray("shots");store.list(SHOT,project(episode),id(scene)).forEach(shots::add);scenes.add(copy);}
        return production.soundDesign(request);
    }
    public ObjectNode timeline(String episodeId,ObjectNode body){ObjectNode episode=store.get(EPISODE,episodeId);JsonNode snapshot=development.approvedSnapshot(episode);ObjectNode input=obj().put("episodeId",episodeId);input.set("continuitySnapshot",snapshot);input.set("episode",episode);input.put("subtitleMode",body.path("subtitleMode").asText("SIDECAR"));if(body.path("soundDesign").isObject())input.set("soundDesign",body.path("soundDesign").deepCopy());if(body.path("soundItems").isArray())input.set("soundItems",body.path("soundItems").deepCopy());return jobs.enqueue(project(episode),null,"TIMELINE",input,requestKey(body));}
    public ObjectNode quality(String timelineId){return timelineQuality.review(timelineId);}
    public ObjectNode finalReview(String timelineId,ObjectNode body){return store.transaction(()->{ObjectNode timeline=store.getForUpdate(TIMELINE,timelineId);if(text(timeline,"finalUrl").isBlank()||!timeline.path("finalTechnicalQa").path("passed").asBoolean())throw new WorkflowException("FINAL_TECHNICAL_QA_REQUIRED","请先完成通过技术质检的最终渲染");String decision=body.path("decision").asText().toUpperCase(Locale.ROOT);if(!Set.of("PASS","REGENERATE","MANUAL_FIX").contains(decision))throw new IllegalArgumentException("终片创作验收只支持 PASS、REGENERATE 或 MANUAL_FIX");for(String field:FinalCreativeQualityService.METRICS){int score=body.path(field).asInt(0);if("PASS".equals(decision)&&score==0)throw new IllegalArgumentException(field+" 通过时必须评为 1～5 分");}ObjectNode review=creativeQuality.evaluate(body);review.put("decision",decision).put("reviewer",body.path("reviewer").asText("LOCAL_OPERATOR")).put("notes",body.path("notes").asText("")).put("reviewedAt",java.time.Instant.now().toString());boolean override=body.path("humanOverride").asBoolean()&&!text(body,"overrideBy").isBlank()&&!text(body,"overrideReason").isBlank();if(override)review.set("humanOverride",obj().put("overrideBy",text(body,"overrideBy")).put("overrideReason",text(body,"overrideReason")).put("overrideAt",java.time.Instant.now().toString()));boolean passed="PASS".equals(decision)&&(review.path("passed").asBoolean()||override);ObjectNode next=timeline.deepCopy().put("finalQaStatus",passed?"PASSED":"PASS".equals(decision)?"CREATIVE_REVIEW_REQUIRED":decision);next.set("finalCreativeQa",review);return store.update(TIMELINE,timelineId,revision(timeline),next);});}
    public ObjectNode render(String timelineId,ObjectNode body){ObjectNode timeline=store.get(TIMELINE,timelineId);development.approvedSnapshot(store.get(EPISODE,required(timeline,"episodeId")));if(timeline.path("stale").asBoolean())throw new WorkflowException("STALE_TIMELINE","时间线依赖的剧本版本已经修改");checkTimelineAssetVersions(timeline);String quality=body.path("quality").asText("PREVIEW").toUpperCase(Locale.ROOT);if(!Set.of("PREVIEW","FINAL").contains(quality))throw new IllegalArgumentException("quality 应为 PREVIEW 或 FINAL");if("FINAL".equals(quality)&&!timeline.path("locked").asBoolean())throw new WorkflowException("TIMELINE_NOT_LOCKED","终版渲染前请先质检并锁定时间线");ObjectNode input=obj().put("timelineId",timelineId).put("timelineRevision",timeline.path("contentRevision").asLong(1)).put("quality",quality).put("subtitleMode",body.path("subtitleMode").asText(timeline.path("subtitleMode").asText("SIDECAR")));return jobs.enqueue(project(timeline),null,"RENDER",input,requestKey(body));}
    private void checkTimelineAssetVersions(ObjectNode timeline){for(ObjectNode item:store.list(TIMELINE_ITEM,project(timeline),id(timeline)))if("VIDEO".equals(text(item,"track"))){JsonNode refs=item.path("assetViewIds");if(!refs.isArray()||refs.isEmpty())throw new WorkflowException("ASSET_REFERENCES_REQUIRED","时间线缺少镜头视图版本快照");workflow.checkReferenceSnapshot(obj().set("assetViewIds",refs.deepCopy()));ObjectNode take=store.get(VIDEO_TAKE,required(item,"videoTakeId"));if(!take.path("locked").asBoolean()||!take.path("selected").asBoolean()||!"PASSED".equals(text(take,"qcStatus")))throw new WorkflowException("TAKE_NOT_APPROVED","时间线引用的视频 Take 已取消采用或未通过质检");ObjectNode keyframe=store.get(KEYFRAME,required(take,"sourceKeyframeId"));if(!keyframe.path("locked").asBoolean()||!keyframe.path("selected").asBoolean()||!"PASSED".equals(text(keyframe,"qcStatus")))throw new WorkflowException("KEYFRAME_NOT_APPROVED","时间线引用的关键帧已取消采用或未通过质检");}}
    private void ensureCurrentShot(String shotId){ObjectNode shot=store.get(SHOT,shotId);ObjectNode scene=store.get(SCENE,required(shot,"sceneId"));development.approvedSnapshot(store.get(EPISODE,required(scene,"episodeId")));}
    private ObjectNode findVoice(ObjectNode line){if(!line.hasNonNull("voiceProfileId"))return null;ObjectNode profile=store.get(VOICE_PROFILE,text(line,"voiceProfileId"));if(!project(line).equals(project(profile)))throw new WorkflowException("VOICE_PROFILE_PROJECT_MISMATCH","角色音色不属于当前项目");String characterId=text(line,"characterId"),profileCharacter=text(profile,"characterId");if(!characterId.isBlank()&&!characterId.equals(profileCharacter))throw new WorkflowException("VOICE_PROFILE_CHARACTER_MISMATCH","角色音色与对白人物不一致");if(!profile.path("approved").asBoolean(false))throw new WorkflowException("VOICE_PROFILE_NOT_APPROVED","请先确认角色音色或声音参考");return profile;}
    private ArrayNode mapperArray(List<ObjectNode> values){ArrayNode out=JsonNodeFactory.instance.arrayNode();values.forEach(v->out.add(id(v)));return out;}
    private String requestKey(JsonNode body){return body.path("requestKey").asText(UUID.randomUUID().toString());}
}
