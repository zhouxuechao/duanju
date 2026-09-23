package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import static com.yourapp.drama.workflow.Documents.*;

@Component
@ConditionalOnProperty(name="drama.novel.intelligence.mode",havingValue="deterministic",matchIfMissing=true)
public class DeterministicNovelIntelligenceProvider implements NovelIntelligenceProvider {
    private final ObjectMapper mapper;private final DeterministicNovelAnalysisProvider chunks;
    public DeterministicNovelIntelligenceProvider(ObjectMapper mapper,DeterministicNovelAnalysisProvider chunks){this.mapper=mapper;this.chunks=chunks;}
    @Override public Result execute(NovelPromptCompiler.Compiled prompt){
        ObjectNode value=obj();
        @SuppressWarnings("unchecked") var properties=(java.util.Map<String,Object>)prompt.schema().get("properties");
        for(String field:properties.keySet()){Object definition=properties.get(field);boolean string=definition instanceof java.util.Map<?,?> map&&"string".equals(map.get("type"));if(string)value.put(field,prompt.taskType()+" deterministic");else value.putArray(field);}
        if("CHUNK_ANALYSIS".equals(prompt.taskType()))try{
            JsonNode envelope=mapper.readTree(prompt.userPrompt());String source=envelope.path("untrustedSource").asText();
            ObjectNode legacy=chunks.analyze(obj().put("sourceBoundary","UNTRUSTED_NOVEL_TEXT").put("sourceText",source));value=legacy.deepCopy();
            for(String field:properties.keySet())if(!value.has(field)){Object definition=properties.get(field);boolean string=definition instanceof java.util.Map<?,?> map&&"string".equals(map.get("type"));if(string)value.put(field,"");else value.putArray(field);}
            for(String field:java.util.List.of("characterMentions","locationMentions","propMentions")){var structured=value.putArray(field);for(JsonNode item:legacy.path(field)){ObjectNode mention=structured.addObject().put("name",item.asText()).put("description","").put("roleHint",field).put("confidence",.95);mention.putArray("aliases");mention.putArray("evidence").add(source.length()>80?source.substring(0,80):source);}}
            value.put("narrativeImportance",50);
        }catch(Exception error){throw new IllegalArgumentException("确定性小说分析输入无效",error);}
        return new Result(value,"DETERMINISTIC","deterministic-novel","det-"+java.util.UUID.randomUUID(),"stop",0,0,true);
    }
    @Override public String cacheIdentity(String modelRole){return "deterministic-novel";}
}
