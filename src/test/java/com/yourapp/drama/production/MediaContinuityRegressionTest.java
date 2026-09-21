package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.yourapp.drama.workflow.Documents.obj;

class MediaContinuityRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContinuityEngine engine = new ContinuityEngine(mapper);

    private ObjectNode request() throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("""
            {"shot":{"shotId":"shot","duration":3,"relationToPrevious":"MATCH_CUT","difficulty":"B",
              "action":"帽顶先出现在门缝，接着面孔随着同一次前倾进入画面。","shotSize":"MEDIUM","cameraAngle":"EYE_LEVEL","cameraMovement":"STATIC",
              "characterIds":["actor"],"propIds":["bell"],"locationId":"yard","authorizedChanges":[
                {"path":"props.bell.position","reason":"同一次前倾动作中，人物把铃从腰侧抬到胸前"},
                {"path":"props.bell.state","reason":"人物完成摇铃动作后让铃停止发声"}],
              "startState":{"characters":{"actor":{"identityId":"actor","lookId":"look","position":"门东侧","holding":"bell","lookDirection":"门缝"}},
                "props":{"bell":{"holder":"actor","position":"右手胸前","state":"停止响铃"}}},
              "cameraPlan":{"position":"门北侧","height":"1.5米","distance":"2米","lensMm":50,"horizontalAngle":"南向","verticalAngle":"水平","subjectPlacement":"左侧","focusPoint":"眼睛","depthOfField":"浅","lightingDirection":"东窗"}},
             "previousState":{"characters":{"actor":{"identityId":"actor","lookId":"look","position":"门东侧","holding":"bell","lookDirection":"窗户"}},
                "props":{"bell":{"holder":"actor","position":"右手腰侧","state":"响铃"}}},
             "assets":{"characters":[{"id":"actor"}],"looks":[{"id":"look","characterId":"actor"}],"locations":[{"id":"yard"}],"props":[{"id":"bell"}]}}
            """);
        for (String collection : new String[]{"looks","locations","props"}) {
            var views = ((ObjectNode) request.path("assets").path(collection).get(0)).putArray("approvedViews");
            for (String view : collection.equals("locations")?new String[]{"FRONT","SIDE","REVERSE","LAYOUT"}:new String[]{"FRONT","SIDE","BACK","DETAIL"})
                views.addObject().put("view",view).put("setVersion",1).put("approved",true);
        }
        return request;
    }

    @Test void singleActionMayDescribeItsPhasesAndPerformanceMayChangeAcrossCuts() throws Exception {
        assertThat(engine.plan(request()).passed()).isTrue();
    }

    @Test void positionAndHolderCannotDriftDuringCutaways() throws Exception {
        ObjectNode request=request();
        ((ObjectNode)request.path("shot")).put("relationToPrevious","CUTAWAY");
        ((ObjectNode)request.path("shot").path("startState").path("characters").path("actor")).put("position","门西侧");
        assertThat(engine.plan(request).risks()).anyMatch(r->r.code().equals("STATE_CONFLICT")&&r.path().endsWith("position"));
        request=request();
        ((ObjectNode)request.path("shot").path("startState").path("props").path("bell")).put("holder","");
        assertThat(engine.plan(request).risks()).anyMatch(r->r.code().equals("STATE_CONFLICT")&&r.path().endsWith("holder"));
    }

    @Test void continuousActionMustInheritPerformanceAndMultipleActionsStillFail() throws Exception {
        ObjectNode request=request();
        ((ObjectNode)request.path("shot")).put("relationToPrevious","CONTINUOUS");
        assertThat(engine.plan(request).passed()).isFalse();
        request=request();
        ((ObjectNode)request.path("shot")).putArray("actions").add("起身").add("出门");
        assertThat(engine.plan(request).risks()).anyMatch(r->r.code().equals("MULTIPLE_ACTIONS"));
    }

    @Test void holdingStateMustReferenceAnExistingProp() throws Exception {
        ObjectNode request=request();
        ((ObjectNode)request.path("shot").path("startState").path("characters").path("actor")).put("holding","missing-prop");
        assertThat(engine.plan(request).risks()).anyMatch(r->r.code().equals("PROP_HOLDING_MISSING"));
    }

    @Test void continuityUsesTheSameGroupEstablishingExceptionAsShotComplexity() throws Exception {
        ObjectNode request=request(),shot=(ObjectNode)request.path("shot"),assets=(ObjectNode)request.path("assets");
        shot.put("shotSize","WIDE").put("directorIntent","ESTABLISH_SPACE");
        shot.putObject("visibilityPlan").put("occlusion","NONE").put("requiredDetail","ACTION").putArray("visibleFeatures").add("三位老人整体站位");
        for(String id:new String[]{"listener","keeper"}){
            shot.withArray("characterIds").add(id);
            ObjectNode state=obj().put("identityId",id).put("lookId",id+"-look").put("position","供桌前").put("holding","").put("lookDirection","院门");
            ((ObjectNode)shot.path("startState").path("characters")).set(id,state.deepCopy());
            ((ObjectNode)request.path("previousState").path("characters")).set(id,state.deepCopy());
            assets.withArray("characters").add(obj().put("id",id).put("baseLookId",id+"-look"));
            ObjectNode look=obj().put("id",id+"-look").put("characterId",id);var views=look.putArray("approvedViews");
            for(String view:new String[]{"FRONT","SIDE","BACK","DETAIL"})views.addObject().put("view",view).put("setVersion",1).put("approved",true);
            assets.withArray("looks").add(look);
        }
        assertThat(engine.plan(request).risks()).noneMatch(r->r.code().equals("TOO_MANY_VISIBLE_IDENTITIES"));
    }

    @Test void imagePromptCarriesVisualCanonWithoutMediaUrlsOrStoreMetadata() throws Exception {
        ObjectNode request=request();
        ((ObjectNode)request.path("shot")).putObject("referenceViews").put("look","FRONT").put("yard","FRONT").put("bell","FRONT");
        for(String collection:new String[]{"looks","locations","props"}) {
            ObjectNode asset=(ObjectNode)request.path("assets").path(collection).get(0);
            asset.put("description","不可丢失的固定视觉设定").put("createdAt","STORE_METADATA_SENTINEL");
            for(var view:asset.path("approvedViews"))((ObjectNode)view).put("id",asset.path("id").asText()+view.path("view").asText()).put("providerUrl","https://example.com/asset.png?signature=PRIVATE_REFERENCE_SENTINEL");
        }
        var compiled=new PromptCompiler(mapper,engine).compileImage(request);
        assertThat(compiled.prompt()).doesNotContain(request.path("shot").path("action").asText());
        assertThat(compiled.prompt()).contains("第0秒", "开口尺寸保持不变")
            .contains("位于摄影机后方或视锥外的固定设施不得错误出现在当前可见承载面")
            .contains("固定空间拓扑合同")
            .contains("不同承载面上的设施不得互换、复制或合并")
            .contains("浅景深或背景虚化只允许降低纹理清晰度")
            .contains("不得改变任何固定设施的所属承载面");
        assertThat(compiled.prompt()).contains("不可丢失的固定视觉设定").doesNotContain("PRIVATE_REFERENCE_SENTINEL","STORE_METADATA_SENTINEL","approvedViews");
        assertThat(compiled.references()).hasSize(4);
        assertThat(compiled.references()).anyMatch(ref->ref.path("role").asText().equals("LOCATION_LAYOUT"));
        assertThat(compiled.references().getFirst().path("url").asText()).contains("PRIVATE_REFERENCE_SENTINEL");
    }

    @Test void promptContextSelectsOnlyCurrentShotPropsFromLargeLedger() throws Exception {
        ObjectNode request=request();
        ObjectNode previous=(ObjectNode)request.path("previousState");
        ObjectNode props=(ObjectNode)previous.path("props");
        for(int i=0;i<200;i++)props.set("unused-"+i,obj().put("holder","").put("position","仓库角落").put("state","完好"));
        ((ObjectNode)request.path("shot")).putObject("referenceViews").put("look","FRONT").put("yard","FRONT").put("bell","FRONT");
        int n=0;for(var view:request.path("assets").path("looks").get(0).path("approvedViews"))((ObjectNode)view).put("id","look-view-"+(n++)).put("providerUrl","https://example.com/look.png");
        n=0;for(var view:request.path("assets").path("locations").get(0).path("approvedViews"))((ObjectNode)view).put("id","yard-view-"+(n++)).put("providerUrl","https://example.com/yard.png");
        n=0;for(var view:request.path("assets").path("props").get(0).path("approvedViews"))((ObjectNode)view).put("id","bell-view-"+(n++)).put("providerUrl","https://example.com/bell.png");
        var compiled=new PromptCompiler(mapper,new ContinuityEngine(mapper)).compileImage(request);
        assertThat(compiled.prompt()).doesNotContain("unused-199").contains("bell");
    }

    @Test void promptContextSelectsOnlyVisibleCharactersFromLargeLedger() throws Exception {
        ObjectNode request=request();
        ObjectNode previous=(ObjectNode)request.path("previousState");
        ObjectNode characters=(ObjectNode)previous.path("characters");
        for(int i=0;i<200;i++)characters.set("unused-character-"+i,obj().put("identityId","unused-character-"+i).put("lookId","look").put("position","画外").put("holding",""));
        ((ObjectNode)request.path("shot")).putObject("referenceViews").put("look","FRONT").put("yard","FRONT").put("bell","FRONT");
        int n=0;for(var view:request.path("assets").path("looks").get(0).path("approvedViews"))((ObjectNode)view).put("id","look-view-"+(n++)).put("providerUrl","https://example.com/look.png");
        n=0;for(var view:request.path("assets").path("locations").get(0).path("approvedViews"))((ObjectNode)view).put("id","yard-view-"+(n++)).put("providerUrl","https://example.com/yard.png");
        n=0;for(var view:request.path("assets").path("props").get(0).path("approvedViews"))((ObjectNode)view).put("id","bell-view-"+(n++)).put("providerUrl","https://example.com/bell.png");
        var compiled=new PromptCompiler(mapper,new ContinuityEngine(mapper)).compileImage(request);
        assertThat(compiled.prompt()).doesNotContain("unused-character-199").contains("actor");
    }

    @Test void overheadLayoutCannotBeThePrimaryPerspectiveReference() throws Exception {
        ObjectNode request=request();
        ((ObjectNode)request.path("shot")).putObject("referenceViews").put("look","FRONT").put("yard","LAYOUT").put("bell","FRONT");
        assertThatThrownBy(()->new PromptCompiler(mapper,new ContinuityEngine(mapper)).compileImage(request))
            .hasMessageContaining("LAYOUT").hasMessageContaining("主场景参考");
    }

    @Test void promptLabelsPerspectiveAndLayoutReferencesWithDifferentAuthority() throws Exception {
        ObjectNode request=request();
        ((ObjectNode)request.path("shot")).putObject("referenceViews").put("look","FRONT").put("yard","FRONT").put("bell","FRONT");
        int n=0;for(var view:request.path("assets").path("looks").get(0).path("approvedViews"))((ObjectNode)view).put("id","look-role-view-"+(n++)).put("providerUrl","https://example.com/look.png");
        n=0;for(var view:request.path("assets").path("locations").get(0).path("approvedViews"))((ObjectNode)view).put("id","yard-role-view-"+(n++)).put("providerUrl","https://example.com/yard.png");
        n=0;for(var view:request.path("assets").path("props").get(0).path("approvedViews"))((ObjectNode)view).put("id","bell-role-view-"+(n++)).put("providerUrl","https://example.com/bell.png");
        var compiled=new PromptCompiler(mapper,new ContinuityEngine(mapper)).compileImage(request);
        assertThat(compiled.compilerVersion()).isEqualTo("4.0.0");
        assertThat(compiled.prompt()).contains("当前机位透视主参考","俯视空间辅助，仅用于世界坐标定位，不可照抄为当前画面");
        assertThat(compiled.references()).extracting(ref->ref.path("role").asText())
            .containsSubsequence("LOCATION","LOCATION_LAYOUT");
    }

    @Test void propDetailShotSendsTheApprovedPropAsThePrimaryVisualReference() throws Exception {
        ObjectNode request=request(),shot=(ObjectNode)request.path("shot");
        shot.put("shotSize","EXTREME_CLOSE_UP").put("subject","持物手部").put("focus","道具与手指接触点");
        shot.putObject("visibilityPlan").put("occlusion","NONE").put("requiredDetail","PROP_DETAIL").putArray("visibleFeatures").add("道具完整结构");
        shot.putObject("referenceViews").put("look","FRONT").put("yard","SIDE").put("bell","FRONT");
        int n=0;for(var view:request.path("assets").path("looks").get(0).path("approvedViews"))((ObjectNode)view).put("id","look-priority-"+(n++)).put("providerUrl","https://example.com/look.png");
        n=0;for(var view:request.path("assets").path("locations").get(0).path("approvedViews"))((ObjectNode)view).put("id","yard-priority-"+(n++)).put("providerUrl","https://example.com/yard.png");
        n=0;for(var view:request.path("assets").path("props").get(0).path("approvedViews"))((ObjectNode)view).put("id","bell-priority-"+(n++)).put("providerUrl","https://example.com/bell.png");
        var compiled=new PromptCompiler(mapper,new ContinuityEngine(mapper)).compileImage(request);
        assertThat(compiled.compilerVersion()).isEqualTo("4.0.0");
        assertThat(compiled.references()).extracting(ref->ref.path("role").asText())
            .containsExactly("PROP","LOCATION","LOCATION_LAYOUT","CHARACTER_LOOK");
        assertThat(compiled.prompt()).contains("图1=","道具形制参考");
    }

    @Test void heldPropDetailProjectsWorldPositionBodySideAndGripAsOneContract() throws Exception {
        ObjectNode request=request(),shot=(ObjectNode)request.path("shot");
        shot.put("shotSize","EXTREME_CLOSE_UP").put("subject","人物持铃的右手").put("focus","右手与木柄接触点");
        shot.putObject("visibilityPlan").put("occlusion","NONE").put("requiredDetail","PROP_DETAIL").putArray("visibleFeatures").add("右手握住木柄");
        shot.putObject("blocking").put("cameraWorldPosition","人物右前方0.3米").put("axis","人物—门轴线").put("axisSide","A_SIDE").put("axisChangeReason","").put("keyObjectPositions","铃在人物右手").put("doorWindowState","门关闭")
            .putArray("characters").addObject().put("characterId","actor").put("worldPosition","门东侧").put("facing","朝南门").put("screenDirection","INTO_DEPTH").put("framePosition","画面中心").put("eyeLineTarget","铃柄")
            .put("visibleBodyPart","RIGHT_HAND").put("bodyFrameSide","OFFSCREEN_RIGHT").put("limbEntrySide","FRAME_RIGHT").put("contactPoint","右手握住木柄中段");
        shot.putObject("performancePlan").put("primaryAction","保持静止").put("microExpression","").put("bodyLanguage","身体仍在门东侧").put("gaze","看向门").put("gesture","右手从身体所在方向伸入并握住木柄").put("actionUnits",1).putArray("propOperations").add("右手握住木柄");
        ((ObjectNode)shot.path("startState").path("props").path("bell")).put("position","人物右手胸前，手掌握住木柄");
        ((ObjectNode)shot.path("startState").path("characters").path("actor")).put("position","门东侧").put("lookDirection","朝南门");
        shot.putObject("referenceViews").put("look","FRONT").put("yard","SIDE").put("bell","FRONT");
        for(String collection:new String[]{"looks","locations","props"}){
            int n=0;for(var view:request.path("assets").path(collection).get(0).path("approvedViews"))
                ((ObjectNode)view).put("id",collection+"-geometry-"+(n++)).put("providerUrl","https://example.com/"+collection+".png");
        }

        var compiled=new PromptCompiler(mapper,new ContinuityEngine(mapper)).compileImage(request);

        assertThat(compiled.prompt()).contains("世界到画面的投影","持物人体与接触几何（不可镜像）")
            .contains("\"anatomicalSide\":\"RIGHT_HAND\"")
            .contains("\"worldPosition\":\"门东侧\"")
            .contains("\"cameraWorldPosition\":\"人物右前方0.3米\"")
            .contains("可见肢体必须从持物者身体投影所在方向自然连接");
    }
}
