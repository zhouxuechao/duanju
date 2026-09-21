package com.yourapp.drama.production;
import java.util.Set;
public final class FramingVisibilityValidator {
 public enum Status { VISIBLE,OFFSCREEN,SHOT_SCALE_CONFLICT }
 public record Result(Status status,String recommendation){}
 public Result validate(String shotScale,String cameraPosition,String characterPosition,String requiredVisibleAction){boolean distant=characterPosition!=null&&(characterPosition.startsWith("FAR")||characterPosition.contains("ROOM_")&&!characterPosition.equals(cameraPosition));boolean detail=Set.of("MICRO_EXPRESSION","PROP_DETAIL","FACE_DETAIL").contains(requiredVisibleAction);if(distant&&detail&&Set.of("CLOSE_UP","EXTREME_CLOSE_UP","MEDIUM_CLOSE_UP").contains(shotScale))return new Result(Status.SHOT_SCALE_CONFLICT,"扩大景别、拆反应镜头或改为画外信息");if("OFFSCREEN".equals(characterPosition))return new Result(Status.OFFSCREEN,"使用画外声音或重新构图");return new Result(Status.VISIBLE,"");}
}
