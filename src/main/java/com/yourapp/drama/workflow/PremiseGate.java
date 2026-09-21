package com.yourapp.drama.workflow;
import com.fasterxml.jackson.databind.JsonNode;
/** Deterministic decision layer between premise analysis and core generation. */
public final class PremiseGate {
 public enum Decision { PASS,BLOCK_REVIEW,OVERRIDE }
 public record Result(Decision decision,String reason,String reviewer){}
 public Result evaluate(JsonNode premise){return premise.path("viable").asBoolean(false)?new Result(Decision.PASS,"",""):new Result(Decision.BLOCK_REVIEW,premise.path("capacityRisk").asText("前提容量需要人工处理"),"");}
 public Result override(String reason,String reviewer){if(reason==null||reason.isBlank())throw new IllegalArgumentException("强制继续必须填写原因");if(reviewer==null||reviewer.isBlank())throw new IllegalArgumentException("强制继续必须记录审核人");return new Result(Decision.OVERRIDE,reason.trim(),reviewer.trim());}
}
