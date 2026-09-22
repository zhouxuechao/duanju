package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;
import static com.yourapp.drama.production.ProductionJson.*;
import static com.yourapp.drama.production.ProductionModels.*;

/** Deterministic validation for editorial intent before an FFmpeg plan is accepted. */
public final class EditingEngine {
    private static final Set<EditOperation> VIDEO_OPERATIONS=EnumSet.of(EditOperation.TRIM,EditOperation.CUT,EditOperation.REACTION_SHOT,EditOperation.INSERT_SHOT,EditOperation.PAUSE,EditOperation.CLIP_REPLACE);
    private static final Set<EditOperation> AUDIO_OPERATIONS=EnumSet.of(EditOperation.J_CUT,EditOperation.L_CUT,EditOperation.AUDIO_BRIDGE,EditOperation.DIALOGUE_GAP);
    private EditingEngine(){}

    public static ArrayNode videoOperations(JsonNode shot,long sourceIn,long sourceOut,long providerDuration){ArrayNode operations=JsonNodeFactory.instance.arrayNode().add(EditOperation.CUT.name());if(sourceIn>0||sourceOut<providerDuration)operations.add(EditOperation.TRIM.name());if("SHOW_REACTION".equalsIgnoreCase(text(shot,"directorIntent")))operations.add(EditOperation.REACTION_SHOT.name());if("INSERT".equalsIgnoreCase(text(shot,"shotSize"))||"INSERT".equalsIgnoreCase(text(shot,"directorIntent")))operations.add(EditOperation.INSERT_SHOT.name());return operations;}
    public static ArrayNode synchronizeVideoOperations(JsonNode item,long providerDuration){LinkedHashSet<EditOperation> operations=new LinkedHashSet<>();operations.add(EditOperation.CUT);if(item.path("sourceInMs").asLong()>0||providerDuration>0&&item.path("sourceOutMs").asLong()<providerDuration)operations.add(EditOperation.TRIM);JsonNode declared=item.path("editOperations");if(declared.isArray())for(JsonNode value:declared){EditOperation operation;try{operation=EditOperation.valueOf(value.asText().toUpperCase(Locale.ROOT));}catch(IllegalArgumentException error){throw new IllegalArgumentException("未知剪辑操作："+value.asText());}if(!VIDEO_OPERATIONS.contains(operation))throw new IllegalArgumentException(operation+" 不适用于 VIDEO 轨道");if(operation!=EditOperation.CUT&&operation!=EditOperation.TRIM)operations.add(operation);}ArrayNode result=JsonNodeFactory.instance.arrayNode();operations.forEach(operation->result.add(operation.name()));return result;}

    public static void validate(JsonNode items,List<Risk> risks){
        if(!items.isArray())return;
        Map<String,Clip> videoByShot=new HashMap<>();
        List<Long> pictureCuts=new ArrayList<>();
        List<Clip> dialogueClips=new ArrayList<>();
        int index=0;
        for(JsonNode item:items){
            if("VIDEO".equals(text(item,"track").toUpperCase(Locale.ROOT))){
                Clip picture=clip(item,index);if(!text(item,"shotId").isBlank())videoByShot.put(text(item,"shotId"),picture);if(picture.start>0)pictureCuts.add(picture.start);
            }
            if("DIALOGUE".equals(text(item,"track").toUpperCase(Locale.ROOT)))dialogueClips.add(clip(item,index));
            index++;
        }
        index=0;
        for(JsonNode item:items){
            String track=text(item,"track").toUpperCase(Locale.ROOT),path="items["+index+"]";
            for(EditOperation operation:operations(item,risks,path)){
                Set<EditOperation> allowed="VIDEO".equals(track)?VIDEO_OPERATIONS:AUDIO_OPERATIONS;
                if(!allowed.contains(operation)){error(risks,"EDIT_OPERATION_TRACK_INVALID",path+".editOperations",operation+" 不适用于 "+track+" 轨道");continue;}
                if(operation==EditOperation.J_CUT||operation==EditOperation.L_CUT){
                    Clip audio=clip(item,index),picture=videoByShot.get(text(item,"shotId"));
                    if(picture==null){error(risks,"EDIT_OPERATION_SHOT_REQUIRED",path+".shotId",operation+" 必须绑定有画面片段的来源镜头");continue;}
                    if(operation==EditOperation.J_CUT&&!(audio.start<picture.start&&audio.end()>picture.start))
                        error(risks,"J_CUT_POSITION_INVALID",path+".startMs","J-Cut 的声音必须先于所属镜头画面开始，并跨过该切点");
                    if(operation==EditOperation.L_CUT&&!(audio.start<picture.end()&&audio.end()>picture.end()))
                        error(risks,"L_CUT_POSITION_INVALID",path+".durationMs","L-Cut 的声音必须跨过所属镜头画面的结束切点");
                }
                if(operation==EditOperation.AUDIO_BRIDGE){Clip audio=clip(item,index);if(pictureCuts.stream().noneMatch(cut->audio.start<cut&&audio.end()>cut))error(risks,"AUDIO_BRIDGE_POSITION_INVALID",path+".durationMs","Audio Bridge 必须跨过至少一个画面切点");}
                if(operation==EditOperation.DIALOGUE_GAP){
                    if(!"DIALOGUE".equals(track)){error(risks,"EDIT_OPERATION_TRACK_INVALID",path+".editOperations","DIALOGUE_GAP 只适用于对白轨");continue;}
                    Clip dialogue=clip(item,index);long declared=item.path("gapBeforeMs").asLong(-1),previousEnd=dialogueClips.stream().filter(other->other.index!=dialogue.index&&other.end()<=dialogue.start).mapToLong(Clip::end).max().orElse(0),actual=dialogue.start-previousEnd;
                    if(declared<=0)error(risks,"DIALOGUE_GAP_DURATION_REQUIRED",path+".gapBeforeMs","Dialogue Gap 必须声明正数 gapBeforeMs");
                    else if(Math.abs(actual-declared)>100)error(risks,"DIALOGUE_GAP_MISMATCH",path+".gapBeforeMs","声明的 Dialogue Gap 与对白轨实际静默时长不一致");
                }
                if(operation==EditOperation.PAUSE){long pause=item.path("pauseDurationMs").asLong(-1),duration=item.path("durationMs").asLong(-1),sourceIn=item.path("sourceInMs").asLong(0),sourceOut=item.path("sourceOutMs").asLong(-1);if(pause<=0||pause>=duration)error(risks,"PAUSE_DURATION_INVALID",path+".pauseDurationMs","Pause 时长必须大于 0 且短于时间线片段");else if(sourceOut<0||sourceOut-sourceIn!=duration-pause)error(risks,"PAUSE_SOURCE_RANGE_INVALID",path+".sourceOutMs","Pause 片段的源媒体长度必须等于输出时长减去停帧时长");}
            }
            index++;
        }
    }

    private static Set<EditOperation> operations(JsonNode item,List<Risk> risks,String path){
        EnumSet<EditOperation> result=EnumSet.noneOf(EditOperation.class);JsonNode values=item.path("editOperations");
        if(values.isMissingNode()||values.isNull())return result;
        if(!values.isArray()){error(risks,"EDIT_OPERATIONS_INVALID",path+".editOperations","editOperations 必须是数组");return result;}
        for(JsonNode value:values)try{result.add(EditOperation.valueOf(value.asText().toUpperCase(Locale.ROOT)));}catch(IllegalArgumentException ignored){error(risks,"EDIT_OPERATION_UNKNOWN",path+".editOperations","未知剪辑操作："+value.asText());}
        return result;
    }
    public static boolean hasOperation(JsonNode item,EditOperation operation){JsonNode values=item.path("editOperations");if(!values.isArray())return false;for(JsonNode value:values)if(operation.name().equalsIgnoreCase(value.asText()))return true;return false;}
    private static Clip clip(JsonNode item,int index){long start=item.path("startMs").asLong(-1),duration=item.path("durationMs").asLong(-1);return new Clip(index,start,duration);}
    private record Clip(int index,long start,long duration){long end(){return start+duration;}}
}
