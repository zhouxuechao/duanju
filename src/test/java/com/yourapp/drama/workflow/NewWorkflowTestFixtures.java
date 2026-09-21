package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import java.util.UUID;

/** Test-only current workflow fixture: confirmed core/script plus approved four-view asset sets. */
final class NewWorkflowTestFixtures {
    private NewWorkflowTestFixtures() {}

    static void install(DocumentStore store, AssetViewService assetViews, ObjectNode project, ObjectNode episode,
                        ObjectNode actor, ObjectNode look, ObjectNode location) {
        String projectId=id(project);
        String coreId=UUID.randomUUID().toString();
        ObjectNode core=store.create(STORY_DOCUMENT,obj().put("id",coreId).put("projectId",projectId).put("coreId",coreId).put("documentType","CORE")
                .put("reviewStatus","CONFIRMED").put("version",1).put("continuityHash","fixture-core")
                .<ObjectNode>set("content",obj().put("title","测试故事核心")).set("continuitySnapshot",obj().put("coreId",coreId).put("continuityHash","fixture-core").put("source","test")));
        ObjectNode currentProject=store.get(PROJECT,projectId);
        store.update(PROJECT,projectId,revision(currentProject),currentProject.deepCopy().put("activeStoryDocumentId",coreId));
        ObjectNode script=store.create(STORY_DOCUMENT,obj().put("projectId",projectId).put("coreId",coreId).put("documentType","EPISODE_SCRIPT")
                .put("reviewStatus","CONFIRMED").put("version",1).put("episodeNo",1).set("content",obj().put("title",text(episode,"name")).put("summary","测试剧本").put("script","测试场景中的完整剧本内容")));
        ObjectNode ep=store.get(EPISODE,id(episode));
        store.update(EPISODE,id(episode),revision(ep),ep.deepCopy().put("storyBibleId",coreId).put("storyDocumentId",id(script)).put("scriptReviewStatus","CONFIRMED").put("locked",true));
        if(actor!=null){ObjectNode current=store.get(CHARACTER,id(actor));ObjectNode next=current.deepCopy().put("storyBibleId",coreId);store.update(CHARACTER,id(actor),revision(current),next);}
        if(look!=null){ObjectNode current=store.get(CHARACTER_LOOK,id(look));ObjectNode next=current.deepCopy().put("storyBibleId",coreId);store.update(CHARACTER_LOOK,id(look),revision(current),next);approveSet(store,assetViews,projectId,id(look));}
        if(location!=null){ObjectNode current=store.get(LOCATION,id(location));ObjectNode next=current.deepCopy().put("storyBibleId",coreId).put("locationKey",current.path("locationKey").asText("location"));if(!next.path("locationBible").has("coordinateSystem"))next.set("locationBible",locationBible());store.update(LOCATION,id(location),revision(current),next);approveSet(store,assetViews,projectId,id(location));}
    }

    private static void approveSet(DocumentStore store, AssetViewService assetViews, String projectId, String assetId){
        ObjectNode request=obj();request.putArray("assetIds").add(assetId);assetViews.generate(projectId,request);
        for(ObjectNode job:store.list(GENERATION_JOB,projectId,null)){
            if("ASSET_IMAGE".equals(text(job,"type"))&&active(text(job,"status")))
                store.update(GENERATION_JOB,id(job),revision(job),job.deepCopy().put("status","CANCELLED").put("cancelReason","test fixture"));
        }
        ObjectNode master=null;
        for(ObjectNode view:store.list(ASSET_VIEW,projectId,assetId))if(!view.path("stale").asBoolean()&&view.path("master").asBoolean())master=view;
        for(ObjectNode view:store.list(ASSET_VIEW,projectId,assetId)){
            String compiler=switch(text(view,"assetKind")){case "CHARACTER_LOOK"->"4.1.0-character-turnaround";case "LOCATION"->"4.2.0-location-topology";default->"4.0.0-asset-reference";};
            ObjectNode next=view.deepCopy().put("status","APPROVED").put("approved",true).put("providerUrl","https://fixture.invalid/"+id(view)+".png").put("archiveStatus","READY").put("compilerVersion",compiler);
            if(master!=null&&!view.path("master").asBoolean())next.putArray("referenceViewIds").add(id(master));
            store.update(ASSET_VIEW,id(view),revision(view),next);
        }
    }
    private static boolean active(String status){return "QUEUED".equals(status)||"RUNNING".equals(status)||"RETRY_WAIT".equals(status);}
    private static ObjectNode locationBible(){ObjectNode b=obj().put("layout","测试场景固定布局");b.set("coordinateSystem",obj().put("origin","场景中心").put("northAxis","世界北").put("eastAxis","世界东").put("verticalAxis","向上"));b.set("dimensions",obj().put("width","八米").put("depth","十米").put("height","三米"));b.putArray("surfaces").add(obj().put("surfaceId","GROUND").put("name","地面").put("kind","GROUND").put("worldOrientation","HORIZONTAL").put("bounds","八米乘十米").put("material","石材").put("appearance","灰色"));b.putArray("fixedFeatures").add(obj().put("featureId","ANCHOR").put("name","固定地标").put("kind","LANDMARK").put("supportSurfaceId","GROUND").put("worldPosition","原点以北两米").put("size","一米").put("state","固定").put("appearance","深色"));b.putArray("spatialRelations").add(obj().put("subjectId","ANCHOR").put("relation","NORTH_OF").put("objectId","GROUND").put("distance","两米"));b.putArray("lightSources").add(obj().put("lightId","LIGHT").put("kind","AMBIENT").put("worldPosition","上方").put("direction","向下").put("colorTemperature","中性").put("appearance","柔光"));b.putArray("visualInvariants").add("固定地标不移动");b.putArray("prohibitedElements");return b;}
    static ArrayNode approvedViewIds(DocumentStore store, String projectId, String assetId){
        ArrayNode ids=obj().putArray("ids");
        for(ObjectNode view:store.list(ASSET_VIEW,projectId,assetId))if(view.path("approved").asBoolean()&&!view.path("stale").asBoolean())ids.add(id(view));
        return ids;
    }
}
