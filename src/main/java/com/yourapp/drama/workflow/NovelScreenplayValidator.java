package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Component
public class NovelScreenplayValidator {
    private static final List<String> PLACEHOLDERS=List.of("根据本段事件继续行动","待补充","TODO","占位","自行发挥","略");
    private final DocumentStore store;public NovelScreenplayValidator(DocumentStore store){this.store=store;}
    public void validate(String projectId,ObjectNode plan,ObjectNode script){
        for(String field:List.of("title","summary","openingHook","endingHook"))if(text(script,field).isBlank())fail("剧本缺少 "+field);
        int target=plan.path("estimatedDurationSec").asInt(90),actual=script.path("estimatedDurationSec").asInt();if(actual<Math.floor(target*.85)||actual>Math.ceil(target*1.15))fail("剧本时长超出 ±15% 合同");
        if(!script.path("scenes").isArray()||script.path("scenes").isEmpty())fail("剧本至少需要一个 Scene");Set<String> chunks=set(plan.path("sourceChunkIds")),reserved=set(plan.path("reservedFutureFacts"));Set<String> characters=set(plan.path("characters")),locations=set(plan.path("locations")),props=set(plan.path("props"));validateRefs(script.path("sourceRefs"),chunks);for(String future:reserved)if(script.toString().contains(future))fail("剧本泄漏 reservedFutureFact");
        rejectDirectorFields(script);
        for(JsonNode scene:script.path("scenes")){if(!Set.of("ORIGINAL_QUOTE","ADAPTED","AI_CREATED").contains(text(scene,"sourceType")))fail("Scene sourceType 无效");if(!scene.path("actions").isArray()||scene.path("actions").isEmpty())fail("Scene actions 不能为空");validateRefs(scene.path("sourceRefs"),chunks);String location=text(scene,"locationId");if(!location.isBlank()){if(!locations.isEmpty()&&!locations.contains(location))fail("Scene location 不在分集计划中");entity(projectId,LOCATION,location);}for(JsonNode id:scene.path("characters")){if(!characters.isEmpty()&&!characters.contains(id.asText()))fail("Scene character 不在分集计划中");entity(projectId,CHARACTER,id.asText());}for(JsonNode id:scene.path("props")){if(!props.isEmpty()&&!props.contains(id.asText()))fail("Scene prop 不在分集计划中");entity(projectId,PROP,id.asText());}validateEntityChanges(projectId,scene.path("knowledgeChanges"),"characterId",characters,CHARACTER);validateEntityChanges(projectId,scene.path("characterStateChanges"),"entityId",characters,CHARACTER);validateEntityChanges(projectId,scene.path("propStateChanges"),"entityId",props,PROP);validateEntityChanges(projectId,scene.path("locationStateChanges"),"entityId",locations,LOCATION);for(JsonNode change:scene.path("propStateChanges")){String holder=text(change,"holderId");if(!holder.isBlank()&&!characters.contains(holder))fail("Prop holder 无效");}for(JsonNode line:scene.path("dialogues")){for(String field:List.of("lineKey","semanticText","spokenText","subtitleText","emotion","intent","startHint","sourceType"))if(text(line,field).isBlank())fail("Dialogue 缺少 "+field);if(!Set.of("ORIGINAL_QUOTE","ADAPTED","AI_CREATED").contains(text(line,"sourceType")))fail("Dialogue sourceType 无效");String speaker=text(line,"characterId");if(!characters.isEmpty()&&!characters.contains(speaker))fail("Dialogue owner 无效");if(!speaker.isBlank())entity(projectId,CHARACTER,speaker);validateRefs(line.path("sourceRefs"),chunks);for(String placeholder:PLACEHOLDERS)if(line.toString().contains(placeholder))fail("剧本包含占位文本");}}
    }
    private void entity(String projectId,com.yourapp.drama.persistence.ResourceKind kind,String id){ObjectNode value=store.get(kind,id);if(!projectId.equals(project(value)))fail("实体不属于当前项目");}
    private void validateEntityChanges(String projectId,JsonNode changes,String idField,Set<String> allowed,com.yourapp.drama.persistence.ResourceKind kind){if(!changes.isArray())fail("状态变化必须是数组");for(JsonNode change:changes){if(!change.isObject())fail("状态变化必须是对象");String entityId=text(change,idField);if(entityId.isBlank()||!allowed.contains(entityId))fail("状态变化引用无效实体");entity(projectId,kind,entityId);}}
    private static void validateRefs(JsonNode refs,Set<String> allowed){if(!refs.isArray()||refs.isEmpty())fail("sourceRefs 不能为空");for(JsonNode ref:refs)if(!allowed.contains(ref.asText()))fail("sourceRef 不属于当前 Episode");}
    private static void rejectDirectorFields(JsonNode value){if(value.isObject()){value.fieldNames().forEachRemaining(field->{if(Set.of("camera","cameraPlan","shotSize","lens","focalLength").contains(field))fail("剧本阶段不得输出导演镜头字段");rejectDirectorFields(value.path(field));});}else if(value.isArray())value.forEach(NovelScreenplayValidator::rejectDirectorFields);}
    private static Set<String> set(JsonNode values){Set<String> out=new LinkedHashSet<>();values.forEach(v->out.add(v.asText()));return out;}
    private static void fail(String message){throw new WorkflowException("NOVEL_SCREENPLAY_INVALID",message);}
}
