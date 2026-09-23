package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.yourapp.drama.workflow.Documents.text;

@Component
public class NovelIntelligenceQualityValidator {
    public void chunk(ObjectNode value){if(text(value,"summary").isBlank())fail("Chunk summary 缺失");for(String field:List.of("events","characterMentions","locationMentions","propMentions","storyFacts","relationshipChanges","knowledgeChanges","characterStateChanges","locationStateChanges","propStateChanges","timelineHints","conflicts","foreshadowing","payoffs","importantDialogue","openQuestions","adaptationSignals"))if(!value.path(field).isArray())fail("Chunk "+field+" 必须为数组");for(String field:List.of("characterMentions","locationMentions","propMentions"))for(JsonNode mention:value.path(field))if(!mention.isObject()||text(mention,"name").isBlank()||!mention.path("evidence").isArray())fail(field+" 缺少 name/evidence");}
    public void chapter(ObjectNode value){for(String field:List.of("chapterSummary","openingState","endingState"))if(text(value,field).isBlank())fail("Chapter "+field+" 缺失");if(!value.path("turningPoints").isArray())fail("Chapter turningPoints 缺失");}
    public void arc(ObjectNode value){for(String field:List.of("arcGoal","mainConflict","climax","resolution"))if(text(value,field).isBlank())fail("Arc "+field+" 缺失");}
    public void graph(ObjectNode value){if(text(value,"mainPlot").isBlank())fail("Global Graph mainPlot 缺失");}
    public void adaptationPlan(List<ObjectNode> episodes,ObjectNode novel){int expected=1;for(ObjectNode episode:episodes){if(episode.path("episodeNo").asInt()!=expected++)fail("Episode number 必须连续");if(episode.path("sourceChapterIds").isEmpty()||episode.path("sourceRefs").isEmpty())fail("Episode source refs 缺失");Set<String> reserved=new HashSet<>();episode.path("reservedFutureFacts").forEach(v->reserved.add(v.asText()));for(String field:List.of("requiredFacts","newFacts"))for(JsonNode fact:episode.path(field))if(reserved.contains(fact.asText()))fail("reserved fact 提前泄漏");}}
    private static void fail(String message){throw new WorkflowException("NOVEL_INTELLIGENCE_QUALITY_FAILED",message);}
}
