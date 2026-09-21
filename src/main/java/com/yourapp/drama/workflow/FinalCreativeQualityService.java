package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.yourapp.drama.workflow.Documents.obj;

/** Produces an explicit creative release report; it never mutates edit decisions. */
@Service
public class FinalCreativeQualityService {
    public static final List<String> METRICS=List.of(
        "characterConsistency","wardrobeConsistency","propContinuity","positionContinuity","actionContinuity",
        "screenDirectionConsistency","spatialConsistency","lightingConsistency","editingRhythm",
        "dialogueQuality","bgmFit","sfxAccuracy","subtitleAccuracy","hook","midHook","cliffhanger");

    public ObjectNode evaluate(JsonNode input){
        ObjectNode report=obj();ArrayNode blocking=report.putArray("BLOCKING"),warnings=report.putArray("WARNING"),info=report.putArray("INFO"),repairs=report.putArray("RepairPlan"),timecodes=report.putArray("timecodes");
        ObjectNode scores=report.putObject("scores");
        for(String metric:METRICS){int score=input.path(metric).asInt(0);if(score<0||score>5)throw new IllegalArgumentException(metric+" 必须在 0 到 5 之间");scores.put(metric,score);report.put(metric,score);if(score==0)continue;if(score<=2){ObjectNode issue=issue(metric.toUpperCase(Locale.ROOT)+"_LOW","BLOCKING",metric,score,metric+" 未达到发布要求");blocking.add(issue);repairs.add(repair(issue,"修复 "+metric+" 后重新进行创作质检"));}else if(score==3)warnings.add(issue(metric.toUpperCase(Locale.ROOT)+"_REVIEW","WARNING",metric,score,metric+" 需要人工复看"));else info.add(issue(metric.toUpperCase(Locale.ROOT)+"_PASS","INFO",metric,score,metric+" 已通过"));}
        for(JsonNode supplied:input.path("issues")){String severity=supplied.path("severity").asText("INFO").toUpperCase(Locale.ROOT);if(!Set.of("BLOCKING","WARNING","INFO").contains(severity))throw new IllegalArgumentException("Creative QA severity 只支持 BLOCKING、WARNING 或 INFO");ObjectNode issue=supplied.deepCopy();issue.put("severity",severity);((ArrayNode)report.path(severity)).add(issue);if(supplied.has("startMs")||supplied.has("endMs")){ObjectNode time=obj().put("code",supplied.path("code").asText("CREATIVE_ISSUE")).put("startMs",supplied.path("startMs").asLong()).put("endMs",supplied.path("endMs").asLong());timecodes.add(time);}String repair=supplied.path("repair").asText();if(!repair.isBlank())repairs.add(obj().put("code",supplied.path("code").asText("CREATIVE_ISSUE")).put("action",repair).put("startMs",supplied.path("startMs").asLong()).put("endMs",supplied.path("endMs").asLong()));}
        report.put("passed",blocking.isEmpty()).put("timelineMutated",false);
        return report;
    }
    private ObjectNode issue(String code,String severity,String metric,int score,String message){return obj().put("code",code).put("severity",severity).put("metric",metric).put("score",score).put("message",message);}
    private ObjectNode repair(ObjectNode issue,String action){return obj().put("code",issue.path("code").asText()).put("action",action);}
}
