package com.yourapp.drama.production;
import java.util.*;
/** Chooses the smallest safe re-review scope from semantic dependency impact. */
public final class ChangeImpactAnalyzer {
 public enum Scope { LOCAL,DEPENDENT_RESOURCE,FULL_REVIEW }
 public record Result(Scope scope,Set<String> changedPaths,int dependentCount,String reason){}
 public Result analyze(String resourceType,Set<String> changedPaths,int dependentCount){String type=resourceType==null?"":resourceType.toUpperCase(Locale.ROOT);Set<String> paths=changedPaths==null?Set.of():Set.copyOf(changedPaths);boolean identity=paths.stream().anyMatch(p->p.matches("(?i).*(identity|face|storyFact|worldRule|timeline).*"));Scope scope;if(identity||Set.of("CORE","STORY_BIBLE").contains(type)||dependentCount>=25)scope=Scope.FULL_REVIEW;else if(Set.of("CHARACTER","LOOK","EPISODE","SCRIPT","LOCATION","PROP").contains(type)||dependentCount>1)scope=Scope.DEPENDENT_RESOURCE;else scope=Scope.LOCAL;return new Result(scope,paths,dependentCount,switch(scope){case LOCAL->"只复核当前资源";case DEPENDENT_RESOURCE->"复核语义依赖资源";case FULL_REVIEW->"身份、故事真相或大范围依赖发生变化";});}
}
