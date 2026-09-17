package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.production.LocationViewProjection;
import com.yourapp.drama.storage.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Independent, reviewed reference sets. Dramatic keyframes never become identity anchors. */
@Service
public class AssetViewService {
    private final DocumentStore store;
    private final JobService jobs;
    private final ImageGenerator images;
    private final MediaStorage storage;
    private final ProviderMediaFetcher fetcher;
    private static final Set<String> METADATA=Set.of("id","projectId","parentId","revision","createdAt","updatedAt","referenceImageUrl","referenceSetVersion","referenceStatus","provider","providerStatus","providerAssetId","sourceType","identityLocked","locked","stale","requiredViews","archiveUrl","providerUrl","baseLookId");
    public AssetViewService(DocumentStore store,JobService jobs,ImageGenerator images,MediaStorage storage,ProviderMediaFetcher fetcher){this.store=store;this.jobs=jobs;this.images=images;this.storage=storage;this.fetcher=fetcher;}

    public ObjectNode generate(String projectId,ObjectNode request){return store.transaction(()->{
        ObjectNode project=store.getForUpdate(PROJECT,projectId),core=currentCore(project);
        Set<String> requested=new HashSet<>();request.path("assetIds").forEach(v->requested.add(v.asText()));
        if(!requested.isEmpty())for(ObjectNode look:store.list(CHARACTER_LOOK,projectId,null))if(requested.contains(id(look))){
            String base=text(store.get(CHARACTER,required(look,"characterId")),"baseLookId");if(!base.isBlank())requested.add(base);
        }
        List<ObjectNode> assets=new ArrayList<>();for(ResourceKind kind:List.of(CHARACTER_LOOK,LOCATION,PROP))
            for(ObjectNode asset:store.list(kind,projectId,null))if(id(core).equals(text(asset,"storyBibleId"))&&!asset.path("stale").asBoolean()&&(requested.isEmpty()||requested.contains(id(asset))))assets.add(asset);
        if(!requested.isEmpty()&&!assets.stream().map(Documents::id).collect(java.util.stream.Collectors.toSet()).containsAll(requested))throw new WorkflowException("ASSET_NOT_CURRENT","请选择当前故事版本中的人物定妆、场景或道具");
        if(assets.isEmpty())throw new WorkflowException("ASSETS_REQUIRED","请先确认整季核心故事，生成角色定妆、场景和道具设定");
        ObjectNode result=obj();ArrayNode list=result.putArray("views");
        for(ObjectNode asset:assets){List<ObjectNode> existing=currentViews(projectId,id(core),id(asset));
            if(!existing.isEmpty()){existing.forEach(list::add);continue;}
            String kind=asset.hasNonNull("characterId")?"CHARACTER_LOOK":asset.hasNonNull("locationKey")?"LOCATION":"PROP";
            int version=store.list(ASSET_VIEW,projectId,id(asset)).stream().mapToInt(v->v.path("setVersion").asInt()).max().orElse(0)+1;
            List<ObjectNode> set=createSet(asset,kind,version,source(asset,kind));
            set.forEach(v->list.add(store.get(ASSET_VIEW,id(v))));
        }
        for(ObjectNode view:store.list(ASSET_VIEW,projectId,null))if(id(core).equals(text(view,"coreId"))&&!view.path("stale").asBoolean()&&view.path("master").asBoolean()&&"PLANNED".equals(text(view,"status"))&&masterCanStart(view))schedule(view);
        ArrayNode refreshed=result.putArray("views");for(JsonNode view:list)refreshed.add(store.get(ASSET_VIEW,id(view)));return result;
    });}

    public ObjectNode approve(String viewId,ObjectNode request){return store.transaction(()->{
        ObjectNode view=store.getForUpdate(ASSET_VIEW,viewId);current(view);
        if(view.path("approved").asBoolean())return view;
        checkRevision(view,request);
        if(!"REVIEW".equals(text(view,"status")))throw new WorkflowException("VIEW_REVIEW_REQUIRED","参考图生成完成后才能确认");
        if(text(view,"providerUrl").isBlank())throw new WorkflowException("IMAGE_REQUIRED","没有生成图片，不能确认");
        for(JsonNode ref:view.path("referenceViewIds")){
            ObjectNode master=store.get(ASSET_VIEW,ref.asText());current(master);
            if(!master.path("approved").asBoolean())throw new WorkflowException("MASTER_REVIEW_REQUIRED","请先确认本套主参考图");
        }
        ObjectNode saved=store.update(ASSET_VIEW,viewId,revision(view),view.deepCopy().put("status","APPROVED").put("approved",true).put("approvedAt",Instant.now().toString()).put("reviewNote",text(request,"reviewNote")));
        if(saved.path("master").asBoolean())for(ObjectNode other:currentViews(project(saved),text(saved,"coreId"),text(saved,"assetId")))
            if("PLANNED".equals(text(other,"status")))schedule(other);
        if(saved.path("master").asBoolean()&&"CHARACTER_LOOK".equals(text(saved,"assetKind")))for(ObjectNode other:store.list(ASSET_VIEW,project(saved),null))
            if(!other.path("stale").asBoolean()&&other.path("master").asBoolean()&&text(saved,"characterId").equals(text(other,"characterId"))&&"PLANNED".equals(text(other,"status"))&&masterCanStart(other))schedule(other);
        updateAssetReadiness(saved);return saved;
    });}

    public ObjectNode reject(String viewId,ObjectNode request){return store.transaction(()->{
        ObjectNode view=store.getForUpdate(ASSET_VIEW,viewId);current(view);checkRevision(view,request);
        if(!Set.of("REVIEW","APPROVED").contains(text(view,"status")))throw new WorkflowException("VIEW_REVIEW_REQUIRED","仅已生成的参考图可以退回");
        if(view.path("master").asBoolean())for(ObjectNode other:currentViews(project(view),text(view,"coreId"),text(view,"assetId")))
            if(!id(other).equals(viewId)){cancel(other);store.update(ASSET_VIEW,id(other),revision(other),other.deepCopy().put("stale",true).put("approved",false).put("status","STALE"));}
        invalidateDependents(project(view),Set.of(viewId));
        ObjectNode saved=store.update(ASSET_VIEW,viewId,revision(view),view.deepCopy().put("status","REJECTED").put("approved",false).put("reviewNote",text(request,"note")));
        updateAssetReadiness(saved);return saved;
    });}

    /** An explicit user action creates a new set version. It never silently repeats an uncertain submission. */
    public ObjectNode regenerate(String viewId,ObjectNode request){return store.transaction(()->{
        ObjectNode old=store.getForUpdate(ASSET_VIEW,viewId);currentCore(store.get(PROJECT,project(old)));checkRevision(old,request);
        if(old.path("stale").asBoolean())throw new WorkflowException("STALE_ASSET_VIEW","请从当前参考图版本发起重新生成");
        if(!text(old,"generationJobId").isBlank()){
            ObjectNode job=store.get(GENERATION_JOB,text(old,"generationJobId"));
            if(job.path("submissionUncertain").asBoolean())throw new WorkflowException("SUBMISSION_UNCERTAIN","服务商可能已接受请求，请先核对这张图片的请求记录，避免重复提交");
            if(Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(text(job,"status")))throw new WorkflowException("GENERATION_ACTIVE","这张参考图仍在生成中");
        }
        ObjectNode asset=asset(old);ObjectNode snapshot=source(asset,text(old,"assetKind"));
        boolean resetAll=old.path("master").asBoolean()||!hash(snapshot).equals(text(old,"sourceHash"));
        List<ObjectNode> previous=store.list(ASSET_VIEW,project(old),text(old,"assetId")).stream().filter(v->v.path("setVersion").asInt()==old.path("setVersion").asInt()).toList();
        for(ObjectNode other:previous)if(!text(other,"generationJobId").isBlank()&&"RUNNING".equals(text(store.get(GENERATION_JOB,text(other,"generationJobId")),"status")))
            throw new WorkflowException("GENERATION_ACTIVE","本套素材还有视图正在生成，请等待后再替换版本，避免重复付费");
        Set<String> invalidIds=new HashSet<>();for(ObjectNode v:previous){invalidIds.add(id(v));cancel(v);store.update(ASSET_VIEW,id(v),revision(v),v.deepCopy().put("stale",true).put("status","STALE"));}
        invalidateDependents(project(old),invalidIds);
        List<ObjectNode> next=createSet(asset,text(old,"assetKind"),old.path("setVersion").asInt()+1,snapshot);
        String newMasterId=id(next.stream().filter(v->v.path("master").asBoolean()).findFirst().orElseThrow());
        ObjectNode replacement=null;
        for(ObjectNode v:next){
            if(resetAll){if(v.path("master").asBoolean())replacement=v;continue;}
            if(text(v,"view").equals(text(old,"view"))){replacement=v;continue;}
            Optional<ObjectNode> prior=previous.stream().filter(p->text(p,"view").equals(text(v,"view"))).findFirst();
            if(prior.isPresent()&&prior.get().path("approved").asBoolean()){
                ObjectNode copy=v.deepCopy();for(String field:List.of("providerUrl","providerUrlExpiresAt","providerRequestId","archiveUrl","archiveKey","archiveContentType","archiveStatus","simulated","model","approvedAt"))if(prior.get().has(field))copy.set(field,prior.get().get(field));
                copy.put("approved",true).put("status","APPROVED").put("reusedFromViewId",id(prior.get()));
                if(!copy.path("master").asBoolean())copy.putArray("referenceViewIds").add(newMasterId);
                store.update(ASSET_VIEW,id(v),revision(v),copy);
            }
        }
        if(replacement==null)throw new IllegalStateException("No replacement view");
        ObjectNode scheduled=schedule(replacement);
        if(!resetAll)for(ObjectNode pending:currentViews(project(old),text(old,"coreId"),text(old,"assetId")))
            if("PLANNED".equals(text(pending,"status")))schedule(pending);
        return scheduled;
    });}

    public void process(ObjectNode job){
        ObjectNode view=store.get(ASSET_VIEW,required(job.path("inputSnapshot"),"assetViewId"));current(view);
        if(!id(job).equals(text(view,"generationJobId")))throw new WorkflowException("STALE_ASSET_JOB","此任务已被新的参考图版本替代");
        List<String> references=new ArrayList<>();for(JsonNode ref:job.path("inputSnapshot").path("referenceViewIds")){
            ObjectNode source=store.get(ASSET_VIEW,ref.asText());current(source);
            if(!source.path("approved").asBoolean())throw new WorkflowException("MASTER_REVIEW_REQUIRED","主参考图尚未确认");
            references.add(referenceUrl(source));
        }
        ImageGenerator.ImageResult result=images.generate(new ImageGenerator.ImageRequest(required(job.path("inputSnapshot"),"prompt"),references,Map.of("size","2K","watermark",false)));
        jobs.mutate(id(job),j->{j.put("providerRequestId",result.requestId()).put("model",result.model()).put("simulated",result.simulated());});
        ObjectNode saved=store.transaction(()->{
            ObjectNode now=store.getForUpdate(ASSET_VIEW,id(view));boolean stale=now.path("stale").asBoolean()||!isCurrent(now);
            ObjectNode next=now.deepCopy().put("providerUrl",result.providerUrl()).put("providerRequestId",result.requestId()).put("model",result.model()).put("simulated",result.simulated()).put("approved",false).put("status",stale?"STALE":"REVIEW").put("stale",stale);
            if(result.expiresAt()!=null)next.put("providerUrlExpiresAt",result.expiresAt().toString());
            return store.update(ASSET_VIEW,id(now),revision(now),next);
        });
        if(!result.simulated())saved=archive(id(saved));
        jobs.succeed(id(job),obj().put("assetViewId",id(saved)).put("status",text(saved,"status")).put("awaitingReview",!saved.path("stale").asBoolean()));
    }

    public ObjectNode archive(String viewId){
        ObjectNode view=store.get(ASSET_VIEW,viewId);if(!text(view,"archiveKey").isBlank())return view;
        try(InputStream input=fetcher.open(required(view,"providerUrl"))){
            byte[] bytes=input.readNBytes(20_000_001);if(bytes.length>20_000_000)throw new IllegalArgumentException("参考图超过 20 MB");
            String mime=imageMime(bytes),extension=mime.equals("image/jpeg")?"jpg":mime.equals("image/webp")?"webp":"png";
            String key="asset-views/"+project(view)+"/"+id(view)+"."+extension;String url=storage.put(key,new ByteArrayInputStream(bytes),mime);
            return mutate(viewId,v->v.put("archiveKey",key).put("archiveUrl",url).put("archiveContentType",mime).put("archiveStatus","READY"));
        }catch(Exception failure){return mutate(viewId,v->v.put("archiveStatus","FAILED").put("archiveFailureReason","参考图已生成，归档失败；可重试归档，无需重新调用图片模型"));}
    }

    public void syncFailure(ObjectNode job){
        String viewId=text(job.path("inputSnapshot"),"assetViewId");if(viewId.isBlank())return;
        store.transaction(()->{ObjectNode view=store.getForUpdate(ASSET_VIEW,viewId);
            if(!id(job).equals(text(view,"generationJobId"))||view.path("approved").asBoolean())return null;
            ObjectNode next=view.deepCopy().put("status",view.path("stale").asBoolean()?"STALE":"FAILED").put("failureCode",text(job,"failureCode")).put("failureReason",text(job,"failureReason"));
            if(job.has("providerRequestId"))next.set("providerRequestId",job.get("providerRequestId"));next.put("submissionUncertain",job.path("submissionUncertain").asBoolean());
            store.update(ASSET_VIEW,viewId,revision(view),next);return null;});
    }

    public List<ObjectNode> approvedReferences(String projectId,String coreId,String assetId){
        List<ObjectNode> views=currentViews(projectId,coreId,assetId);
        if(views.isEmpty()||views.size()!=viewsFor(text(views.getFirst(),"assetKind")).size()||views.stream().anyMatch(v->!v.path("approved").asBoolean()||!isCurrent(v)))return List.of();
        return views;
    }
    public void requireReady(String projectId,String coreId,List<String> assetIds){for(String assetId:new LinkedHashSet<>(assetIds))if(approvedReferences(projectId,coreId,assetId).isEmpty())throw new WorkflowException("ASSET_VIEWS_REVIEW_REQUIRED","本镜头使用的素材多视图尚未全部确认，或素材设定已更新；请先完成素材审查");}
    public String referenceUrl(ObjectNode view){
        current(view);
        if(!expired(view)&&!text(view,"providerUrl").isBlank())return text(view,"providerUrl");
        if(!text(view,"archiveKey").isBlank())try(InputStream input=storage.open(text(view,"archiveKey"))){
            byte[] bytes=input.readNBytes(20_000_001);if(bytes.length>20_000_000)throw new IllegalArgumentException("参考图超过 20 MB");
            return "data:"+imageMime(bytes)+";base64,"+Base64.getEncoder().encodeToString(bytes);
        }catch(IOException error){throw new UncheckedIOException(error);}
        throw new WorkflowException("ASSET_REFERENCE_EXPIRED","素材参考图链接已到期且归档不可用，请先重试归档或重新生成该素材");
    }
    public void invalidateAsset(String projectId,String assetId){store.transaction(()->{
        Set<String> ids=new HashSet<>();for(ObjectNode view:store.list(ASSET_VIEW,projectId,assetId))if(!view.path("stale").asBoolean()){
            ids.add(id(view));cancel(view);store.update(ASSET_VIEW,id(view),revision(view),view.deepCopy().put("stale",true).put("status","STALE"));}
        invalidateDependents(projectId,ids);return null;});}

    private List<ObjectNode> createSet(ObjectNode asset,String kind,int version,ObjectNode snapshot){
        List<ObjectNode> result=new ArrayList<>();for(String angle:viewsFor(kind)){
            ObjectNode view=obj().put("projectId",project(asset)).put("parentId",id(asset)).put("coreId",text(asset,"storyBibleId")).put("assetKind",kind).put("assetId",id(asset)).put("assetName",text(asset,"name")).put("setVersion",version).put("view",angle).put("master",angle.equals(masterFor(kind))).put("status","PLANNED").put("approved",false).put("stale",false).put("sourceHash",hash(snapshot));
            if(asset.hasNonNull("characterId"))view.put("characterId",text(asset,"characterId"));view.set("sourceSnapshot",snapshot.deepCopy());view.putArray("referenceViewIds");result.add(store.create(ASSET_VIEW,view));
        }return result;
    }
    private ObjectNode schedule(ObjectNode view){
        ObjectNode current=store.get(ASSET_VIEW,id(view));ArrayNode references=obj().putArray("ids");
        if(!current.path("master").asBoolean()){
            ObjectNode master=currentViews(project(current),text(current,"coreId"),text(current,"assetId")).stream().filter(v->v.path("master").asBoolean()&&v.path("approved").asBoolean()).findFirst().orElseThrow(()->new WorkflowException("MASTER_REVIEW_REQUIRED","请先确认主参考图"));references.add(id(master));
        }else if(!baselineLook(current).isBlank()){
            ObjectNode baseline=currentViews(project(current),text(current,"coreId"),baselineLook(current)).stream().filter(v->v.path("master").asBoolean()&&v.path("approved").asBoolean()).findFirst().orElseThrow(()->new WorkflowException("IDENTITY_ANCHOR_REQUIRED","请先确认这个演员的基础定妆主图，后续服装才能保持同一张脸"));references.add(id(baseline));
        }
        ObjectNode input=obj().put("assetViewId",id(current)).put("coreId",text(current,"coreId")).put("sourceHash",text(current,"sourceHash")).put("prompt",prompt(current,!references.isEmpty()));input.set("sourceSnapshot",current.path("sourceSnapshot").deepCopy());input.set("referenceViewIds",references);
        if("LOCATION".equals(text(current,"assetKind")))input.set("viewCamera",locationCamera(text(current,"view")));
        ObjectNode job=jobs.enqueue(project(current),null,"ASSET_IMAGE",input,"asset-view:"+id(current));jobs.mutate(id(job),j->j.put("maxAttempts",1));
        ObjectNode next=current.deepCopy().put("status","GENERATING").put("generationJobId",id(job));next.set("referenceViewIds",references.deepCopy());return store.update(ASSET_VIEW,id(current),revision(current),next);
    }
    private boolean masterCanStart(ObjectNode view){String base=baselineLook(view);return base.isBlank()||currentViews(project(view),text(view,"coreId"),base).stream().anyMatch(v->v.path("master").asBoolean()&&v.path("approved").asBoolean()&&isCurrent(v));}
    private String baselineLook(ObjectNode view){if(!"CHARACTER_LOOK".equals(text(view,"assetKind")))return "";String base=text(store.get(CHARACTER,required(view,"characterId")),"baseLookId");return base.equals(text(view,"assetId"))?"":base;}
    private String prompt(ObjectNode view,boolean references){
        String angle=switch(text(view,"view")){case "FRONT"->"正面，按世界坐标的正前方";case "LEFT"->"左侧面，沿人物/物件左侧世界轴线观察";case "RIGHT"->"右侧面，沿人物右侧世界轴线观察";case "BACK"->"背面，沿背部世界轴线观察";case "LAYOUT"->"正上方俯视平面布局，标出入口、出口和固定地标相对位置";case "REVERSE"->"与正面机位相对的反向空间视角，不能翻转布局";case "SIDE"->"场景侧向视角，沿固定空间轴线保留深度";case "SCALE"->"道具比例视角，完整物件旁放无文字一米比例杆";default->throw new IllegalArgumentException("未知参考视角");};
        if("LOCATION".equals(text(view,"assetKind")))angle=locationCamera(text(view,"view")).toString();
        String skill=loadPrompt(view.path("assetKind").asText());
        String metadata="\\n本次执行参数（供模型理解，不要在图片中显示，也不要返回 JSON）：assetKind="+text(view,"assetKind")+"；assetId="+text(view,"assetId")+"；view="+text(view,"view")+"；sourceSnapshot="+view.path("sourceSnapshot")+"；referenceViewIds="+view.path("referenceViewIds")+"。\\n";
        String anchor=switch(text(view,"assetKind")){case "CHARACTER_LOOK"->"这是角色身份与定妆参考图。角色身份锚点与服装定妆分开：不得把手持道具、场景、剧情动作或临时姿势固化到人物主图；基础身份主图只确认脸、发型、体态和比例，服装主图只在此身份上确认本套衣服。";case "LOCATION"->"这是空场景参考图。所有入口、出口、门窗、道路、井、树和固定地标按同一世界坐标保留；不同视角只能改变相机位置，不能旋转、镜像或移动建筑。";default->"这是单件道具参考图。保持轮廓、尺寸比例、材质、重量感、颜色、纹理、刻痕、磨损和当前状态；不要出现手、人、第二件同类道具或文字。";};
        String refs=references?"输入参考图是已经确认的同一素材锚点；严格保持身份、几何、材质和纹理，只改变观察视角。":"这是本套多视图的主参考，生成后需人工确认后才能成为其它视角锚点。";
        return skill+metadata+"本视角："+angle+"。"+anchor+refs+"。只生成一张单视角制作参考图，不要拼图、四宫格、重复主体、文字水印或 JSON。";
    }
    private ObjectNode locationCamera(String view){
        return LocationViewProjection.describe(view);
    }
    private String loadPrompt(String kind){
        String folder=switch(kind){case "CHARACTER_LOOK"->"09-character-design";case "LOCATION"->"10-location-design";case "PROP"->"11-prop-design";default->throw new IllegalArgumentException("未知素材类型："+kind);};
        try(var stream=new org.springframework.core.io.ClassPathResource("development-skills/"+folder+"/prompt.md").getInputStream()){return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        catch(Exception e){throw new IllegalStateException("素材多视图技能加载失败："+folder,e);}
    }
    private ObjectNode currentCore(ObjectNode project){
        if(text(project,"activeStoryDocumentId").isBlank())throw new WorkflowException("CORE_REVIEW_REQUIRED","请先确认整季核心故事");
        ObjectNode core=store.get(STORY_DOCUMENT,text(project,"activeStoryDocumentId"));
        if(!"CONFIRMED".equals(text(core,"reviewStatus"))||core.path("stale").asBoolean())throw new WorkflowException("CORE_REVIEW_REQUIRED","请先确认当前整季核心故事");return core;
    }
    private ObjectNode asset(ObjectNode view){return store.get(ResourceKind.valueOf(text(view,"assetKind")),text(view,"assetId"));}
    private ObjectNode source(ObjectNode asset,String kind){ObjectNode result=obj();result.set("asset",description(asset));if("CHARACTER_LOOK".equals(kind))result.set("character",description(store.get(CHARACTER,required(asset,"characterId"))));return result;}
    private ObjectNode description(ObjectNode value){ObjectNode result=value.deepCopy();METADATA.forEach(result::remove);return result;}
    private boolean isCurrent(ObjectNode view){try{current(view);return true;}catch(WorkflowException ignored){return false;}}
    private void current(ObjectNode view){
        if(view.path("stale").asBoolean()||!text(currentCore(store.get(PROJECT,project(view))),"id").equals(text(view,"coreId")))throw new WorkflowException("STALE_ASSET_VIEW","此参考图不属于当前已确认的故事版本");
        if(!hash(source(asset(view),text(view,"assetKind"))).equals(text(view,"sourceHash")))throw new WorkflowException("ASSET_DESCRIPTION_CHANGED","素材设定已修改，请重新生成并确认多视图后再生产");
    }
    private List<ObjectNode> currentViews(String projectId,String coreId,String assetId){return store.list(ASSET_VIEW,projectId,assetId).stream().filter(v->coreId.equals(text(v,"coreId"))&&!v.path("stale").asBoolean()).toList();}
    private void checkRevision(ObjectNode view,ObjectNode request){if(request.path("revision").asLong(-1)!=revision(view))throw new RevisionConflictException(ASSET_VIEW,id(view));}
    private void cancel(ObjectNode view){if(!text(view,"generationJobId").isBlank())jobs.cancel(text(view,"generationJobId"));}
    private ObjectNode mutate(String viewId,java.util.function.Consumer<ObjectNode> action){return store.transaction(()->{ObjectNode view=store.getForUpdate(ASSET_VIEW,viewId);ObjectNode next=view.deepCopy();action.accept(next);return store.update(ASSET_VIEW,viewId,revision(view),next);});}
    private void updateAssetReadiness(ObjectNode view){ObjectNode asset=asset(view);boolean ready=currentViews(project(view),text(view,"coreId"),id(asset)).stream().filter(v->v.path("approved").asBoolean()).count()==viewsFor(text(view,"assetKind")).size();ObjectNode next=asset.deepCopy().put("referenceStatus",ready?"APPROVED":"REVIEW").put("referenceSetVersion",view.path("setVersion").asInt());store.update(ResourceKind.valueOf(text(view,"assetKind")),id(asset),revision(asset),next);}
    private void invalidateDependents(String projectId,Set<String> viewIds){
        if(viewIds.isEmpty())return;Set<String> invalidViews=new HashSet<>(viewIds);boolean expanded;
        do{expanded=false;for(ObjectNode view:store.list(ASSET_VIEW,projectId,null))if(!view.path("stale").asBoolean()&&containsReference(view.path("referenceViewIds"),invalidViews)){
            for(ObjectNode related:currentViews(projectId,text(view,"coreId"),text(view,"assetId"))){if(invalidViews.add(id(related)))expanded=true;cancel(related);store.update(ASSET_VIEW,id(related),revision(related),related.deepCopy().put("stale",true).put("status","STALE"));}
        }}while(expanded);
        viewIds=invalidViews;Set<String> shots=new HashSet<>();
        for(ObjectNode shot:store.list(SHOT,projectId,null))if(containsReference(shot,viewIds)){shots.add(id(shot));store.update(SHOT,id(shot),revision(shot),shot.deepCopy().put("assetReferencesStale",true).put("assetReferenceFailure","引用的素材视图已更新，请重新生成该镜头"));}
        for(ResourceKind kind:List.of(STORYBOARD,KEYFRAME,VIDEO_TAKE))for(ObjectNode record:store.list(kind,projectId,null))if(shots.contains(text(record,"shotId"))||containsReference(record,viewIds)){shots.add(text(record,"shotId"));store.update(kind,id(record),revision(record),record.deepCopy().put("assetReferencesStale",true));}
        Set<String> timelineIds=new HashSet<>();
        for(ObjectNode item:store.list(TIMELINE_ITEM,projectId,null))if(shots.contains(text(item,"shotId"))||containsReference(item,viewIds)){
            timelineIds.add(text(item,"timelineId"));store.update(TIMELINE_ITEM,id(item),revision(item),item.deepCopy().put("assetReferencesStale",true));}
        for(ObjectNode timeline:store.list(TIMELINE,projectId,null))if(timelineIds.contains(id(timeline))||containsReference(timeline,viewIds))store.update(TIMELINE,id(timeline),revision(timeline),timeline.deepCopy().put("assetReferencesStale",true));
        for(ObjectNode job:store.list(GENERATION_JOB,projectId,null))if(containsReference(job.path("inputSnapshot"),viewIds)&&Set.of("QUEUED","RETRY_WAIT").contains(text(job,"status")))jobs.cancel(id(job));
    }
    private boolean containsReference(JsonNode value,Set<String> ids){if(value.isTextual())return ids.contains(value.asText());if(value.isContainerNode())for(JsonNode child:value)if(containsReference(child,ids))return true;return false;}
    private static List<String> viewsFor(String kind){return switch(kind){case "CHARACTER_LOOK"->List.of("FRONT","LEFT","RIGHT","BACK");case "LOCATION"->LocationViewProjection.allViews();case "PROP"->List.of("FRONT","SIDE","BACK","SCALE");default->throw new IllegalArgumentException("未知素材类型");};}
    private static String masterFor(String kind){return "LOCATION".equals(kind)?"LAYOUT":"FRONT";}
    private static String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    private static JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode result=obj();SortedSet<String> keys=new TreeSet<>();value.fieldNames().forEachRemaining(keys::add);keys.forEach(k->result.set(k,canonical(value.get(k))));return result;}if(value.isArray()){ArrayNode result=JsonNodeFactory.instance.arrayNode();value.forEach(v->result.add(canonical(v)));return result;}return value;}
    private static String imageMime(byte[] bytes){if(bytes.length>8&&bytes[0]==(byte)0x89&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G')return "image/png";if(bytes.length>3&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8)return "image/jpeg";if(bytes.length>12&&bytes[0]=='R'&&bytes[1]=='I'&&bytes[8]=='W'&&bytes[9]=='E')return "image/webp";throw new IllegalArgumentException("返回内容不是支持的图片格式");}
}
