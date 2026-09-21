package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local hard reservation for explicitly marked live canary runs. */
@Component
public class TestBudgetGuard {
    private final Environment environment;private final Map<String,Reservation> reservations=new ConcurrentHashMap<>();
    public TestBudgetGuard(Environment environment){this.environment=environment;}
    public synchronized void reserve(String taskType,JsonNode input){
        boolean testRun=input.path("testRun").asBoolean(false)||environment.getProperty("DRAMA_TEST_RUN",Boolean.class,false);if(!testRun||"mock".equalsIgnoreCase(environment.getProperty("drama.provider.mode","mock")))return;
        String run=input.path("testRunId").asText(environment.getProperty("DRAMA_TEST_RUN_ID","default-canary")),category=category(taskType);Reservation old=reservations.getOrDefault(run,new Reservation(0,0,0,0,0));double cost=input.path("estimatedCost").asDouble(0),maxCost=property("TEST_MAX_COST_CNY",5d);int llm=old.llm+("LLM".equals(category)?1:0),images=old.images+("IMAGE".equals(category)?1:0),videos=old.videos+("VIDEO".equals(category)?1:0),audio=old.audio+("AUDIO".equals(category)?1:0);
        if(old.cost+cost>maxCost||llm>property("TEST_MAX_REAL_LLM_REQUESTS",20)||images>property("TEST_MAX_REAL_IMAGE_REQUESTS",2)||videos>property("TEST_MAX_REAL_VIDEO_REQUESTS",2)||audio>property("TEST_MAX_REAL_AUDIO_REQUESTS",5))throw new WorkflowException("TEST_BUDGET_EXCEEDED","真实 Canary 预算或请求次数已达到上限，禁止继续提交付费请求");
        reservations.put(run,new Reservation(old.cost+cost,llm,images,videos,audio));
    }
    private String category(String type){if(Set.of("STORY","PREMISE","CORE","OUTLINE_BATCH","EPISODE_SCRIPT","STORY_QA","DIRECTOR_PLAN","SHOT_DETAIL").contains(type))return "LLM";if(Set.of("ASSET_IMAGE","STORYBOARD","KEYFRAME").contains(type))return "IMAGE";if(Set.of("VIDEO","LIPSYNC").contains(type))return "VIDEO";return "AUDIO";}
    private int property(String key,int fallback){return environment.getProperty(key,Integer.class,fallback);}
    private double property(String key,double fallback){return environment.getProperty(key,Double.class,fallback);}
    private record Reservation(double cost,int llm,int images,int videos,int audio){}
}
