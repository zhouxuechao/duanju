package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class SelectiveRebuildService {
    private final DocumentStore store;public SelectiveRebuildService(DocumentStore store){this.store=store;}
    public ObjectNode prepare(String impactPlanId){ObjectNode impact=store.get(IMPACT_PLAN,impactPlanId);for(ObjectNode existing:store.list(REBUILD_PLAN,project(impact),null))if(impactPlanId.equals(text(existing,"impactPlanId")))return existing;ObjectNode plan=obj().put("projectId",project(impact)).put("impactPlanId",impactPlanId).put("status","PLANNED").put("requiresUserConfirmation",true).put("estimatedCost",0);ArrayNode local=plan.putArray("noCostActions"),llm=plan.putArray("llmActions"),image=plan.putArray("imageActions"),video=plan.putArray("videoActions"),tts=plan.putArray("ttsActions"),timeline=plan.putArray("timelineActions"),render=plan.putArray("renderActions");String type=text(impact,"impactType");local.add("PRESERVE_HISTORY").add("REVALIDATE_DEPENDENCIES");if("DIALOGUE_ONLY".equals(type)){tts.add("REGENERATE_CHANGED_DIALOGUE");timeline.add("REBUILD_AFFECTED_TIMELINE");render.add("RENDER_AFFECTED_EPISODE");}else if(java.util.Set.of("SHOT_LOCAL","SCENE_LOCAL","EPISODE_STRUCTURE").contains(type)){llm.add("REPLAN_AFFECTED_SHOTS");image.add("REGENERATE_AFFECTED_KEYFRAMES");video.add("REGENERATE_AFFECTED_TAKES");timeline.add("REBUILD_AFFECTED_TIMELINE");render.add("RENDER_AFFECTED_EPISODE");}ObjectNode counts=obj().put("llm",llm.size()).put("image",image.size()).put("video",video.size()).put("tts",tts.size()).put("timeline",timeline.size()).put("render",render.size());plan.set("estimatedRequestCounts",counts);return store.create(REBUILD_PLAN,plan);}
}
