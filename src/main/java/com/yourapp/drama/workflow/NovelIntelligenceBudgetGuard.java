package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NovelIntelligenceBudgetGuard {
    private static final List<String> LIMITS=List.of("maxRequests","maxInputTokens","maxOutputTokens","maxEstimatedCost");
    public void validate(ObjectNode limits){for(String field:LIMITS){JsonNode value=limits.path(field);if(!value.isNumber()||!Double.isFinite(value.asDouble())||value.asDouble()<0)invalid("小说智能预算缺少或无效："+field);}if(limits.path("maxRequests").asLong()<1||limits.path("maxInputTokens").asLong()<1||limits.path("maxOutputTokens").asLong()<1)invalid("请求数和 Token 上限必须大于零");}
    public void assertCanReserve(ObjectNode run,String layer,long inputTokens,long outputTokens,double estimatedCost){validate(run);if(inputTokens<0||outputTokens<0||!Double.isFinite(estimatedCost)||estimatedCost<0)invalid("模型请求预算估算无效");if(run.path("usedRequests").asLong()+1>run.path("maxRequests").asLong()||run.path("usedInputTokens").asLong()+run.path("reservedInputTokens").asLong()+inputTokens>run.path("maxInputTokens").asLong()||run.path("usedOutputTokens").asLong()+run.path("reservedOutputTokens").asLong()+outputTokens>run.path("maxOutputTokens").asLong()||run.path("usedEstimatedCost").asDouble()+run.path("reservedEstimatedCost").asDouble()+estimatedCost>run.path("maxEstimatedCost").asDouble())exceeded("小说智能运行总预算不足");JsonNode layerLimit=run.path("layerLimits").path(layer),layerUsage=run.path("layerUsage").path(layer);if(layerLimit.isObject()){JsonNode max=layerLimit.path("maxRequests");if(!max.isIntegralNumber()||max.asLong()<0)invalid("分层请求预算无效："+layer);if(layerUsage.path("usedRequests").asLong()+1>max.asLong())exceeded("分层请求预算不足："+layer);}}
    private static void invalid(String message){throw new WorkflowException("NOVEL_INTELLIGENCE_BUDGET_INVALID",message);}
    private static void exceeded(String message){throw new WorkflowException("NOVEL_INTELLIGENCE_BUDGET_EXCEEDED",message);}
}
