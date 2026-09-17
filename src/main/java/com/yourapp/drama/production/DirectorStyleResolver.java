package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.util.*;

/** Data-only project style with a bounded scene override; no genre-specific branches. */
@Component
public final class DirectorStyleResolver {
    private static final Set<String> FIELDS=Set.of("visualRhythm","averageShotLength","cameraActivity","closeUpPreference","reactionShotPreference","compositionStyle","tensionStyle");
    private final ObjectMapper mapper;
    public DirectorStyleResolver(ObjectMapper mapper){this.mapper=mapper;}
    public ObjectNode resolve(JsonNode project,JsonNode scene){
        ObjectNode result=defaults();merge(result,project.path("directorStyleProfile"));merge(result,scene.path("directorStyleOverride"));validate(result);return result;
    }
    public ObjectNode defaults(){return mapper.createObjectNode().put("visualRhythm","动作清楚，信息变化时切镜").put("averageShotLength",3.0)
        .put("cameraActivity","LOW").put("closeUpPreference",0.45).put("reactionShotPreference",0.65)
        .put("compositionStyle","先建立空间，再按人物关系组织景别").put("tensionStyle","通过信息保留和反应逐级推进");}
    private void merge(ObjectNode target,JsonNode source){if(source.isObject())source.fields().forEachRemaining(e->{if(!FIELDS.contains(e.getKey()))throw new IllegalArgumentException("未知导演风格字段："+e.getKey());target.set(e.getKey(),e.getValue().deepCopy());});}
    private void validate(JsonNode value){double average=value.path("averageShotLength").asDouble(-1);if(average<1||average>8)throw new IllegalArgumentException("averageShotLength 应为 1～8 秒");
        if(!Set.of("LOW","BALANCED","HIGH").contains(value.path("cameraActivity").asText()))throw new IllegalArgumentException("cameraActivity 无效");
        for(String field:List.of("closeUpPreference","reactionShotPreference")){double n=value.path(field).asDouble(-1);if(n<0||n>1)throw new IllegalArgumentException(field+" 应为 0～1");}}
}
