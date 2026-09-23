package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;
import java.time.Instant;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class PlatformReviewService {
    private final DocumentStore store;private final ScriptWorkspaceService scripts;
    public PlatformReviewService(DocumentStore store,ScriptWorkspaceService scripts){this.store=store;this.scripts=scripts;}
    public ObjectNode reject(ObjectNode request){String episodeId=required(request,"episodeId"),versionId=required(request,"scriptVersionId");ObjectNode episode=store.get(EPISODE,episodeId),version=store.get(EPISODE_SCRIPT_VERSION,versionId);if(!project(episode).equals(project(version)))throw new IllegalArgumentException("审核问题与剧本版本不属于同一项目");ObjectNode issue=obj().put("projectId",project(episode)).put("episodeId",episodeId).put("scriptVersionId",versionId).put("platform",required(request,"platform")).put("reasonCode",required(request,"reasonCode")).put("reasonText",request.path("reasonText").asText("")).put("status","OPEN").put("submittedAt",request.path("submittedAt").asText("")).put("rejectedAt",Instant.now().toString());ObjectNode saved=store.create(PLATFORM_REVIEW_ISSUE,issue),draft=scripts.fork(episodeId,obj().put("revision",revision(version)).put("createdBy","USER").put("changeReason","PLATFORM_REJECTION_FIX"));ObjectNode next=saved.deepCopy().put("status","IN_FIX").put("resolutionVersionId",id(draft));return store.update(PLATFORM_REVIEW_ISSUE,id(saved),revision(saved),next);}
}
