package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.*;

/** Turns the asset pool into an explicit per-shot allowlist with auditable exclusions. */
@Component
public class MaterialActivationPlan {
    public record Result(List<ObjectNode> activated,List<ObjectNode> excluded){public Result{activated=List.copyOf(activated);excluded=List.copyOf(excluded);}}
    private final ObjectMapper mapper;
    public MaterialActivationPlan(ObjectMapper mapper){this.mapper=mapper;}
    public Result plan(JsonNode candidates,JsonNode shot){
        return plan(candidates,shot,VideoTaskType.REFERENCE_GENERATE);
    }
    public Result plan(JsonNode candidates,JsonNode shot,VideoTaskType taskType){
        Set<String> visible=new LinkedHashSet<>();strings(shot.path("characterIds"),visible);strings(shot.path("propIds"),visible);String location=shot.path("locationId").asText("");if(!location.isBlank())visible.add(location);
        List<ObjectNode> active=new ArrayList<>(),excluded=new ArrayList<>();Set<String> ids=new HashSet<>();
        if(candidates!=null&&candidates.isArray())for(JsonNode candidate:candidates){
            ObjectNode item=candidate.isObject()?((ObjectNode)candidate).deepCopy():mapper.createObjectNode();String id=item.path("id").asText("").trim();
            if(id.isBlank()||!ids.add(id)){item.put("exclusionReason",id.isBlank()?"MATERIAL_ID_MISSING":"DUPLICATE_MATERIAL_ID");excluded.add(item);continue;}
            boolean user=item.path("userSpecified").asBoolean(false);String entity=item.path("entityId").asText("");String role=item.path("role").asText("");
            if(taskType.lockMode()==ProviderTaskLockMode.LOCKED&&!Set.of("KEYFRAME_PROVIDER","START_FRAME","END_FRAME").contains(role)){item.put("exclusionReason","TASK_ROUTE_DOES_NOT_ACCEPT_MATERIAL");excluded.add(item);continue;}
            if(!user&&entityScoped(role)&&!entity.isBlank()&&!visible.contains(entity)){item.put("exclusionReason","ENTITY_NOT_VISIBLE_IN_SHOT");excluded.add(item);continue;}
            ReferenceAuthority authority=user?ReferenceAuthority.USER_SPECIFIED:authority(item.path("role").asText(""));String source=user?"USER_SPECIFIED":source(item.path("authoritySource").asText(""));
            item.put("authority",authority.name()).put("authoritySource",source).put("authorityPriority",sourceRank(source)*1_000+authority.priority());
            if(!item.path("controls").isArray())item.set("controls",mapper.valueToTree(authority.controls()));
            if(!item.path("mustNotTransfer").isArray())item.set("mustNotTransfer",mapper.valueToTree(authority.mustNotTransfer()));
            item.put("activationReason",user?"USER_SPECIFIED":"VISIBLE_SHOT_ENTITY");active.add(item);
        }
        active.sort(Comparator.comparingInt((ObjectNode n)->n.path("authorityPriority").asInt()).reversed().thenComparing(n->n.path("id").asText()));
        return new Result(active,excluded);
    }
    private boolean entityScoped(String role){return Set.of("CHARACTER_IDENTITY","CHARACTER_LOOK","LOCATION","LOCATION_LAYOUT","LOCATION_IDENTITY","PROP","PROP_IDENTITY").contains(role);}
    private ReferenceAuthority authority(String raw){return ReferenceAuthority.fromRole(ReferenceBinding.Role.from(raw));}
    private String source(String raw){return switch(raw){case "SHOT_CONTRACT","STORY_CONTRACT"->"SHOT_CONTRACT";case "CANONICAL_STATE"->"CANONICAL_STATE";case "OBSERVED_CONTENT","OBSERVED_STATE"->"OBSERVED_CONTENT";case "AUTO_INFERRED"->"AUTO_INFERRED";default->"ASSET_METADATA";};}
    private int sourceRank(String source){return switch(source){case "USER_SPECIFIED"->6;case "SHOT_CONTRACT"->5;case "CANONICAL_STATE"->4;case "ASSET_METADATA"->3;case "OBSERVED_CONTENT"->2;default->1;};}
    private void strings(JsonNode values,Set<String> out){if(values.isArray())values.forEach(v->{String s=v.asText("").trim();if(!s.isBlank())out.add(s);});}
}
