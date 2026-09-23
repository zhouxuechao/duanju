package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.NOVEL_CHUNK;
import static com.yourapp.drama.workflow.Documents.*;

@Component
@ConditionalOnProperty(name="drama.novel.intelligence.mode",havingValue="deterministic",matchIfMissing=true)
public class DeterministicNovelEpisodeScreenwriter implements NovelEpisodeScreenwriter {
    private final DocumentStore store;
    public DeterministicNovelEpisodeScreenwriter(DocumentStore store){this.store=store;}
    @Override public String cacheIdentity(){return "deterministic-screenwriter";}
    @Override public Result write(Input input){
        ObjectNode plan=input.episodePlan();List<ObjectNode> chunks=new ArrayList<>();plan.path("sourceChunkIds").forEach(id->chunks.add(store.get(NOVEL_CHUNK,id.asText())));
        ObjectNode script=obj().put("title",text(input.novel(),"title")+" · 第"+plan.path("episodeNo").asInt()+"集").put("summary",text(plan,"episodeGoal")).put("openingHook",text(plan,"openingHook")).put("endingHook",text(plan,"endingHook")).put("estimatedDurationSec",plan.path("estimatedDurationSec").asInt(input.adaptationPlan().path("targetEpisodeDuration").asInt(90)));script.set("sourceRefs",plan.path("sourceRefs").deepCopy());ArrayNode scenes=script.putArray("scenes");
        int sceneCount=Math.max(1,Math.min(2,chunks.size())),cursor=0;for(int sceneNo=1;sceneNo<=sceneCount;sceneNo++){int to=(int)Math.ceil((double)sceneNo*chunks.size()/sceneCount);List<ObjectNode> selected=chunks.subList(cursor,to);cursor=to;String source=String.join(" ",selected.stream().map(c->text(c,"normalizedText")).toList());ObjectNode scene=scenes.addObject().put("sceneKey","novel-"+id(plan)+"-scene-"+sceneNo).put("title","剧情场 "+sceneNo).put("locationId",first(plan.path("locations"))).put("timeOfDay","按原作连续性").put("scenePurpose",sceneNo==1?text(plan,"centralConflict"):text(plan,"climax")).put("description",abbreviate(source,240)).put("sourceType","ADAPTED");scene.set("characters",plan.path("characters").deepCopy());scene.set("props",plan.path("props").deepCopy());scene.putArray("actions").add(abbreviate(source,360));scene.set("storyFactChanges",plan.path("newFacts").isArray()?plan.path("newFacts").deepCopy():array());scene.set("knowledgeChanges",plan.path("knowledgeChanges").isArray()?plan.path("knowledgeChanges").deepCopy():array());scene.set("relationshipChanges",plan.path("relationshipChanges").isArray()?plan.path("relationshipChanges").deepCopy():array());scene.set("characterStateChanges",plan.path("characterChanges").isArray()?plan.path("characterChanges").deepCopy():array());scene.set("propStateChanges",plan.path("propChanges").isArray()?plan.path("propChanges").deepCopy():array());scene.set("locationStateChanges",plan.path("locationChanges").isArray()?plan.path("locationChanges").deepCopy():array());ArrayNode refs=scene.putArray("sourceRefs");selected.forEach(c->refs.add(id(c)));String speaker=first(plan.path("characters"));ObjectNode line=scene.putArray("dialogues").addObject().put("lineKey","novel-"+id(plan)+"-line-"+sceneNo).put("characterId",speaker).put("semanticText",sceneNo==1?"这条线索说明事情还没有结束。":"我们现在就沿着这条线索查下去。").put("spokenText",sceneNo==1?"这事没完。":"顺着这条线，继续查。").put("subtitleText",sceneNo==1?"这事没完":"继续追查").put("emotion",sceneNo==1?"警觉":"坚定").put("intent",sceneNo==1?"确认危险":"推动行动").put("startHint",sceneNo==1?"场景开端":"动作完成后").put("sourceType","ADAPTED");line.set("sourceRefs",refs.deepCopy());}
        return new Result(script,cacheIdentity(),"det-screenplay-"+UUID.randomUUID(),NovelPromptCompiler.EPISODE_SCRIPT_VERSION);
    }
    private static String first(JsonNode values){return values.isArray()&&!values.isEmpty()?values.get(0).asText():"";}
    private static String abbreviate(String value,int max){return value.length()<=max?value:value.substring(0,max);}
    private static ArrayNode array(){return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();}
}
