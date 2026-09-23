package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class ScriptImpactAnalyzer {
    private final DocumentStore store;private final ScriptDiffService diffs;private final DependencyRevisionService dependencies;
    public ScriptImpactAnalyzer(DocumentStore store,ScriptDiffService diffs,DependencyRevisionService dependencies){this.store=store;this.diffs=diffs;this.dependencies=dependencies;}

    public ObjectNode analyze(String sourceVersionId,String targetVersionId,ChangeScope scope,ChangePoint point){
        ObjectNode source=store.get(EPISODE_SCRIPT_VERSION,sourceVersionId),target=store.get(EPISODE_SCRIPT_VERSION,targetVersionId);if(!project(source).equals(project(target)))throw new IllegalArgumentException("影响分析版本必须属于同一项目");ObjectNode diff=diffs.compare(source.path("structuredContent"),target.path("structuredContent"));String type=classify(diff);String projectId=project(target);ObjectNode plan=obj().put("projectId",projectId).put("changeId",UUID.randomUUID().toString()).put("sourceVersionId",sourceVersionId).put("targetVersionId",targetVersionId).put("changeScope",scope.name()).put("impactType",type).put("revalidationOnly",Set.of("TEXT_ONLY","CONTINUITY_FORWARD","ENTITY_DEFINITION").contains(type));plan.set("changePoint",point.toJson());plan.set("diff",diff.deepCopy());
        ArrayNode episodes=plan.putArray("affectedEpisodes"),scenes=plan.putArray("affectedScenes"),shots=plan.putArray("affectedShots"),keyframes=plan.putArray("affectedKeyframes"),videos=plan.putArray("affectedVideos"),audio=plan.putArray("affectedAudio"),timelines=plan.putArray("affectedTimelines"),renders=plan.putArray("affectedRenders");
        if("DIALOGUE_ONLY".equals(type)){audio.add("REBUILD_TTS");timelines.add("REBUILD_TIMELINE");renders.add("REBUILD_RENDER");}
        else if(Set.of("SHOT_LOCAL","SCENE_LOCAL","EPISODE_STRUCTURE").contains(type)){shots.add("REBUILD_SHOTS");keyframes.add("REBUILD_KEYFRAMES");videos.add("REBUILD_VIDEOS");timelines.add("REBUILD_TIMELINE");renders.add("REBUILD_RENDER");}
        if(Set.of("CONTINUITY_FORWARD","ENTITY_DEFINITION","GLOBAL").contains(type)){
            Set<String> allowed=dependencyTypes(diff);ArrayNode affected=dependencies.markForwardDependencies(projectId,EPISODE_SCRIPT_VERSION,targetVersionId,scope,point,targetVersionId,allowed);
            for(JsonNode node:affected){String kind=text(node,"resourceKind"),resourceId=text(node,"resourceId");if(kind.equals(EPISODE.path()))episodes.add(resourceId);else if(kind.equals(SCENE.path()))scenes.add(resourceId);else if(kind.equals(SHOT.path()))shots.add(resourceId);else if(kind.equals(KEYFRAME.path()))keyframes.add(resourceId);else if(kind.equals(VIDEO_TAKE.path()))videos.add(resourceId);else if(kind.equals(AUDIO_CLIP.path()))audio.add(resourceId);else if(kind.equals(TIMELINE.path()))timelines.add(resourceId);}
        }
        ObjectNode counts=obj().put("episodes",episodes.size()).put("scenes",scenes.size()).put("shots",shots.size()).put("keyframes",keyframes.size()).put("videos",videos.size()).put("audio",audio.size()).put("timelines",timelines.size()).put("renders",renders.size());plan.set("estimatedRebuildCounts",counts);return store.create(IMPACT_PLAN,plan);
    }
    private static String classify(JsonNode diff){if(!diff.path("changedFacts").isEmpty()||!diff.path("changedKnowledge").isEmpty()||!diff.path("changedRelationships").isEmpty()||!diff.path("changedPropStates").isEmpty()||!diff.path("changedLocationStates").isEmpty())return "CONTINUITY_FORWARD";if(!diff.path("addedScenes").isEmpty()||!diff.path("removedScenes").isEmpty()||!diff.path("movedScenes").isEmpty())return "EPISODE_STRUCTURE";if(!diff.path("changedActions").isEmpty())return "SHOT_LOCAL";if(!diff.path("changedDialogues").isEmpty())return "DIALOGUE_ONLY";return "TEXT_ONLY";}
    private static Set<String> dependencyTypes(JsonNode diff){Set<String> result=new HashSet<>();if(!diff.path("changedKnowledge").isEmpty())result.add("KNOWLEDGE");if(!diff.path("changedFacts").isEmpty())result.add("STORY_FACT");if(!diff.path("changedRelationships").isEmpty())result.add("RELATIONSHIP");if(!diff.path("changedPropStates").isEmpty())result.add("PROP_STATE");if(!diff.path("changedLocationStates").isEmpty())result.add("LOCATION_STATE");return result;}
}
