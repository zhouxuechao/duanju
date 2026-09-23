package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import static com.yourapp.drama.workflow.Documents.*;

@Component
@ConditionalOnProperty(name="drama.novel.intelligence.mode",havingValue="deterministic",matchIfMissing=true)
public class DeterministicNovelIntelligenceProvider implements NovelIntelligenceProvider {
    @Override public Result execute(NovelPromptCompiler.Compiled prompt){
        ObjectNode value=obj();prompt.schema().get("properties");
        @SuppressWarnings("unchecked") var properties=(java.util.Map<String,Object>)prompt.schema().get("properties");
        for(String field:properties.keySet()){Object definition=properties.get(field);boolean string=definition instanceof java.util.Map<?,?> map&&"string".equals(map.get("type"));if(string)value.put(field,prompt.taskType()+" deterministic");else value.putArray(field);}
        return new Result(value,"DETERMINISTIC","deterministic-novel","det-"+java.util.UUID.randomUUID(),"stop",0,0,true);
    }
}
