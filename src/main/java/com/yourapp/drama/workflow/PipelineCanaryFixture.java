package com.yourapp.drama.workflow;

import java.util.List;
import java.util.Map;

/** Fixed, low-risk modern-city fixture used only to verify the Phase B production pipeline. */
public record PipelineCanaryFixture(
        String title,List<Character> characters,List<Location> locations,List<Prop> props,
        List<Dialogue> dialogues,List<ShotPlan> shots) {

    public record Character(String id,String name,String identity,String wardrobe,String hairstyle,int age,String voiceId) {}
    public record Location(String id,String name,String stableLayout,String ambience) {}
    public record Prop(String id,String name,String visualIdentity) {}
    public record Dialogue(String id,String shotId,String speakerId,String semanticText,String spokenText,String subtitleText) {}
    public record ShotPlan(int index,String shotId,String previousShotId,String continuitySnapshotId,String promptVersion,
                           String action,String camera,String propHolderBefore,String propHolderAfter,
                           Map<String,Boolean> knowledgeBefore,Map<String,Boolean> knowledgeAfter) {
        public ShotPlan {knowledgeBefore=Map.copyOf(knowledgeBefore);knowledgeAfter=Map.copyOf(knowledgeAfter);}
    }

    public PipelineCanaryFixture {
        characters=List.copyOf(characters);locations=List.copyOf(locations);props=List.copyOf(props);
        dialogues=List.copyOf(dialogues);shots=List.copyOf(shots);
        if(characters.size()!=2||locations.size()!=1||props.size()!=1||dialogues.size()!=2||shots.size()!=4)
            throw new IllegalArgumentException("Phase B fixture must remain 2 characters, 1 location, 1 prop, 2 dialogue lines and 4 shots");
    }

    public static PipelineCanaryFixture standard(){
        var characters=List.of(
                new Character("character-a","林川","年轻男性，身份特征稳定","深蓝色衬衫与灰色长裤","黑色短发",28,"canary-male"),
                new Character("character-b","苏宁","年轻女性，身份特征稳定","米色针织衫与深色长裙","黑色低马尾",27,"canary-female"));
        var location=List.of(new Location("location-living-room","现代客厅","落地窗在北墙，沙发在东侧，入口在南侧；布局和光线方向保持不变","安静室内底噪"));
        var props=List.of(new Prop("prop-phone","黑色手机","黑色直板手机，透明保护壳，镜头模组位置固定"));
        var dialogues=List.of(
                new Dialogue("dialogue-1","shot-2","character-b","苏宁询问发生了什么","发生什么事了？","发生什么事了？"),
                new Dialogue("dialogue-2","shot-4","character-b","苏宁确认已经理解手机中的消息","原来消息是真的。","原来消息是真的。"));
        Map<String,Boolean> onlyA=Map.of("character-a",true,"character-b",false),both=Map.of("character-a",true,"character-b",true);
        var shots=List.of(
                new ShotPlan(1,"shot-1","","continuity-1","pipeline-canary-v1","林川站在客厅窗边，用右手查看手机","中景，竖屏，固定机位","character-a","character-a",onlyA,onlyA),
                new ShotPlan(2,"shot-2","shot-1","continuity-2","pipeline-canary-v1","苏宁从入口走近林川并询问情况，手机仍在林川右手","双人中景，保持人物左右位置","character-a","character-a",onlyA,onlyA),
                new ShotPlan(3,"shot-3","shot-2","continuity-3","pipeline-canary-v1","林川把手机从右手递给苏宁，苏宁接住并看到消息","手部与双人近景，完整呈现交接","character-a","character-b",onlyA,both),
                new ShotPlan(4,"shot-4","shot-3","continuity-4","pipeline-canary-v1","苏宁右手持手机，看完后抬头回应林川","苏宁近景，保持客厅轴线和屏幕方向","character-b","character-b",both,both));
        return new PipelineCanaryFixture("手机里的消息",characters,location,props,dialogues,shots);
    }

    public Dialogue dialogueFor(String shotId){return dialogues.stream().filter(value->value.shotId().equals(shotId)).findFirst().orElse(null);}
}
