package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class SemanticDependencyResolver {
    public ObjectNode resolve(String sourceKind,String sourceId,JsonNode before,JsonNode after){Set<String> changed=new TreeSet<>();Set<String> names=new TreeSet<>();before.fieldNames().forEachRemaining(names::add);after.fieldNames().forEachRemaining(names::add);for(String field:names)if(!Objects.equals(before.get(field),after.get(field))&&!Set.of("revision","updatedAt","impactDecision").contains(field))changed.add(field);String kind=sourceKind.toLowerCase(Locale.ROOT),action="NO_IMPACT",explain="仅编辑展示信息，无须重做已生成内容";ArrayNode impacts=JsonNodeFactory.instance.arrayNode();
        if(changed.isEmpty())explain="内容没有发生语义变化，无须重做";
        else if(kind.equals("projects")&&changed.stream().allMatch(Set.of("name","description")::contains)){}
        else if(kind.equals("dialogue-lines")&&changed.stream().anyMatch(Set.of("displayText","speechText","dialectText","voiceProfileId")::contains)){action="REGENERATE";explain="对白内容或音色变化，需要重做 TTS，并重新校验 SUBTITLE、LIPSYNC 与 TIMELINE";impact(impacts,"TTS","REGENERATE");impact(impacts,"SUBTITLE","REVALIDATE");impact(impacts,"TIMELINE","STALE");}
        else if(kind.equals("voice-profiles")&&changed.stream().anyMatch(Set.of("providerVoiceId","referenceAudioUrl")::contains)){action="REGENERATE";explain="声音身份变化，需要重做 TTS 与 LIPSYNC，画面和剧本不受影响";impact(impacts,"TTS","REGENERATE");impact(impacts,"LIPSYNC","REGENERATE");}
        else if(kind.equals("timeline-items")){action="REGENERATE";explain="剪辑点或转场变化，只需重做 PREVIEW、TIMELINE_QA 与 RENDER";impact(impacts,"PREVIEW","REGENERATE");impact(impacts,"TIMELINE_QA","REVALIDATE");impact(impacts,"RENDER","REGENERATE");}
        else if(Set.of("location-states","character-states","prop-states","relationships","story-facts").contains(kind)){action="REVALIDATE";explain="连续性状态变化，需要从有效故事时间开始重新校验 KEYFRAME、VIDEO 和后续 TIMELINE";impact(impacts,"KEYFRAME","REVALIDATE");impact(impacts,"VIDEO","REVALIDATE");impact(impacts,"TIMELINE","REVALIDATE");}
        else if(kind.equals("shots")){action="REGENERATE";explain="镜头表演、机位或状态变化，需要重做该镜头的 KEYFRAME 与 VIDEO，不影响已确认故事";impact(impacts,"KEYFRAME","REGENERATE");impact(impacts,"VIDEO","REGENERATE");}
        else {action="STALE";explain="字段改变会影响下游语义，相关资源需标记 STALE 后局部重建";impact(impacts,"DOWNSTREAM","STALE");}
        ObjectNode result=JsonNodeFactory.instance.objectNode().put("sourceKind",sourceKind).put("sourceId",sourceId).put("action",action).put("explain",explain);ArrayNode fields=result.putArray("changedFields");changed.forEach(fields::add);result.set("impacts",impacts);return result;}
    private void impact(ArrayNode impacts,String target,String action){impacts.add(JsonNodeFactory.instance.objectNode().put("target",target).put("action",action));}
}
