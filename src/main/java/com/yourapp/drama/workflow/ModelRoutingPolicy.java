package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import com.yourapp.drama.production.ProviderCapabilityRegistry;

@Service
public class ModelRoutingPolicy {
    private final Environment environment;
    public ModelRoutingPolicy(Environment environment){this.environment=environment;}
    public ObjectNode decide(String taskType,String complexity,String qualityTier){String task=taskType.toUpperCase(Locale.ROOT),role,property;switch(task){case "STORY","SCRIPT","STORY_QA"->{role="WRITER";property="drama.provider.volcengine.text-model";}case "DIRECTOR_PLAN","SHOT_DETAIL"->{role="DIRECTOR";property="drama.provider.volcengine.director-model";}case "KEYFRAME_QC","VIDEO_QC"->{role="VISION_REVIEW";property="drama.provider.volcengine.vlm-model";}case "STORYBOARD","KEYFRAME","ASSET_IMAGE"->{role="IMAGE";property="drama.provider.volcengine.image-model";}case "VIDEO","LIPSYNC"->{role="VIDEO";property="drama.provider.volcengine.video-model";}case "TTS"->{role="VOICE";property="drama.provider.seed-audio.model";}default->{role="LOCAL";property="";}}
        String mode=environment.getProperty("drama.provider.mode","mock"),provider="mock".equalsIgnoreCase(mode)?"MOCK":"VOLCENGINE";String configured=property.isBlank()?"local":environment.getProperty(property,defaultModel(role));String model="mock".equalsIgnoreCase(mode)?"mock-"+role.toLowerCase(Locale.ROOT):configured;String fallbackText=environment.getProperty("drama.routing."+task.toLowerCase(Locale.ROOT).replace('_','-')+".fallbacks","");ArrayNode fallback=JsonNodeFactory.instance.arrayNode();if(!fallbackText.isBlank())Arrays.stream(fallbackText.split(",")).map(String::trim).filter(value->!value.isBlank()).forEach(fallback::add);double ceiling=environment.getProperty("drama.routing."+task.toLowerCase(Locale.ROOT).replace('_','-')+".cost-ceiling",Double.class,0d);String reason="按任务角色 "+role+"、质量档和适配器能力选择";ObjectNode result=JsonNodeFactory.instance.objectNode().put("taskType",task).put("role",role).put("provider",provider).put("chosenProvider",provider).put("plannedModel",model).put("chosenModel",model).put("reason",reason).put("complexity",complexity==null?"MEDIUM":complexity.toUpperCase(Locale.ROOT)).put("qualityTier",qualityTier==null?"BALANCED":qualityTier.toUpperCase(Locale.ROOT)).put("costCeiling",ceiling).put("maxEstimatedCost",ceiling).put("decidedAt",Instant.now().toString()).put("fallbackRequiresAudit",!fallback.isEmpty());result.set("fallbackChain",fallback);return result;}
    private String defaultModel(String role){return switch(role){case "IMAGE"->ProviderCapabilityRegistry.SEEDREAM_50;case "VIDEO"->ProviderCapabilityRegistry.SEEDANCE_20_FAST;default->"";};}
}
