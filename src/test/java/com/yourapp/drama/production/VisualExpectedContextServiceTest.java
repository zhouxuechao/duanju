package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VisualExpectedContextServiceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void extractsOnlyShotRelevantVisualConstraints(){
        ObjectNode context=mapper.createObjectNode().put("style","rural horror")
            .put("revisionFeedback","上一版把北墙牌位复制到了南门上，必须确认南门没有牌位");
        ObjectNode shot=context.putObject("shot").put("locationId","yard").put("shotSize","MEDIUM").put("cameraAngle","EYE_LEVEL").put("action","elder turns once");
        shot.put("directorIntent","SHOW_REACTION").put("shotPurpose","先看听者反应").put("subject","elder").put("cameraMovement","STATIC").put("eyeLine","向右").put("focus","眼睛");
        ObjectNode blocking=mapper.createObjectNode().put("cameraWorldPosition","老人右前方").put("axis","门与供桌轴线").put("axisSide","A_SIDE").put("doorWindowState","门关闭");
        blocking.putArray("characters").addObject().put("characterId","elder").put("worldPosition","门东侧").put("facing","朝南门").put("screenDirection","INTO_DEPTH").put("framePosition","画面中央")
            .put("visibleBodyPart","RIGHT_HAND").put("bodyFrameSide","OFFSCREEN_RIGHT").put("limbEntrySide","FRAME_RIGHT").put("contactPoint","右手握住铃柄");shot.set("blocking",blocking);
        shot.set("performancePlan",mapper.createObjectNode().put("microExpression","眼睑轻颤").put("gesture","右手握住铃柄"));shot.set("visibilityPlan",mapper.createObjectNode().put("occlusion","NONE").put("requiredDetail","PROP_DETAIL"));
        shot.set("cameraPlan",mapper.createObjectNode().put("position","north").put("lensMm",50));
        shot.putObject("referenceViews").put("yard","FRONT");
        shot.putArray("characterIds").add("elder");shot.putArray("propIds").add("bell");
        ObjectNode start=shot.putObject("startState");start.putObject("characters").putObject("elder").put("position","门东侧").put("lookDirection","朝南门").put("holding","bell");
        start.putObject("props").putObject("bell").put("holder","elder").put("position","右手胸前，握住铃柄").put("state","静止");
        context.putObject("characterStates").putObject("elder").putObject("state").put("clothing","black coat");
        context.putObject("locationState").putObject("state").put("door","closed");
        ObjectNode assets=context.putObject("assets");ObjectNode actor=assets.putArray("characters").addObject().put("id","elder").put("name","李满囤").put("providerUrl","https://signed.example/character-secret").put("generationJobId","job-1");actor.putObject("identityTraits").put("face","square");actor.putArray("approvedViews").addObject().put("url","data:image/png;base64,very-large-pixels");
        assets.putArray("looks").addObject().put("id","look").put("characterId","elder").put("description","black coat").put("archiveUrl","/api/media/look.png");
        assets.putArray("locations").addObject().put("id","yard").put("description","ancestral room");assets.putArray("props").addObject().put("id","bell").put("description","copper bell");
        context.putArray("storyFacts").addObject().put("statement","future truth must stay out");
        ObjectNode expected=new VisualExpectedContextService(mapper).build(context);
        assertThat(expected.path("requiredConstraints").path("characterIdentity").toString()).contains("李满囤","square");
        assertThat(expected.path("requiredConstraints").path("clothing").toString()).contains("black coat");
        assertThat(expected.path("requiredConstraints").path("location").toString()).contains("closed","ancestral room");
        assertThat(expected.path("requiredConstraints").path("props").toString()).contains("copper bell");
        assertThat(expected.path("requiredConstraints").path("composition").toString()).contains("MEDIUM","north");
        assertThat(expected.path("requiredConstraints").path("composition").path("worldToScreenProjection").path("screenLeft").asText()).isEqualTo("东");
        assertThat(expected.path("requiredConstraints").path("composition").path("worldToScreenProjection").path("screenRight").asText()).isEqualTo("西");
        assertThat(expected.path("requiredConstraints").path("composition").path("interactionGeometry").toString())
            .contains("RIGHT_HAND","门东侧","老人右前方","右手胸前");
        assertThat(expected.path("requiredConstraints").path("composition").path("surfaceTopology").toString())
            .contains("SURFACE_ASSIGNMENT","OPPOSITE_SURFACES","locationId");
        assertThat(expected.path("requiredConstraints").path("composition").path("surfaceTopology").path("visibilityMode").asText())
            .isEqualTo("PRESERVE_IF_VISIBLE");
        assertThat(expected.path("requiredConstraints").path("directorCompliance").toString()).contains("SHOW_REACTION","门与供桌轴线","眼睑轻颤","PROP_DETAIL");
        assertThat(expected.path("requiredConstraints").path("priorFailureToRecheck").asText()).contains("南门没有牌位");
        assertThat(expected.toString()).doesNotContain("future truth");
        assertThat(expected.toString()).doesNotContain("providerUrl","generationJobId","approvedViews","archiveUrl","signed.example","very-large-pixels");
    }
}
