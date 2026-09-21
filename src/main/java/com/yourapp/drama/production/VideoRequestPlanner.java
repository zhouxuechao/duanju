package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.VideoGenerator;
import org.springframework.stereotype.Component;

import java.util.*;

/** Single pre-provider planning boundary for activation, routing, validation, and audit snapshots. */
@Component
public class VideoRequestPlanner {
    private final ObjectMapper mapper;private final ProviderCapabilityRegistry capabilities;private final MaterialActivationPlan activation;
    private final ReferenceBudgeter budgeter;private final MaterialPreflight preflight;private final ReferenceConflictValidator conflicts;
    private final ReferenceIndexValidator indexes;private final VideoRequestContractValidator contracts;private final VideoRequestRouteResolver routes;
    private final KeyframeTimelineValidator timeline;private final ProviderRulePackResolver rules;
    public VideoRequestPlanner(ObjectMapper mapper,ProviderCapabilityRegistry capabilities,MaterialActivationPlan activation,ReferenceBudgeter budgeter,
        MaterialPreflight preflight,ReferenceConflictValidator conflicts,ReferenceIndexValidator indexes,VideoRequestContractValidator contracts,
        VideoRequestRouteResolver routes,KeyframeTimelineValidator timeline,ProviderRulePackResolver rules){this.mapper=mapper;this.capabilities=capabilities;this.activation=activation;this.budgeter=budgeter;this.preflight=preflight;this.conflicts=conflicts;this.indexes=indexes;this.contracts=contracts;this.routes=routes;this.timeline=timeline;this.rules=rules;}

    public ObjectNode plan(JsonNode prepared,JsonNode context,JsonNode keyframe,JsonNode previous,JsonNode body){
        String modelId=context.path("providerCapabilities").path("modelId").asText("");VideoModelProfile profile=capabilities.profile(modelId);
        VideoTaskType task=task(body,context,previous);ProviderTaskLockMode lock=lock(body,task);ArrayNode keyframes=keyframes(body,keyframe,context.path("shot").path("duration").asDouble(5));
        timeline.validate(keyframes,context.path("shot").path("duration").asDouble(5),profile);
        ArrayNode candidates=normalize(prepared.path("references"),context.path("shot").path("shotId").asText());MaterialActivationPlan.Result active=activation.plan(candidates,context.path("shot"),task);
        ArrayNode activeArray=array(active.activated());conflicts.validate(activeArray);
        ReferenceBudgeter.Result budget=task.lockMode()==ProviderTaskLockMode.UNLOCKED?budgeter.fit(activeArray,profile.recommendedLimits()):new ReferenceBudgeter.Result(active.activated(),List.of());
        ArrayNode selected=array(budget.selected());VideoRequestRouteResolver.Resolved route=routes.resolve(task,lock,profile,keyframes,previous,selected);
        ArrayNode actual=requestReferences(route,context.path("shot").path("shotId").asText(),selected,keyframes);MaterialPreflight.Result check=preflight.validate(actual,profile);
        if(!check.passed())throw new IllegalArgumentException("MATERIAL_PREFLIGHT_BLOCKED: "+String.join(",",check.blockingCodes()));
        contracts.validateOneShot(context.path("shot").path("shotId").asText(),actual);List<ObjectNode> mapping=indexes.map(actual);
        RuntimeRulePackLoader.RulePack pack=rules.resolve(profile,task);AudioGenerationPolicy audio=audio(body);
        ObjectNode result=mapper.createObjectNode().put("modelId",profile.modelId()).put("modelProfileVersion",profile.profileVersion()).put("capabilityFingerprint",profile.capabilityFingerprint())
                .put("taskType",task.name()).put("lockMode",lock.name()).put("route",route.route().name()).put("videoRequestRoute",route.route().name()).put("rulePackFingerprint",pack.fingerprint())
                .put("rulePackUpstreamCommit",pack.upstreamCommit()).put("audioGenerationPolicy",audio.name());
        result.put("runtimeRules",pack.content());result.set("runtimeRuleIds",mapper.valueToTree(pack.ruleIds()));
        result.set("modelProfile",profile.toJson());result.set("activatedMaterials",selected);ArrayNode excluded=result.putArray("excludedMaterials");active.excluded().forEach(excluded::add);budget.excluded().forEach(excluded::add);
        result.set("referenceMapping",array(mapping));result.set("referenceAuthority",authority(selected));result.set("referenceBudget",budgetJson(profile,budget));result.set("preflight",preflightJson(check));result.set("providerParameters",parameters(body,audio,profile));
        if(route.firstFrameUrl()!=null)result.put("firstFrameProviderUrl",route.firstFrameUrl());ArrayNode refs=result.putArray("references");for(VideoGenerator.Reference ref:route.references())refs.add(mapper.createObjectNode().put("type",ref.type()).put("mediaType",ref.type()).put("url",ref.url()).put("role",ref.role()));
        return result;
    }
    public void validatePrompt(String prompt,JsonNode mapping){indexes.validatePrompt(prompt,mapping);}
    private VideoTaskType task(JsonNode body,JsonNode context,JsonNode previous){String explicit=body.path("videoTaskType").asText("");if(!explicit.isBlank())try{return VideoTaskType.valueOf(explicit);}catch(IllegalArgumentException e){throw new IllegalArgumentException("VIDEO_TASK_TYPE_INVALID: "+explicit);}String relation=context.path("shot").path("sequenceRelation").asText(context.path("shot").path("relationToPrevious").asText(""));return (relation.contains("CONTINU")&&previous.isObject()&&!previous.isEmpty())?VideoTaskType.REFERENCE_GENERATE:VideoTaskType.FIRST_FRAME_GENERATE;}
    private ProviderTaskLockMode lock(JsonNode body,VideoTaskType task){String raw=body.path("providerTaskLockMode").asText("");return raw.isBlank()?task.lockMode():ProviderTaskLockMode.valueOf(raw);}
    private AudioGenerationPolicy audio(JsonNode body){String raw=body.path("audioGenerationPolicy").asText("POST_ONLY");try{return AudioGenerationPolicy.valueOf(raw);}catch(IllegalArgumentException e){throw new IllegalArgumentException("AUDIO_GENERATION_POLICY_INVALID: "+raw);}}
    private ArrayNode keyframes(JsonNode body,JsonNode keyframe,double duration){ArrayNode frames=mapper.createArrayNode();if(body.path("keyframes").isArray()&&!body.path("keyframes").isEmpty())body.path("keyframes").forEach(v->frames.add(v.deepCopy()));else{ObjectNode frame=keyframe.isObject()?((ObjectNode)keyframe).deepCopy():mapper.createObjectNode();if(frame.path("semanticRole").asText("").isBlank())frame.put("semanticRole","START_FRAME");if(!frame.has("timeSec"))frame.put("timeSec",frame.path("semanticRole").asText().equals("END_FRAME")?duration:0);if(!frame.has("stateVersion"))frame.put("stateVersion",frame.path("version").asInt(0));frames.add(frame);}return frames;}
    private ArrayNode normalize(JsonNode values,String shotId){ArrayNode out=mapper.createArrayNode();if(values.isArray())for(JsonNode value:values){ObjectNode n=value.isObject()?((ObjectNode)value).deepCopy():mapper.createObjectNode();String id=n.path("id").asText(n.path("referenceId").asText(""));n.put("id",id).put("shotId",shotId);String media=n.path("mediaType").asText("");if("IMAGE".equals(media))media="image_url";else if("VIDEO".equals(media))media="video_url";else if("AUDIO".equals(media))media="audio_url";n.put("mediaType",media);if(n.path("entityId").asText("").isBlank())n.put("entityId",n.path("subjectId").asText(n.path("sourceResourceId").asText("")));String role=n.path("role").asText("");boolean required=Set.of("KEYFRAME_PROVIDER","PREVIOUS_TAKE","START_FRAME","END_FRAME").contains(role)||n.path("userSpecified").asBoolean(false);n.put("required",n.path("required").asBoolean(required));out.add(n);}return out;}
    private ArrayNode requestReferences(VideoRequestRouteResolver.Resolved route,String shotId,JsonNode selected,JsonNode keyframes){ArrayNode result=mapper.createArrayNode();if(route.firstFrameUrl()!=null)result.add(routedReference("native-start-frame",shotId,"image_url",route.firstFrameUrl(),"START_FRAME",selected,keyframes));int i=0;for(VideoGenerator.Reference ref:route.references())result.add(routedReference("route-ref-"+(++i),shotId,ref.type(),ref.url(),providerSemanticRole(ref.role()),selected,keyframes));return result;}
    private ObjectNode routedReference(String fallbackId,String shotId,String mediaType,String url,String role,JsonNode selected,JsonNode keyframes){ObjectNode result=source(url,selected,keyframes);if(result.path("id").asText("").isBlank())result.put("id",fallbackId);return result.put("shotId",shotId).put("mediaType",mediaType).put("url",url).put("role",role).put("required",true);}
    private ObjectNode source(String url,JsonNode selected,JsonNode keyframes){for(JsonNode source:selected)if(url.equals(source.path("url").asText()))return source.isObject()?((ObjectNode)source).deepCopy():mapper.createObjectNode();for(JsonNode source:keyframes)if(url.equals(source.path("providerUrl").asText(source.path("url").asText())))return source.isObject()?((ObjectNode)source).deepCopy():mapper.createObjectNode();return mapper.createObjectNode();}
    private String providerSemanticRole(String providerRole){return switch(providerRole){case "last_frame"->"END_FRAME";case "reference_video"->"MOTION_REFERENCE";case "reference_audio"->"AUDIO_REFERENCE";default->"COMPOSITION_REFERENCE";};}
    private ObjectNode parameters(JsonNode body,AudioGenerationPolicy audio,VideoModelProfile profile){ObjectNode options=body.path("providerOptions").isObject()?((ObjectNode)body.path("providerOptions")).deepCopy():mapper.createObjectNode();if(audio!=AudioGenerationPolicy.POST_ONLY&&!profile.supportsNativeAudio())throw new IllegalArgumentException("NATIVE_AUDIO_UNSUPPORTED");String ratio=options.path("ratio").asText("");if(!ratio.isBlank()&&!profile.supportedRatios().contains(ratio))throw new IllegalArgumentException("VIDEO_RATIO_UNSUPPORTED_BY_PROFILE: "+ratio);String resolution=options.path("resolution").asText("");if(!resolution.isBlank()&&!profile.supportedResolutions().contains(resolution))throw new IllegalArgumentException("VIDEO_RESOLUTION_UNSUPPORTED_BY_PROFILE: "+resolution);options.put("generate_audio",audio!=AudioGenerationPolicy.POST_ONLY);return options;}
    private ObjectNode preflightJson(MaterialPreflight.Result value){ObjectNode n=mapper.createObjectNode().put("passed",value.passed());n.set("blockingCodes",mapper.valueToTree(value.blockingCodes()));n.set("warnings",mapper.valueToTree(value.warnings()));return n;}
    private ArrayNode authority(JsonNode selected){ArrayNode out=mapper.createArrayNode();for(JsonNode ref:selected){ObjectNode n=mapper.createObjectNode();for(String field:List.of("id","role","entityId","authority","authorityPriority","controls","mustNotTransfer"))if(ref.has(field))n.set(field,ref.path(field).deepCopy());out.add(n);}return out;}
    private ObjectNode budgetJson(VideoModelProfile profile,ReferenceBudgeter.Result budget){return mapper.valueToTree(new ReferenceBudget(profile.hardLimits(),profile.recommendedLimits(),budget.selected().size(),budget.excluded().size()));}
    private ArrayNode array(Collection<? extends JsonNode> values){ArrayNode result=mapper.createArrayNode();values.forEach(v->result.add(v.deepCopy()));return result;}
}
