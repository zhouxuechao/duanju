package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.workflow.WorkflowException;
import org.springframework.stereotype.Service;

import java.util.Set;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Last cost-free validation boundary before media provider dispatch. */
@Service
public final class PaidProviderPreflight {
    private static final Set<String> MEDIA_TASKS=Set.of("KEYFRAME","STORYBOARD","VIDEO","ASSET_IMAGE","TTS","LIPSYNC");
    private final DocumentStore store;
    private final RuleEngine rules;
    public PaidProviderPreflight(DocumentStore store,RuleEngine rules){this.store=store;this.rules=rules;}

    public void requirePass(ObjectNode job){
        String type=text(job,"type");if(!MEDIA_TASKS.contains(type))return;
        JsonNode input=job.path("inputSnapshot");
        if(text(input,"prompt").isBlank())throw failure("PROVIDER_PROMPT_REQUIRED","付费请求缺少已编译提示词");
        if(Set.of("KEYFRAME","STORYBOARD","VIDEO").contains(type)){
            if(!input.path("context").isObject())throw failure("GENERATION_CONTEXT_REQUIRED","画面生成缺少可审计的生成上下文");
            if("VIDEO".equals(type)&&(!input.path("preflight").path("passed").asBoolean()||!input.path("capabilityFingerprint").isTextual()))throw failure("VIDEO_PREFLIGHT_REQUIRED","视频请求缺少通过的模型能力与素材预检");
            try{rules.requireDeterministicPass(input.path("context"));}catch(DeterministicRuleViolationException error){throw failure("DETERMINISTIC_RULE_FAILED",error.getMessage());}
        }else if("ASSET_IMAGE".equals(type))asset(input,job);
        else if("TTS".equals(type))voice(input);
        else lipsync(input);
    }

    private void asset(JsonNode input,JsonNode job){
        ObjectNode view=store.get(ASSET_VIEW,required(input,"assetViewId"));
        if(view.path("stale").asBoolean()||!id(job).equals(text(view,"generationJobId"))||!text(view,"sourceHash").equals(text(input,"sourceHash")))throw failure("STALE_ASSET_JOB","素材参考图任务已失效");
        for(JsonNode referenceId:input.path("referenceViewIds")){ObjectNode reference=store.get(ASSET_VIEW,referenceId.asText());if(reference.path("stale").asBoolean()||!reference.path("approved").asBoolean())throw failure("ASSET_REFERENCE_NOT_APPROVED","素材参考图引用未批准或已失效");}
    }
    private void voice(JsonNode input){
        ObjectNode line=store.get(DIALOGUE_LINE,required(input,"dialogueLineId"));
        if(!required(line,"spokenText").equals(required(input,"spokenText")))throw failure("STALE_DIALOGUE_JOB","对白发音文本已变化，请重新创建配音任务");
        if(!input.path("voiceProfile").isObject()||!input.path("voiceState").isObject())throw failure("VOICE_SNAPSHOT_REQUIRED","配音任务缺少冻结的音色与故事时间状态快照");
        String voice=text(input,"providerVoiceId"),reference=text(input,"referenceAudioUrl");
        if(voice.isBlank()==reference.isBlank())throw failure("VOICE_PROFILE_INVALID","配音必须且只能使用固定音色 ID 或已批准声音参考之一");
    }
    private void lipsync(JsonNode input){
        ObjectNode take=store.get(VIDEO_TAKE,required(input,"sourceTakeId"));
        if(!take.path("locked").asBoolean()||!take.path("selected").asBoolean()||!"PASSED".equals(text(take,"qcStatus")))throw failure("TAKE_NOT_APPROVED","口型同步源视频未通过质检并锁定");
        if(!input.path("audioClipIds").isArray()||input.path("audioClipIds").isEmpty())throw failure("AUDIO_REQUIRED","口型同步缺少已采用音频");
        for(JsonNode clipId:input.path("audioClipIds")){ObjectNode clip=store.get(AUDIO_CLIP,clipId.asText());if(!clip.path("locked").asBoolean()||!clip.path("selected").asBoolean()||text(clip,"providerUrl").isBlank())throw failure("AUDIO_NOT_APPROVED","口型同步音频未采用、未锁定或没有服务商可访问地址");}
    }
    private WorkflowException failure(String code,String message){return new WorkflowException(code,message);}
}
