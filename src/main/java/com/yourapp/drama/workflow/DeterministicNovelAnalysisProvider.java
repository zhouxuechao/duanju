package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.yourapp.drama.workflow.Documents.*;

/** Deterministic N4 provider used by tests and local development; it performs no network calls. */
@Component
public class DeterministicNovelAnalysisProvider implements NovelAnalysisProvider {
    private static final List<String> ARRAY_FIELDS=List.of("characterMentions","locationMentions","propMentions","events","storyFacts","relationshipChanges","knowledgeChanges","timelineHints","conflicts","foreshadowing","payoffs","importantDialogue");
    @Override public ObjectNode analyze(ObjectNode request){if(request.has("wholeNovelText"))throw new WorkflowException("WHOLE_NOVEL_PROMPT_FORBIDDEN","小说分析只能按语义块请求");if(!"UNTRUSTED_NOVEL_TEXT".equals(text(request,"sourceBoundary")))throw new WorkflowException("SOURCE_BOUNDARY_MISSING","小说原文必须标记为不可信数据");String source=text(request,"sourceText");ObjectNode result=obj().put("summary",source.length()>180?source.substring(0,180):source);ARRAY_FIELDS.forEach(name->result.putArray(name));tagged(source,"人物").forEach(value->split(value).forEach(result.withArray("characterMentions")::add));tagged(source,"地点").forEach(value->split(value).forEach(result.withArray("locationMentions")::add));tagged(source,"道具").forEach(value->split(value).forEach(result.withArray("propMentions")::add));tagged(source,"事实").forEach(result.withArray("storyFacts")::add);tagged(source,"事件").forEach(result.withArray("events")::add);tagged(source,"伏笔").forEach(result.withArray("foreshadowing")::add);tagged(source,"回收").forEach(result.withArray("payoffs")::add);for(String value:tagged(source,"知情")){String[] pair=value.split("知道",2);if(pair.length==2)result.withArray("knowledgeChanges").add(obj().put("character",clean(pair[0])).put("fact",clean(pair[1])).put("state","KNOWN"));}for(String value:tagged(source,"关系")){Matcher match=Pattern.compile("([^>]+)>([^:：]+)[:：](.+)").matcher(value);if(match.find())result.withArray("relationshipChanges").add(obj().put("subject",clean(match.group(1))).put("object",clean(match.group(2))).put("type",clean(match.group(3))));}if(result.withArray("events").isEmpty()&&!source.isBlank())result.withArray("events").add(result.path("summary").asText());return result;}
    private static List<String> tagged(String source,String label){Matcher matcher=Pattern.compile(Pattern.quote(label)+"[:：]([^；;。\n]+)").matcher(source);List<String> values=new ArrayList<>();while(matcher.find())values.add(clean(matcher.group(1)));return values;}
    private static List<String> split(String value){return Arrays.stream(value.split("[,，、|]")).map(DeterministicNovelAnalysisProvider::clean).filter(v->!v.isBlank()).distinct().toList();}
    private static String clean(String value){return value==null?"":value.strip().replaceAll("^[：:；;]+|[：:；;]+$","");}
}
