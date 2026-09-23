package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.yourapp.drama.workflow.Documents.obj;

/** Durable hard reservation for explicitly marked live canary runs. */
@Component
public class TestBudgetGuard {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Map<String,Reservation> reservations=new ConcurrentHashMap<>();
    public TestBudgetGuard(Environment environment){this(environment,null,null,null);}
    @Autowired
    public TestBudgetGuard(Environment environment,JdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager){
        this.environment=environment;this.jdbc=jdbc;this.mapper=mapper;this.transactions=manager==null?null:new TransactionTemplate(manager);
    }

    public synchronized boolean reserve(String taskType,JsonNode input){
        if(!guarded(input))return false;
        String category=category(taskType);if("LOCAL".equals(category))return false;
        String run=run(input);double cost=estimatedCost(taskType,input);
        if(!Double.isFinite(cost)||cost<0)throw new WorkflowException("TEST_BUDGET_INVALID","真实 Canary 预计成本必须是非负有限数值");
        return transaction(()->{Reservation next=load(run).reserve(category,taskType,cost);
            int lipsyncLimit="PIPELINE".equalsIgnoreCase(input.path("testPhase").asText())?0:property("TEST_MAX_REAL_LIPSYNC_REQUESTS",2);
            boolean pipeline="PIPELINE".equalsIgnoreCase(input.path("testPhase").asText());
            if(next.reservedCost+next.actualCost>limit(input,"TEST_MAX_COST_CNY",5d)||next.llm>limit(input,"TEST_MAX_REAL_LLM_REQUESTS",20)||next.vlm>limit(input,"TEST_MAX_REAL_VLM_REQUESTS",0)||next.images>limit(input,"TEST_MAX_REAL_IMAGE_REQUESTS",2)||next.videos>limit(input,"TEST_MAX_REAL_VIDEO_REQUESTS",2)||next.tts>limit(input,"TEST_MAX_REAL_AUDIO_REQUESTS",5)||next.lipsync>lipsyncLimit||(pipeline&&(next.keyframeQc>4||next.videoQc>4)))throw new WorkflowException("TEST_BUDGET_EXCEEDED","真实 Canary 预算或请求次数已达到上限，禁止继续提交付费请求");
            save(run,next);return true;});
    }

    public synchronized void release(String taskType,JsonNode input){
        if(!guarded(input))return;String category=category(taskType);if("LOCAL".equals(category))return;
        String run=run(input);double cost=estimatedCost(taskType,input);transaction(()->{Reservation old=load(run);save(run,old.release(category,taskType,cost));return null;});
    }

    public synchronized void settle(String taskType,JsonNode input,boolean wasted){
        if(!guarded(input))return;String category=category(taskType);if("LOCAL".equals(category))return;
        String run=run(input);double cost=estimatedCost(taskType,input);transaction(()->{Reservation old=load(run);save(run,old.settle(cost,wasted));return null;});
    }

    public synchronized ObjectNode snapshot(String run){return document(load(run));}

    /** Freezes a category-specific per-request estimate into each persisted Pipeline Canary job. */
    public void applyEstimatedCost(String taskType,ObjectNode input){if(guarded(input)&&pipeline(input)&&!input.has("estimatedCost"))input.put("estimatedCost",estimatedCost(taskType,input));}

    public double estimatedCost(String taskType,JsonNode input){
        if(input.has("estimatedCost"))return input.path("estimatedCost").asDouble(Double.NaN);
        if(!guarded(input)||!pipeline(input))return 0;
        String category=category(taskType);if("LOCAL".equals(category)||"LIPSYNC".equals(category))return 0;
        String estimateKey="PIPELINE_TEST_"+category+"_ESTIMATED_COST_CNY";
        String limitKey=switch(category){case "TTS"->"TEST_MAX_REAL_AUDIO_REQUESTS";default->"TEST_MAX_REAL_"+category+"_REQUESTS";};
        double categoryEstimate=requiredProperty(estimateKey),requests=limit(input,limitKey,0d);
        if(requests<=0)throw new WorkflowException("TEST_BUDGET_INVALID",limitKey+" 必须大于 0 才能计算单次预计成本");
        return categoryEstimate/requests;
    }

    private <T>T transaction(Supplier<T> work){return transactions==null?work.get():transactions.execute(status->work.get());}
    private Reservation load(String run){
        if(jdbc==null)return reservations.getOrDefault(run,Reservation.empty());
        return jdbc.query("SELECT document FROM \"test_budget_reservation\" WHERE run_id=?",(rs,n)->fromDocument(rs.getString(1)),run).stream().findFirst().orElse(Reservation.empty());
    }
    private void save(String run,Reservation value){
        if(jdbc==null){reservations.put(run,value);return;}
        String document=document(value).toString();
        int changed=jdbc.update("UPDATE \"test_budget_reservation\" SET document=?,updated_at=? WHERE run_id=?",document,OffsetDateTime.now(ZoneOffset.UTC),run);
        if(changed==0)try{jdbc.update("INSERT INTO \"test_budget_reservation\" (run_id,document,updated_at) VALUES (?,?,?)",run,document,OffsetDateTime.now(ZoneOffset.UTC));}
        catch(DuplicateKeyException race){jdbc.update("UPDATE \"test_budget_reservation\" SET document=?,updated_at=? WHERE run_id=?",document,OffsetDateTime.now(ZoneOffset.UTC),run);}
    }
    private ObjectNode document(Reservation value){return obj().put("plannedCost",value.plannedCost).put("reservedCost",value.reservedCost).put("actualCost",value.actualCost).put("wasteCost",value.wasteCost).put("llm",value.llm).put("vlm",value.vlm).put("keyframeQc",value.keyframeQc).put("videoQc",value.videoQc).put("image",value.images).put("video",value.videos).put("tts",value.tts).put("lipsync",value.lipsync);}
    private Reservation fromDocument(String json){try{JsonNode value=mapper.readTree(json);return new Reservation(value.path("plannedCost").asDouble(),value.path("reservedCost").asDouble(),value.path("actualCost").asDouble(),value.path("wasteCost").asDouble(),value.path("llm").asInt(),value.path("vlm").asInt(),value.path("keyframeQc").asInt(),value.path("videoQc").asInt(),value.path("image").asInt(),value.path("video").asInt(),value.path("tts").asInt(),value.path("lipsync").asInt());}catch(Exception error){throw new IllegalStateException("测试预算记录损坏",error);}}

    private boolean guarded(JsonNode input){return (input.path("testRun").asBoolean(false)||environment.getProperty("DRAMA_TEST_RUN",Boolean.class,false))&&!"mock".equalsIgnoreCase(environment.getProperty("drama.provider.mode","mock"));}
    private boolean pipeline(JsonNode input){return "PIPELINE".equalsIgnoreCase(input.path("testPhase").asText());}
    private String run(JsonNode input){return input.path("testRunId").asText(environment.getProperty("DRAMA_TEST_RUN_ID","default-canary"));}
    private String category(String type){if(Set.of("STORY","PREMISE","CORE","OUTLINE_BATCH","EPISODE_SCRIPT","STORY_QA","DIRECTOR_PLAN","SHOT_DETAIL").contains(type))return "LLM";if(Set.of("KEYFRAME_QC","VIDEO_QC").contains(type))return "VLM";if(Set.of("ASSET_IMAGE","STORYBOARD","KEYFRAME").contains(type))return "IMAGE";if("VIDEO".equals(type))return "VIDEO";if("TTS".equals(type))return "TTS";if("LIPSYNC".equals(type))return "LIPSYNC";return "LOCAL";}
    private int property(String key,int fallback){return environment.getProperty(key,Integer.class,fallback);}
    private double property(String key,double fallback){return environment.getProperty(key,Double.class,fallback);}
    private double requiredProperty(String key){String raw=environment.getProperty(key);if(raw==null||raw.isBlank())throw new WorkflowException("TEST_BUDGET_INVALID",key+" 缺失，禁止 Pipeline Canary 提交付费请求");try{double value=Double.parseDouble(raw);if(!Double.isFinite(value)||value<0)throw new NumberFormatException();return value;}catch(NumberFormatException error){throw new WorkflowException("TEST_BUDGET_INVALID",key+" 必须是非负有限数值");}}

    private int limit(JsonNode input,String key,int fallback){double value=limit(input,key,(double)fallback);return value>Integer.MAX_VALUE?-1:(int)value;}
    private double limit(JsonNode input,String key,double fallback){
        if(!"PIPELINE".equalsIgnoreCase(input.path("testPhase").asText()))return property(key,fallback);
        String name="PIPELINE_"+key,raw=environment.getProperty(name);if(raw==null||raw.isBlank())throw new WorkflowException("TEST_BUDGET_INVALID",name+" 缺失，禁止 Pipeline Canary 提交付费请求");
        try{double value=Double.parseDouble(raw);if(!Double.isFinite(value)||value<0)throw new NumberFormatException();return value;}catch(NumberFormatException error){throw new WorkflowException("TEST_BUDGET_INVALID",name+" 必须是非负有限数值");}
    }

    private record Reservation(double plannedCost,double reservedCost,double actualCost,double wasteCost,int llm,int vlm,int keyframeQc,int videoQc,int images,int videos,int tts,int lipsync){
        static Reservation empty(){return new Reservation(0,0,0,0,0,0,0,0,0,0,0,0);}
        Reservation reserve(String category,String taskType,double cost){return change(category,taskType,1,plannedCost+cost,reservedCost+cost,actualCost,wasteCost);}
        Reservation release(String category,String taskType,double cost){return change(category,taskType,-1,plannedCost,Math.max(0,reservedCost-cost),actualCost,wasteCost);}
        Reservation settle(double cost,boolean wasted){return new Reservation(plannedCost,Math.max(0,reservedCost-cost),actualCost+cost,wasteCost+(wasted?cost:0),llm,vlm,keyframeQc,videoQc,images,videos,tts,lipsync);}
        private Reservation change(String category,String taskType,int delta,double planned,double reserved,double actual,double waste){return new Reservation(planned,reserved,actual,waste,llm+("LLM".equals(category)?delta:0),vlm+("VLM".equals(category)?delta:0),keyframeQc+("KEYFRAME_QC".equals(taskType)?delta:0),videoQc+("VIDEO_QC".equals(taskType)?delta:0),images+("IMAGE".equals(category)?delta:0),videos+("VIDEO".equals(category)?delta:0),tts+("TTS".equals(category)?delta:0),lipsync+("LIPSYNC".equals(category)?delta:0));}
    }
}
