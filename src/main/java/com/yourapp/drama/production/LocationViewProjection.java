package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Canonical world-to-screen projection for every approved location reference view. */
public final class LocationViewProjection {
    private static final List<String> PERSPECTIVE_VIEWS=List.of("FRONT","REVERSE","SIDE");
    private static final List<String> ALL_VIEWS=List.of("LAYOUT","FRONT","REVERSE","SIDE");
    private LocationViewProjection(){}

    public static List<String> perspectiveViews(){return PERSPECTIVE_VIEWS;}
    public static List<String> allViews(){return ALL_VIEWS;}
    public static boolean isPerspective(String view){return PERSPECTIVE_VIEWS.contains(view);}

    public static ObjectNode describe(String view){
        ObjectNode camera=JsonNodeFactory.instance.objectNode().put("view",view).put("worldAxes","北为世界正北，东为世界正东，建筑不随相机旋转");
        return switch(view){
            case "LAYOUT"->camera.put("projection","正交俯视平面").put("position","场景正上方").put("pitchDegrees",-90).put("screenTop","北").put("screenRight","东").put("visibility","仅此视图可隐藏屋顶，用于检查平面关系");
            case "FRONT"->camera.put("projection","真实透视").put("position","主活动区北侧，朝世界南方；室内场景相机必须在室内").put("headingDegrees",180).put("heightMeters",1.5).put("pitchDegrees",0).put("screenLeft","东").put("screenRight","西").put("nearSide","北").put("farSide","南");
            case "REVERSE"->camera.put("projection","真实透视").put("position","主活动区南侧，朝世界北方；室内场景相机必须在室内").put("headingDegrees",0).put("heightMeters",1.5).put("pitchDegrees",0).put("screenLeft","西").put("screenRight","东").put("nearSide","南").put("farSide","北");
            case "SIDE"->camera.put("projection","真实透视").put("position","主活动区东侧，朝世界西方；室内场景相机必须在室内").put("headingDegrees",270).put("heightMeters",1.5).put("pitchDegrees",0).put("screenLeft","南").put("screenRight","北").put("nearSide","东").put("farSide","西");
            default->throw new IllegalArgumentException("未知场景视角："+view);
        };
    }
}
