package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.yourapp.drama.workflow.Documents.*;

@Service
public class ScriptDiffService {
    public ObjectNode compare(JsonNode from,JsonNode to){
        ObjectNode result=obj();ArrayNode fields=result.putArray("changedFields");
        for(String field:List.of("title","summary","openingHook","endingHook","rawText"))if(!Objects.equals(from.get(field),to.get(field)))fields.add(field);
        Map<String,JsonNode> before=scenes(from.path("scenes")),after=scenes(to.path("scenes"));
        array(result,"addedScenes",difference(after.keySet(),before.keySet()));array(result,"removedScenes",difference(before.keySet(),after.keySet()));
        ArrayNode moved=result.putArray("movedScenes"),actions=result.putArray("changedActions"),dialogues=result.putArray("changedDialogues");
        List<String> beforeOrder=new ArrayList<>(before.keySet()),afterOrder=new ArrayList<>(after.keySet());
        for(String key:before.keySet())if(after.containsKey(key)){
            if(beforeOrder.indexOf(key)!=afterOrder.indexOf(key))moved.add(key);
            if(!before.get(key).path("actions").equals(after.get(key).path("actions")))actions.add(key);
            if(!before.get(key).path("dialogues").equals(after.get(key).path("dialogues")))dialogues.add(key);
        }
        changed(result,"changedEntities",from,to,"characters","locations","props");
        changed(result,"changedFacts",from,to,"storyFactChanges");changed(result,"changedKnowledge",from,to,"knowledgeChanges");
        changed(result,"changedRelationships",from,to,"relationshipChanges");changed(result,"changedPropStates",from,to,"propStateChanges");changed(result,"changedLocationStates",from,to,"locationStateChanges");
        int count=fields.size()+result.path("addedScenes").size()+result.path("removedScenes").size()+moved.size()+actions.size()+dialogues.size();
        result.put("humanReadableSummary",count==0?"两个版本没有内容差异":"共发现 "+count+" 组剧本改动；旧版本保持不变");return result;
    }
    private static Map<String,JsonNode> scenes(JsonNode value){Map<String,JsonNode> result=new LinkedHashMap<>();int i=0;for(JsonNode scene:value){String key=scene.path("sceneKey").asText("scene-"+(++i));result.put(key,scene);}return result;}
    private static Set<String> difference(Set<String> a,Set<String> b){Set<String> result=new LinkedHashSet<>(a);result.removeAll(b);return result;}
    private static void array(ObjectNode target,String field,Collection<String> values){ArrayNode array=target.putArray(field);values.forEach(array::add);}
    private static void changed(ObjectNode result,String output,JsonNode from,JsonNode to,String... fields){ArrayNode values=result.putArray(output);for(String field:fields)if(!Objects.equals(from.findValues(field),to.findValues(field)))values.add(field);}
}
