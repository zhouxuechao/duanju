package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductionSnapshotJobIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired JobService jobs;

    @Test void paidJobAutomaticallyFreezesAndPinsProductionInputSnapshot(){
        ObjectNode project=store.create(PROJECT,obj().put("name","snapshot job").put("idea","fixture"));String projectId=id(project);
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",projectId).put("name","第一集").put("episodeNo",1));
        ObjectNode scene=store.create(SCENE,obj().put("projectId",projectId).put("episodeId",id(episode)).put("name","客厅"));
        ObjectNode shot=store.create(SHOT,obj().put("projectId",projectId).put("sceneId",id(scene)).put("action","举起证据").put("purpose","揭示线索").put("duration",3));
        ObjectNode actor=store.create(CHARACTER,obj().put("projectId",projectId).put("name","林川").put("identity","调查员"));
        ObjectNode look=store.create(CHARACTER_LOOK,obj().put("projectId",projectId).put("characterId",id(actor)).put("name","便装").put("validFromStoryTime",0));
        ObjectNode prompt=store.create(PROMPT_VERSION,obj().put("projectId",projectId).put("shotId",id(shot)).put("version",1).put("prompt","举起证据"));
        ObjectNode input=obj().put("promptVersionId",id(prompt)).put("scriptVersionId",id(episode)).put("prompt","举起证据");input.putArray("characterIds").add(id(actor));input.putArray("characterLookIds").add(id(look));

        ObjectNode job=jobs.enqueue(projectId,id(shot),"KEYFRAME",input,"snapshot-job-once");

        assertThat(text(job.path("inputSnapshot"),"productionInputSnapshotId")).isNotBlank();
        assertThat(text(job.path("inputSnapshot"),"assetSnapshotHash")).hasSize(64);
        assertThat(text(job,"productionInputSnapshotId")).isEqualTo(text(job.path("inputSnapshot"),"productionInputSnapshotId"));
        assertThat(text(job,"assetSnapshotHash")).isEqualTo(text(job.path("inputSnapshot"),"assetSnapshotHash"));
        assertThat(store.list(PRODUCTION_INPUT_SNAPSHOT,projectId,null)).hasSize(1);
        assertThat(id(jobs.enqueue(projectId,id(shot),"KEYFRAME",input,"snapshot-job-once"))).isEqualTo(id(job));
        assertThat(store.list(PRODUCTION_INPUT_SNAPSHOT,projectId,null)).hasSize(1);
    }
}
