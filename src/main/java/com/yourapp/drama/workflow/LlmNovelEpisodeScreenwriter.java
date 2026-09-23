package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
import static com.yourapp.drama.workflow.Documents.*;

@Component
@ConditionalOnProperty(name="drama.novel.intelligence.mode",havingValue="llm")
public class LlmNovelEpisodeScreenwriter implements NovelEpisodeScreenwriter {
    private final NovelIntelligenceExecutor ai;private final NovelPromptCompiler prompts;
    public LlmNovelEpisodeScreenwriter(NovelIntelligenceExecutor ai,NovelPromptCompiler prompts){this.ai=ai;this.prompts=prompts;}
    @Override public String cacheIdentity(){return ai.cacheIdentity("novel_screenwriter");}
    @Override public Result write(Input input){ObjectNode payload=obj();payload.set("confirmedEpisodePlan",input.episodePlan().deepCopy());ObjectNode boundedContext=obj().put("contextText",text(input.context(),"contextText")).put("previousEpisodeHandoff",text(input.episodePlan(),"previousEpisodeHandoff"));for(String field:List.of("sourceChunkIds","sourceFactIds","entityVersionIds","entityStateIds","reservedFutureFacts","knowledgeStart","propStateStart","locationStateStart"))boundedContext.set(field,input.context().path(field).deepCopy());payload.set("adaptationContext",boundedContext);payload.put("novelTitle",text(input.novel(),"title"));NovelPromptIR ir=new NovelPromptIR(id(input.novel()),strings(input.episodePlan().path("sourceChapterIds")),strings(input.episodePlan().path("sourceChunkIds")),concat(input.episodePlan().path("characters"),input.episodePlan().path("locations"),input.episodePlan().path("props")),strings(input.episodePlan().path("requiredFacts")),input.novel().path("analysisProfile").asText("STANDARD"),input.adaptationPlan().path("adaptationStyle").asText("BALANCED"),input.episodePlan().path("estimatedDurationSec").asInt(input.adaptationPlan().path("targetEpisodeDuration").asInt(90)),input.generationContextHash(),"UNTRUSTED_NOVEL_TEXT",List.of("不得输出 Shot、camera、lens","不得泄漏 reservedFutureFacts","所有 entity id 必须来自输入"),payload.toString());var result=ai.executeOrReuse(input.projectId(),ir,prompts.compileEpisodeScript(ir));return new Result(result.value(),result.model(),result.requestId(),NovelPromptCompiler.EPISODE_SCRIPT_VERSION);}
    private static List<String> strings(com.fasterxml.jackson.databind.JsonNode values){java.util.ArrayList<String> out=new java.util.ArrayList<>();values.forEach(v->out.add(v.asText()));return out;}
    private static List<String> concat(com.fasterxml.jackson.databind.JsonNode... values){java.util.LinkedHashSet<String> out=new java.util.LinkedHashSet<>();for(var value:values)value.forEach(v->out.add(v.asText()));return List.copyOf(out);}
}
