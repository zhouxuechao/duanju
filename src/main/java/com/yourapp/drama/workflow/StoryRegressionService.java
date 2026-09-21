package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Deterministic story contract regression; it never calls a paid provider or mutates a project. */
@Service
public class StoryRegressionService {
    private final ObjectMapper mapper;private final StoryProfilePolicy profiles;private final EpisodeFormatResolver formats;private final ScreenwritingRuleResolver rules;
    public StoryRegressionService(ObjectMapper mapper,StoryProfilePolicy profiles,EpisodeFormatResolver formats,ScreenwritingRuleResolver rules){this.mapper=mapper;this.profiles=profiles;this.formats=formats;this.rules=rules;}
    public ObjectNode run(){return run(Path.of("evaluation"));}
    ObjectNode run(Path root){
        ArrayNode results=JsonNodeFactory.instance.arrayNode(),blocking=JsonNodeFactory.instance.arrayNode();int blockingFixtures=0;
        Path fixtures=root.resolve("fixtures"),baselines=root.resolve("baselines");
        try(var folders=Files.list(fixtures)){
            for(Path folder:folders.filter(Files::isDirectory).sorted().toList()){
                JsonNode fixture=mapper.readTree(folder.resolve("fixture.json").toFile()),baseline=mapper.readTree(baselines.resolve(folder.getFileName()+".json").toFile());
                ObjectNode project=Documents.obj().put("targetDuration",fixture.path("targetDuration").asInt()).put("episodeCount",fixture.path("episodeCount").asInt()).put("distributionProfile",fixture.path("distributionProfile").asText("GENERAL"));project.set("storyProfile",fixture.path("storyProfile").deepCopy());
                ObjectNode enriched=profiles.enrich(project),format=formats.resolve(enriched),pack=rules.resolve("CORE",enriched.path("storyProfile"),format,enriched.path("distributionProfile").asText());
                ArrayNode issues=JsonNodeFactory.instance.arrayNode();String expectedType=fixture.path("storyProfile").path("storyType").asText(),requiredRule=fixture.path("expectedInvariants").path("requiredRuleId").asText();
                if(!expectedType.equals(pack.path("storyType").asText()))issues.add("STORY_TYPE_CHANGED");
                if(!containsRule(pack.path("rules"),requiredRule))issues.add("REQUIRED_RULE_MISSING:"+requiredRule);
                String fragment=fixture.path("expectedInvariants").path("coreLoopMustContain").asText();if(!pack.path("storyTypeRule").path("coreLoop").asText().contains(fragment))issues.add("CORE_LOOP_REGRESSION:"+fragment);
                if(!baseline.path("storyType").asText().equals(pack.path("storyType").asText()))issues.add("BASELINE_STORY_TYPE_CHANGED");
                if(!baseline.path("episodeFormatFamily").asText().equals(format.path("family").asText()))issues.add("BASELINE_FORMAT_CHANGED");
                ObjectNode item=Documents.obj().put("fixtureId",folder.getFileName().toString()).put("storyType",expectedType).put("passed",issues.isEmpty()).put("blockingRegression",!issues.isEmpty()).put("rulePackFingerprint",pack.path("fingerprint").asText()).put("episodeFormatId",format.path("profileId").asText()).put("promptVersion","story-regression-v1").put("modelVersion","OFFLINE_DETERMINISTIC");item.set("issues",issues);item.set("current",Documents.obj().put("coreLoop",pack.path("storyTypeRule").path("coreLoop").asText()).put("episodeFormatFamily",format.path("family").asText()).put("sceneLimit",format.path("sceneLimit").asInt()).set("rules",pack.path("rules").deepCopy()));item.set("baseline",baseline.deepCopy());results.add(item);if(!issues.isEmpty()){blockingFixtures++;blocking.addAll(issues);}
            }
        }catch(Exception e){throw new IllegalStateException("Story Regression fixtures 无法读取",e);}
        return Documents.obj().put("status",blocking.isEmpty()?"PASS":"BLOCKED").put("fixtureCount",results.size()).put("passedCount",results.size()-blockingFixtures).put("blockingRegressionCount",blockingFixtures).put("blockingIssueCount",blocking.size()).put("executedAt",Instant.now().toString()).set("results",results);
    }
    private boolean containsRule(JsonNode values,String expected){for(JsonNode value:values)if(expected.equals(value.path("ruleId").asText()))return true;return false;}
}
