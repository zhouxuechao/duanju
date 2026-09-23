package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static com.yourapp.drama.workflow.Documents.obj;

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

    /** Converts only the authored intent and canonical assets into production input. */
    public ObjectNode productionProject(ObjectMapper mapper){
        ObjectNode project=obj().put("name",title).put("idea","林川在现代客厅查看手机；苏宁走近询问，林川把手机递给她，她看后得知一条重要消息。")
                .put("episodeCount",1).put("targetDuration",20).put("ratio","9:16").put("resolution","480p")
                .put("generationProfile","TEST").put("previsMode","SKIP").put("style","现代都市写实短剧").put("target","Pipeline Canary");
        ObjectNode storyProfile=obj().put("settingGenre","MODERN_URBAN").put("storyType","INFORMATION_REVEAL")
                .put("audience","GENERAL").put("intensity","MEDIUM").put("sourceMode","ORIGINAL_IDEA");
        storyProfile.putArray("tones").add("克制").add("悬念");storyProfile.putArray("tropes").add("信息揭示");project.set("storyProfile",storyProfile);
        project.set("directorStyleProfile",obj().put("visualRhythm","动作清楚，信息变化时切镜").put("averageShotLength",5)
                .put("cameraActivity","LOW").put("closeUpPreference",.5).put("reactionShotPreference",.7)
                .put("compositionStyle","先建立客厅轴线，再表现手机交接").put("tensionStyle","由询问、交接、阅读逐步揭示信息"));
        ArrayNode people=project.putArray("characters");
        for(Character character:characters){ObjectNode value=obj().put("characterKey",character.id()).put("name",character.name())
                .put("description",character.identity()).put("voiceId",character.voiceId());
            value.set("identityTraits",obj().put("age",character.age()+"岁").put("face",character.identity()).put("hair",character.hairstyle()).put("body","自然青年体型").put("voiceDialect","MANDARIN"));
            value.putArray("looks").add(obj().put("lookKey",character.id()+"-base-look").put("name","固定基础造型").put("description",character.wardrobe()+"；"+character.hairstyle()));people.add(value);}
        Location room=locations.getFirst();ObjectNode location=obj().put("locationKey",room.id()).put("name",room.name()).put("description",room.stableLayout());
        ObjectNode bible=obj().put("layout",room.stableLayout());bible.set("coordinateSystem",obj().put("origin","客厅中央地面").put("northAxis","朝落地窗为北").put("eastAxis","朝沙发为东").put("verticalAxis","垂直地面向上"));
        bible.set("dimensions",obj().put("width","东西六米").put("depth","南北八米").put("height","三米"));
        bible.putArray("surfaces").add(obj().put("surfaceId","FLOOR").put("name","客厅地面").put("kind","GROUND").put("worldOrientation","HORIZONTAL").put("bounds","东西六米、南北八米").put("material","浅色木地板").put("appearance","纹理稳定"))
                .add(obj().put("surfaceId","NORTH_WALL").put("name","客厅北墙").put("kind","WALL").put("worldOrientation","NORTH").put("bounds","宽六米、高三米").put("material","浅色乳胶漆").put("appearance","中央嵌落地窗"));
        bible.putArray("fixedFeatures").add(obj().put("featureId","NORTH_WINDOW").put("name","北侧落地窗").put("kind","WINDOW").put("supportSurfaceId","NORTH_WALL").put("worldPosition","北墙中央").put("size","宽三米、高两米").put("state","关闭").put("appearance","黑色窄框"));
        bible.putArray("spatialRelations").add(obj().put("subjectId","NORTH_WINDOW").put("relation","NORTH_OF").put("objectId","FLOOR").put("distance","固定在北墙中央并高于地面"));bible.putArray("lightSources").add(obj().put("lightId","WINDOW_LIGHT").put("kind","DAYLIGHT").put("worldPosition","北侧窗外").put("direction","由北向南").put("colorTemperature","中性日光").put("appearance","柔和侧逆光"));
        bible.putArray("visualInvariants").add("落地窗始终位于北墙").add("沙发始终位于东侧");bible.putArray("prohibitedElements").add("额外人物").add("额外手机");location.set("locationBible",bible);project.putArray("locations").add(location);
        Prop phone=props.getFirst();ObjectNode prop=obj().put("propKey",phone.id()).put("name",phone.name()).put("description",phone.visualIdentity()).put("state","开场由林川右手持有")
                .put("initialHolderCharacterKey",characters.getFirst().id()).put("transferToCharacterKey",characters.getLast().id());
        prop.set("propBible",obj().put("appearance",phone.visualIdentity()).put("scale","约十五厘米长").put("ownership","临时由人物持有并在第三镜完成交接"));project.putArray("props").add(prop);
        project.put("storyScript","现代客厅，北侧落地窗投下稳定日光。林川站在窗边，右手握着唯一一部黑色手机，先读完屏幕上的消息，没有移动位置。苏宁从南侧入口走近，在林川右侧停下，保持两人的视线轴线。苏宁问：\""+dialogues.getFirst().spokenText()+"\"林川转向苏宁，把手机从自己的右手递向她。苏宁用右手接住，林川确认松手，手机的持有人完成一次清楚的转移。苏宁低头读完消息，知道此前不知道的事实，再抬头看向林川说：\""+dialogues.getLast().spokenText()+"\"结尾时手机仍在苏宁右手，人物服装、发型、客厅布局和光线方向均未改变。");
        ObjectNode continuity=project.putObject("continuityIntent"),facts=continuity.putObject("storyFacts");
        facts.set("message-truth",obj().put("statement","手机中的重要消息真实有效").put("predicate","MESSAGE_IS_TRUE").put("subjectCharacterKey","character-a").put("objectPropKey","prop-phone").put("narrativeRole","REVEAL").put("validFromStoryTime",0).put("revealedAtStoryTime",15));
        ObjectNode knowledge=continuity.putObject("characterKnowledge");
        knowledge.putObject("character-a").putArray("message-truth").add(obj().put("knowledgeState","KNOWN").put("validFromStoryTime",0));
        knowledge.putObject("character-b").putArray("message-truth").add(obj().put("knowledgeState","UNKNOWN").put("validFromStoryTime",0).put("validToStoryTime",15)).add(obj().put("knowledgeState","KNOWN").put("validFromStoryTime",15));
        continuity.putObject("stateLedger").putObject("props").putArray("prop-phone")
                .add(obj().put("state","HELD").put("condition","INTACT").put("visible",true).put("carriedByCharacterKey","character-a").put("heldByHand","RIGHT").put("validFromStoryTime",0).put("validToStoryTime",15))
                .add(obj().put("state","HELD").put("condition","INTACT").put("visible",true).put("carriedByCharacterKey","character-b").put("heldByHand","RIGHT").put("validFromStoryTime",15));
        project.set("canaryDialogues",mapper.valueToTree(dialogues));
        return project;
    }

    public Dialogue dialogueFor(String shotId){return dialogues.stream().filter(value->value.shotId().equals(shotId)).findFirst().orElse(null);}
}
