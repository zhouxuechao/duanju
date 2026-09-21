package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.yourapp.drama.workflow.Documents.obj;

/** Process-local hard reservation for explicitly marked live canary runs. */
@Component
public class TestBudgetGuard {
    private final Environment environment;
    private final Map<String,Reservation> reservations=new ConcurrentHashMap<>();
    public TestBudgetGuard(Environment environment){this.environment=environment;}

    public synchronized boolean reserve(String taskType,JsonNode input){
        if(!guarded(input))return false;
        String category=category(taskType);if("LOCAL".equals(category))return false;
        String run=run(input);Reservation old=reservations.getOrDefault(run,Reservation.empty());double cost=input.path("estimatedCost").asDouble(0);
        Reservation next=old.reserve(category,cost);
        if(next.reservedCost+next.actualCost>property("TEST_MAX_COST_CNY",5d)||next.llm>property("TEST_MAX_REAL_LLM_REQUESTS",20)||next.images>property("TEST_MAX_REAL_IMAGE_REQUESTS",2)||next.videos>property("TEST_MAX_REAL_VIDEO_REQUESTS",2)||next.tts>property("TEST_MAX_REAL_AUDIO_REQUESTS",5)||next.lipsync>property("TEST_MAX_REAL_LIPSYNC_REQUESTS",2))throw new WorkflowException("TEST_BUDGET_EXCEEDED","真实 Canary 预算或请求次数已达到上限，禁止继续提交付费请求");
        reservations.put(run,next);return true;
    }

    public synchronized void release(String taskType,JsonNode input){
        if(!guarded(input))return;String category=category(taskType);if("LOCAL".equals(category))return;
        String run=run(input);Reservation old=reservations.get(run);if(old!=null)reservations.put(run,old.release(category,input.path("estimatedCost").asDouble(0)));
    }

    public synchronized void settle(String taskType,JsonNode input,boolean wasted){
        if(!guarded(input))return;String category=category(taskType);if("LOCAL".equals(category))return;
        String run=run(input);Reservation old=reservations.get(run);if(old!=null)reservations.put(run,old.settle(input.path("estimatedCost").asDouble(0),wasted));
    }

    public synchronized ObjectNode snapshot(String run){Reservation value=reservations.getOrDefault(run,Reservation.empty());return obj().put("plannedCost",value.plannedCost).put("reservedCost",value.reservedCost).put("actualCost",value.actualCost).put("wasteCost",value.wasteCost).put("llm",value.llm).put("image",value.images).put("video",value.videos).put("tts",value.tts).put("lipsync",value.lipsync);}

    private boolean guarded(JsonNode input){return (input.path("testRun").asBoolean(false)||environment.getProperty("DRAMA_TEST_RUN",Boolean.class,false))&&!"mock".equalsIgnoreCase(environment.getProperty("drama.provider.mode","mock"));}
    private String run(JsonNode input){return input.path("testRunId").asText(environment.getProperty("DRAMA_TEST_RUN_ID","default-canary"));}
    private String category(String type){if(Set.of("STORY","PREMISE","CORE","OUTLINE_BATCH","EPISODE_SCRIPT","STORY_QA","DIRECTOR_PLAN","SHOT_DETAIL").contains(type))return "LLM";if(Set.of("ASSET_IMAGE","STORYBOARD","KEYFRAME").contains(type))return "IMAGE";if("VIDEO".equals(type))return "VIDEO";if("TTS".equals(type))return "TTS";if("LIPSYNC".equals(type))return "LIPSYNC";return "LOCAL";}
    private int property(String key,int fallback){return environment.getProperty(key,Integer.class,fallback);}
    private double property(String key,double fallback){return environment.getProperty(key,Double.class,fallback);}

    private record Reservation(double plannedCost,double reservedCost,double actualCost,double wasteCost,int llm,int images,int videos,int tts,int lipsync){
        static Reservation empty(){return new Reservation(0,0,0,0,0,0,0,0,0);}
        Reservation reserve(String category,double cost){return change(category,1,plannedCost+cost,reservedCost+cost,actualCost,wasteCost);}
        Reservation release(String category,double cost){return change(category,-1,plannedCost,Math.max(0,reservedCost-cost),actualCost,wasteCost);}
        Reservation settle(double cost,boolean wasted){return new Reservation(plannedCost,Math.max(0,reservedCost-cost),actualCost+cost,wasteCost+(wasted?cost:0),llm,images,videos,tts,lipsync);}
        private Reservation change(String category,int delta,double planned,double reserved,double actual,double waste){return new Reservation(planned,reserved,actual,waste,llm+("LLM".equals(category)?delta:0),images+("IMAGE".equals(category)?delta:0),videos+("VIDEO".equals(category)?delta:0),tts+("TTS".equals(category)?delta:0),lipsync+("LIPSYNC".equals(category)?delta:0));}
    }
}
