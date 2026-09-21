package com.yourapp.drama.production;
import java.util.*;
/** Retrieves only abstract structural patterns; source-case plots are never exposed to generation prompts. */
public final class CasePatternRetriever {
 public record Pattern(String id,Set<String> storyTypes,Set<String> tropes,Set<String> audiences,Set<String> tones,Set<String> formats,String structure,String rhythm,String informationGap,String payoffMode,List<String> plot){}
 private static final List<Pattern> LIBRARY=List.of(
  pattern("FAIR_CLUE_REVERSAL","SUSPENSE,MYSTERY,SUSPENSE_MYSTERY","HIDDEN_IDENTITY,INVESTIGATION","GENERAL","DARK,TENSE","STANDARD,LONG","异常→可见线索→错误解释→矛盾证据→解释收缩→真相兑现","线索与反证交替，真相只在证据闭环后揭晓","观众可领先角色半步，角色知情范围严格按证据获得时间变化","旧线索获得新解释"),
  pattern("PRESSURE_PAYOFF_LADDER","REVENGE,COUNTERATTACK,IDENTITY_REVERSAL","REVENGE,HIDDEN_IDENTITY","GENERAL","INTENSE","MICRO,STANDARD","压力→有限反击→敌方升级→资源或证据增长→阶段兑现","短周期小兑现与长周期主兑现错开","观众知道主角的一项隐藏筹码，对手保有合理误判依据","兑现改变资源、关系或公众判断"),
  pattern("RELATIONSHIP_CHOICE","ROMANCE,SWEET_ROMANCE,FAMILY_ETHICS","SWEET_ROMANCE,FAMILY","GENERAL,FEMALE","WARM,ROMANTIC","MICRO,STANDARD,LONG","共同目标→具体照顾或冲突→必须选择→关系距离变化","亲密行为与阻力交替，每次甜点都留下关系后果","观众看见未说出口的在意，双方知识不完全对称","通过行动确认信任或边界"),
  pattern("COSTLY_GROWTH","GROWTH,ADVENTURE,SURVIVAL","TRAINING,SURVIVAL","GENERAL","HOPEFUL,INTENSE","STANDARD,LONG","缺陷→选择→代价→测试失败→新选择→能力与价值变化","训练和关系事件服务于下一次测试","观众知道旧模式会失败，人物在后果后才承认","新能力必须与价值选择同时兑现")
 );
 public List<Pattern> retrieve(String storyType,List<String> tropes,String audience,String tone,String episodeFormat,int limit){int bounded=Math.max(1,Math.min(3,limit));Set<String> wantedTropes=normalize(tropes);return LIBRARY.stream().map(p->Map.entry(p,score(p,storyType,wantedTropes,audience,tone,episodeFormat))).filter(e->e.getValue()>0).sorted(Comparator.<Map.Entry<Pattern,Integer>>comparingInt(Map.Entry::getValue).reversed().thenComparing(e->e.getKey().id())).limit(bounded).map(Map.Entry::getKey).toList();}
 private static int score(Pattern p,String type,Set<String>tropes,String audience,String tone,String format){int score=p.storyTypes.contains(n(type))?8:0;for(String t:tropes)if(p.tropes.contains(t))score+=4;if(p.audiences.contains(n(audience)))score+=2;if(p.tones.contains(n(tone)))score+=2;if(p.formats.contains(n(format)))score+=2;return score;}
 private static Pattern pattern(String id,String types,String tropes,String audiences,String tones,String formats,String structure,String rhythm,String gap,String payoff){return new Pattern(id,csv(types),csv(tropes),csv(audiences),csv(tones),csv(formats),structure,rhythm,gap,payoff,List.of());}
 private static Set<String> csv(String v){return new LinkedHashSet<>(Arrays.asList(v.split(",")));}private static Set<String> normalize(Collection<String>v){Set<String>r=new HashSet<>();if(v!=null)v.forEach(x->r.add(n(x)));return r;}private static String n(String v){return v==null?"":v.trim().toUpperCase(Locale.ROOT);}
}
