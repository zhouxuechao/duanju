package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.production.ProviderCapabilityRegistry;

import java.util.List;
import java.util.Map;

import static com.yourapp.drama.workflow.Documents.obj;

/** Immutable, non-billable description and safety checks for the two explicit live canary phases. */
public record LiveCanaryPlan(String phase,int imageRequests,int videoRequests,int audioRequests,int llmRequests,int vlmRequests,
                             int shots,int videoDurationSeconds) {
    public static LiveCanaryPlan provider(){return new LiveCanaryPlan("PROVIDER",1,1,0,0,0,1,5);}
    public static LiveCanaryPlan pipeline(){return new LiveCanaryPlan("PIPELINE",4,4,2,2,8,4,5);}

    public ObjectNode toJson(){
        ObjectNode value=obj().put("phase",phase).put("generationProfile","TEST")
                .put("imageModel",ProviderCapabilityRegistry.SEEDREAM_50).put("imageSize","2K").put("aspectRatioIntent","9:16")
                .put("videoModel",ProviderCapabilityRegistry.SEEDANCE_20_FAST).put("videoResolution","480p")
                .put("videoDurationSeconds",videoDurationSeconds).put("shots",shots).put("nativeAudio",false)
                .put("referenceRoute","SEEDREAM_PROVIDER_URL_DIRECT_FIRST_FRAME").put("automaticRetry",false)
                .put("secondPaidAttemptAutomatic",false);
        value.set("requests",obj().put("image",imageRequests).put("video",videoRequests).put("audio",audioRequests)
                .put("storyDirectorLlm",llmRequests).put("vlmQc",vlmRequests));
        return value;
    }

    public ObjectNode validateLive(Map<String,String> environment,List<? extends JsonNode> jobs){
        ArrayNode checks=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),blocking=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        check(checks,blocking,"LIVE_FLAG","true".equalsIgnoreCase(environment.get("RUN_LIVE_PROVIDER_CANARY")),"RUN_LIVE_PROVIDER_CANARY 必须显式为 true");
        check(checks,blocking,"RUNNER_PREFLIGHT","true".equalsIgnoreCase(environment.get("CANARY_RUNNER_PREFLIGHT_OK")),"真实 Canary 必须由 run-provider-canary.ps1 完成安全预检后启动");
        if("PIPELINE".equals(phase))check(checks,blocking,"PIPELINE_LIVE_FLAG","true".equalsIgnoreCase(environment.get("RUN_LIVE_PIPELINE_CANARY")),"RUN_LIVE_PIPELINE_CANARY 必须显式为 true");
        check(checks,blocking,"GENERATION_PROFILE","TEST".equalsIgnoreCase(value(environment,"CANARY_GENERATION_PROFILE","TEST")),"Canary 只能使用 TEST 档位");
        check(checks,blocking,"IMAGE_MODEL",ProviderCapabilityRegistry.SEEDREAM_50.equals(value(environment,"ARK_IMAGE_MODEL",ProviderCapabilityRegistry.SEEDREAM_50)),"图片模型必须为 Seedream 5.0 测试模型");
        check(checks,blocking,"IMAGE_SIZE","2K".equalsIgnoreCase(value(environment,"ARK_IMAGE_SIZE","2K")),"图片尺寸必须为 2K");
        check(checks,blocking,"VIDEO_MODEL",ProviderCapabilityRegistry.SEEDANCE_20_FAST.equals(value(environment,"ARK_VIDEO_MODEL",ProviderCapabilityRegistry.SEEDANCE_20_FAST)),"视频模型必须为 Seedance 2.0 Fast 测试模型");
        check(checks,blocking,"VIDEO_RESOLUTION","480p".equalsIgnoreCase(value(environment,"ARK_VIDEO_RESOLUTION","480p")),"视频分辨率必须为 480p");
        check(checks,blocking,"FINAL_DISABLED",!"true".equalsIgnoreCase(environment.get("CANARY_ENABLE_FINAL")),"Canary 禁止启用 FINAL");
        check(checks,blocking,"API_KEY_PRESENT",!value(environment,"ARK_API_KEY","").isBlank(),"ARK_API_KEY 只能从本机环境变量提供");
        int maxImages=integer(environment,"TEST_MAX_REAL_IMAGE_REQUESTS",imageRequests),maxVideos=integer(environment,"TEST_MAX_REAL_VIDEO_REQUESTS",videoRequests),maxAudio=integer(environment,"TEST_MAX_REAL_AUDIO_REQUESTS",audioRequests),maxLlm=integer(environment,"TEST_MAX_REAL_LLM_REQUESTS",llmRequests);
        double maxCost=decimal(environment,"TEST_MAX_COST_CNY",5);
        check(checks,blocking,"IMAGE_BUDGET",maxImages==imageRequests,"Phase "+phase+" 图片请求硬上限必须等于计划数 "+imageRequests);
        check(checks,blocking,"VIDEO_BUDGET",maxVideos==videoRequests,"Phase "+phase+" 视频请求硬上限必须等于计划数 "+videoRequests);
        check(checks,blocking,"AUDIO_BUDGET",maxAudio==audioRequests,"Phase "+phase+" 音频请求硬上限必须等于计划数 "+audioRequests);
        check(checks,blocking,"LLM_BUDGET",maxLlm==llmRequests,"Phase "+phase+" LLM 请求硬上限必须等于计划数 "+llmRequests);
        check(checks,blocking,"COST_BUDGET",Double.isFinite(maxCost)&&maxCost>0,"TEST_MAX_COST_CNY 必须是正有限数值");
        double imageEstimate=decimal(environment,"TEST_IMAGE_ESTIMATED_COST_CNY",maxCost/2),videoEstimate=decimal(environment,"TEST_VIDEO_ESTIMATED_COST_CNY",maxCost/2);
        check(checks,blocking,"PLANNED_COST",Double.isFinite(imageEstimate)&&imageEstimate>=0&&Double.isFinite(videoEstimate)&&videoEstimate>=0&&imageEstimate+videoEstimate<=maxCost,"图片与视频预计成本之和不能超过 TEST_MAX_COST_CNY");
        boolean unresolved=jobs.stream().anyMatch(job->"UNKNOWN".equalsIgnoreCase(job.path("status").asText())||job.path("submissionUncertain").asBoolean()||job.path("reconciliationRequired").asBoolean());
        check(checks,blocking,"NO_UNRESOLVED_PROVIDER_SUBMISSIONS",!unresolved,"存在 UNKNOWN 或待对账的 Provider 提交，禁止启动新 Canary");
        ObjectNode result=obj().put("ready",blocking.isEmpty()).put("phase",phase);result.set("checks",checks);result.set("blocking",blocking);result.set("plan",toJson());return result;
    }

    public void requireLiveFlags(Map<String,String> environment){
        if(!"true".equalsIgnoreCase(environment.get("RUN_LIVE_PROVIDER_CANARY")))throw new IllegalStateException("RUN_LIVE_PROVIDER_CANARY=true 才能启动真实 Provider Canary");
        if(!"true".equalsIgnoreCase(environment.get("CANARY_RUNNER_PREFLIGHT_OK")))throw new IllegalStateException("CANARY_RUNNER_PREFLIGHT_REQUIRED: 真实 Canary 必须由 run-provider-canary.ps1 完成安全预检后启动");
        if("PIPELINE".equals(phase)&&!"true".equalsIgnoreCase(environment.get("RUN_LIVE_PIPELINE_CANARY")))throw new IllegalStateException("RUN_LIVE_PIPELINE_CANARY=true 才能启动完整 Pipeline Canary");
    }

    private void check(ArrayNode checks,ArrayNode blocking,String code,boolean passed,String message){ObjectNode item=obj().put("code",code).put("passed",passed);if(!passed){item.put("message",message);blocking.add(code);}checks.add(item);}
    private String value(Map<String,String> values,String key,String fallback){String value=values.get(key);return value==null||value.isBlank()?fallback:value.trim();}
    private int integer(Map<String,String> values,String key,int fallback){try{return Integer.parseInt(value(values,key,Integer.toString(fallback)));}catch(RuntimeException error){return -1;}}
    private double decimal(Map<String,String> values,String key,double fallback){try{return Double.parseDouble(value(values,key,Double.toString(fallback)));}catch(RuntimeException error){return Double.NaN;}}
}
