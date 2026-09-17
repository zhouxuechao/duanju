package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Ten deliberately different directing fixtures. These are acceptance examples, not genre rules. */
class DirectorBenchmarkTest {
    private record Benchmark(String name,String pacing,List<String> beats,List<String> intents,List<String> sizes,
                             List<String> movements,List<Double> durations,boolean dialogue){}

    private static final List<Benchmark> CASES=List.of(
        scene("两人普通对话","NORMAL",true,"建立共同空间|试探对方态度|用沉默收束","ESTABLISH_SPACE,SHOW_ACTION,REVEAL_INFORMATION,SHOW_ACTION,SHOW_REACTION,EMPHASIZE_EMOTION,SHOW_ACTION,TRANSITION","WIDE,MEDIUM,OVER_THE_SHOULDER,MEDIUM_CLOSE,CLOSE_UP,OVER_THE_SHOULDER,CLOSE_UP,WIDE","STATIC,STATIC,STATIC,STATIC,DOLLY_IN,STATIC,STATIC,STATIC","3.2,2.8,3.1,2.5,2.2,3.0,2.4,3.3"),
        scene("三人冲突","CLIMAX",true,"三方建立对峙|争夺话语权|弱者作出选择","ESTABLISH_SPACE,SHOW_ACTION,SHOW_ACTION,REVEAL_INFORMATION,SHOW_REACTION,EMPHASIZE_EMOTION,SHOW_ACTION,PAYOFF","WIDE,MEDIUM_FULL,MEDIUM,CLOSE_UP,CLOSE_UP,OVER_THE_SHOULDER,MEDIUM_FULL,WIDE","STATIC,TRACK,STATIC,DOLLY_IN,STATIC,STATIC,TRACK,STATIC","2.6,2.2,2.0,2.4,1.8,2.5,2.1,2.8"),
        scene("悬疑发现线索","TENSION_BUILD",false,"隐藏线索所在空间|逐步限制观察范围|让观众先看见证据","ESTABLISH_SPACE,HIDE_INFORMATION,SHOW_ACTION,REVEAL_INFORMATION,SHOW_REACTION,EMPHASIZE_EMOTION,SHOW_ACTION,TRANSITION","EXTREME_WIDE,MEDIUM_FULL,OVER_THE_SHOULDER,INSERT,CLOSE_UP,POV,EXTREME_CLOSE_UP,WIDE","STATIC,STATIC,FOLLOW,DOLLY_IN,STATIC,STATIC,STATIC,DOLLY_OUT","3.5,2.8,2.6,2.2,1.9,2.5,2.0,3.0"),
        scene("恐怖逐渐揭示","TENSION_BUILD",false,"让黑暗保持未知|用局部动静升级威胁|只在结尾给出轮廓","ESTABLISH_SPACE,HIDE_INFORMATION,SHOW_REACTION,SHOW_ACTION,SHOW_REACTION,REVEAL_INFORMATION,EMPHASIZE_EMOTION,PAYOFF","EXTREME_WIDE,POV,CLOSE_UP,INSERT,CLOSE_UP,MEDIUM_FULL,EXTREME_CLOSE_UP,WIDE","STATIC,HANDHELD,STATIC,STATIC,DOLLY_IN,STATIC,STATIC,DOLLY_OUT","3.6,2.4,1.8,2.0,1.7,2.5,2.1,3.2"),
        scene("情绪哭戏","SLOW",true,"压住尚未说出的痛苦|说出无法挽回的事实|让情绪在停顿中落地","ESTABLISH_SPACE,EMPHASIZE_EMOTION,REVEAL_INFORMATION,SHOW_REACTION,SHOW_REACTION,EMPHASIZE_EMOTION,SHOW_ACTION,PAYOFF","MEDIUM_FULL,MEDIUM_CLOSE,CLOSE_UP,OVER_THE_SHOULDER,CLOSE_UP,EXTREME_CLOSE_UP,MEDIUM,WIDE","STATIC,STATIC,DOLLY_IN,STATIC,STATIC,STATIC,DOLLY_OUT,STATIC","4.2,3.8,4.0,3.2,3.6,4.4,3.5,4.6"),
        scene("动作追逐","FAST",false,"明确追逐方向|缩短距离并制造阻挡|以越过出口完成动作","ESTABLISH_SPACE,SHOW_ACTION,SHOW_ACTION,SHOW_ACTION,SHOW_REACTION,SHOW_ACTION,EMPHASIZE_EMOTION,PAYOFF","WIDE,FULL,MEDIUM_FULL,POV,CLOSE_UP,MEDIUM,FULL,WIDE","STATIC,TRACK,FOLLOW,HANDHELD,STATIC,TRACK,FOLLOW,STATIC","2.2,1.8,1.7,1.6,1.5,1.9,1.8,2.4"),
        scene("仙侠战斗前对峙","TENSION_BUILD",true,"建立阵营与法器方位|以言语试探底线|在出手前停住时间","ESTABLISH_SPACE,SHOW_ACTION,SHOW_ACTION,REVEAL_INFORMATION,SHOW_REACTION,INSERT,EMPHASIZE_EMOTION,PAYOFF","EXTREME_WIDE,FULL,OVER_THE_SHOULDER,CLOSE_UP,CLOSE_UP,INSERT,MEDIUM_FULL,WIDE","ORBIT,STATIC,STATIC,DOLLY_IN,STATIC,STATIC,DOLLY_OUT,STATIC","3.8,3.0,2.8,3.2,2.4,2.2,3.0,3.5"),
        scene("多人物群像","NORMAL",true,"建立群体内部层次|让冲突在人群中传播|由关键人物改变全场态度","ESTABLISH_SPACE,SHOW_ACTION,SHOW_REACTION,REVEAL_INFORMATION,SHOW_REACTION,SHOW_ACTION,EMPHASIZE_EMOTION,PAYOFF","EXTREME_WIDE,WIDE,MEDIUM,CLOSE_UP,MEDIUM_CLOSE,OVER_THE_SHOULDER,CLOSE_UP,WIDE","STATIC,PAN,STATIC,STATIC,STATIC,PAN,DOLLY_IN,STATIC","3.6,3.0,2.4,2.8,2.1,2.6,2.5,3.4"),
        scene("无对白动作","FAST",false,"交代目标与障碍|用连续动作改变道具位置|以结果反应结束","ESTABLISH_SPACE,SHOW_ACTION,SHOW_ACTION,INSERT,SHOW_REACTION,SHOW_ACTION,EMPHASIZE_EMOTION,PAYOFF","WIDE,FULL,MEDIUM_FULL,INSERT,CLOSE_UP,POV,FULL,EXTREME_WIDE","STATIC,FOLLOW,TRACK,STATIC,STATIC,HANDHELD,FOLLOW,STATIC","2.5,1.9,1.8,1.6,1.5,1.8,2.0,2.7"),
        scene("重要剧情反转","TENSION_BUILD",true,"确认观众原有判断|用证据推翻判断|把意义落在知情者反应上","ESTABLISH_SPACE,SHOW_ACTION,HIDE_INFORMATION,REVEAL_INFORMATION,SHOW_REACTION,INSERT,EMPHASIZE_EMOTION,PAYOFF","WIDE,MEDIUM,OVER_THE_SHOULDER,CLOSE_UP,EXTREME_CLOSE_UP,INSERT,CLOSE_UP,MEDIUM_FULL","STATIC,STATIC,STATIC,DOLLY_IN,STATIC,STATIC,DOLLY_OUT,STATIC","3.1,2.7,2.5,3.0,2.0,2.2,3.2,3.5")
    );

    private final ObjectMapper mapper=new ObjectMapper();
    private final DirectorPlanValidator sceneValidator=new DirectorPlanValidator();
    private final ShotComplexityValidator shotValidator=new ShotComplexityValidator();

    @Test void tenKindsHaveIndependentBeatsIntentBlockingEmotionDialogueAndReactionCoverage(){
        assertThat(CASES).hasSize(10);Set<String> signatures=new LinkedHashSet<>();
        for(Benchmark benchmark:CASES){
            ObjectNode scene=fixture(benchmark,8);assertThat(sceneValidator.validate(scene)).as(benchmark.name()).isEmpty();
            assertThat(scene.path("dramaticBeats")).hasSize(3);
            assertThat(scene.path("shots")).allSatisfy(shot->{
                assertThat(shot.path("directorIntent").asText()).isNotBlank();
                assertThat(shot.path("blocking").path("axis").asText()).isEqualTo("主行动轴");
                assertThat(shot.path("performancePlan").path("primaryAction").asText()).isNotBlank();
                assertThat(shotValidator.validate(shot)).isEmpty();
            });
            assertThat(scene.path("shots")).anySatisfy(shot->assertThat(shot.path("directorIntent").asText()).isEqualTo("SHOW_REACTION"));
            if(benchmark.dialogue()){
                assertThat(scene.path("shots")).anySatisfy(shot->assertThat(shot.path("dialogueOwner").asText()).isEqualTo("a"));
                assertThat(scene.path("shots")).anySatisfy(shot->{assertThat(shot.path("directorIntent").asText()).isEqualTo("SHOW_REACTION");assertThat(shot.path("focus").asText()).isEqualTo("b");assertThat(shot.path("dialogueOwner").asText()).isEqualTo("a");});
            }else assertThat(scene.path("shots")).allSatisfy(shot->assertThat(shot.path("dialogues")).isEmpty());
            signatures.add(signature(scene));
        }
        assertThat(signatures).as("十类场景必须有各自的剧情节拍和镜头策略").hasSize(CASES.size());
    }

    @Test void longSceneKeepsVarietyStableAxisChangingPaceReactionAndEmotionProgression(){
        ObjectNode scene=fixture(CASES.get(2),24);assertThat(scene.path("shots")).hasSize(24);assertThat(sceneValidator.validate(scene)).isEmpty();
        Set<String> sizes=new HashSet<>(),durations=new HashSet<>(),emotions=new HashSet<>();
        for(var shot:scene.path("shots")){sizes.add(shot.path("shotSize").asText());durations.add(shot.path("duration").asText());assertThat(shot.path("blocking").path("axisSide").asText()).isEqualTo("A_SIDE");}
        scene.path("dramaticBeats").forEach(beat->{emotions.add(beat.path("emotionBefore").asText());emotions.add(beat.path("emotionAfter").asText());});
        assertThat(sizes).hasSizeGreaterThanOrEqualTo(6);assertThat(durations).hasSizeGreaterThanOrEqualTo(4);assertThat(emotions).contains("平静","怀疑","紧张","恐惧");
    }

    private ObjectNode fixture(Benchmark benchmark,int count){
        ObjectNode root=mapper.createObjectNode();ObjectNode plan=root.putObject("directorPlan").put("scenePacing",benchmark.pacing()).put("shotRepetitionReason","").put("cameraStrategy",benchmark.beats().get(0)+"；"+benchmark.beats().get(2));
        plan.putObject("styleProfile").put("cameraActivity","HIGH").put("reactionShotPreference",0.75);
        ArrayNode beats=root.putArray("dramaticBeats");beats.add(beat("b1",benchmark.beats().get(0),"平静","怀疑",false));beats.add(beat("b2",benchmark.beats().get(1),"怀疑","紧张",true));beats.add(beat("b3",benchmark.beats().get(2),"紧张","恐惧",false));
        ArrayNode shots=root.putArray("shots");
        for(int i=0;i<count;i++){
            String beat=i<count/3?"b1":i<2*count/3?"b2":"b3";int pattern=i%8;String size=benchmark.sizes().get(pattern);String intent=pattern==4?"SHOW_REACTION":benchmark.intents().get(pattern);
            ObjectNode shot=mapper.createObjectNode().put("beatId",beat).put("directorIntent",intent).put("shotPurpose",benchmark.beats().get(Math.min(2,i*3/Math.max(1,count))))
                .put("shotSize",size).put("cameraAngle","EYE_LEVEL").put("cameraMovement",benchmark.movements().get(pattern)).put("duration",benchmark.durations().get(pattern))
                .put("subject",intent.equals("SHOW_REACTION")?"b":"a").put("focus",intent.equals("SHOW_REACTION")?"b":"a").put("dialogueOwner",benchmark.dialogue()&&pattern>=2&&pattern<=4?"a":"");
            shot.putArray("secondarySubjects").add("b");shot.putArray("characterIds").add("a").add("b");
            ObjectNode blocking=shot.putObject("blocking").put("axis","主行动轴").put("axisSide","A_SIDE").put("cameraWorldPosition","行动区南侧");blocking.putArray("characters");
            ObjectNode performance=shot.putObject("performancePlan").put("primaryAction",benchmark.beats().get(Math.min(2,i*3/Math.max(1,count)))).put("actionUnits",1);performance.putArray("propOperations");
            String required=Set.of("EXTREME_WIDE","WIDE","FULL","MEDIUM_FULL").contains(size)?"ACTION":size.equals("INSERT")?"PROP_DETAIL":"IDENTITY";
            shot.putObject("visibilityPlan").put("occlusion","NONE").put("requiredDetail",required);
            ArrayNode dialogue=shot.putArray("dialogues");if(benchmark.dialogue()&&pattern==2)dialogue.add(mapper.createObjectNode().put("displayText","我知道真相。"));shots.add(shot);
        }
        return root;
    }

    private ObjectNode beat(String id,String purpose,String before,String after,boolean reveal){
        ObjectNode beat=mapper.createObjectNode().put("beatId",id).put("beatPurpose",purpose).put("emotionBefore",before).put("emotionAfter",after).put("importance",reveal?"HIGH":"MEDIUM").put("informationReveal",reveal?"关键信息改变人物判断":"");beat.putArray("activeCharacters").add("a").add("b");return beat;
    }
    private String signature(JsonNode scene){return scene.path("directorPlan").path("scenePacing").asText()+"|"+scene.path("dramaticBeats").toString()+"|"+scene.path("shots").toString();}
    private static Benchmark scene(String name,String pacing,boolean dialogue,String beats,String intents,String sizes,String movements,String durations){
        return new Benchmark(name,pacing,List.of(beats.split("\\|")),List.of(intents.split(",")),List.of(sizes.split(",")),List.of(movements.split(",")),Arrays.stream(durations.split(",")).map(Double::valueOf).toList(),dialogue);
    }
}
