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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProviderReconciliationIntegrationTest {
    @Autowired JobService jobs;
    @Autowired DocumentStore store;

    @Test void confirmedSubmissionResumesExistingPollingJobWithoutCreatingAnotherPaidJob() {
        ObjectNode project=store.create(PROJECT,obj().put("name","对账测试").put("episodeCount",1).put("targetDuration",24));
        ObjectNode failed=uncertain(project,"VIDEO");

        ObjectNode reconciled=jobs.reconcile(id(failed),
                obj().put("decision","CONFIRMED_SUBMITTED").put("providerTaskId","cgt-provider-task")
                        .put("evidence","服务商控制台显示任务处理中").put("reviewer","operator"));

        assertThat(text(reconciled,"status")).isEqualTo("RUNNING");
        assertThat(text(reconciled,"providerTaskId")).isEqualTo("cgt-provider-task");
        assertThat(reconciled.path("submissionUncertain").asBoolean()).isFalse();
        assertThat(store.list(GENERATION_JOB,id(project),null)).hasSize(1);
        ObjectNode recovered=store.list(VIDEO_TAKE,id(project),null).getFirst();
        assertThat(text(recovered,"recoveredFromJobId")).isEqualTo(id(failed));
    }

    @Test void unknownTaskCannotRetryOrClaimNoSubmissionAndKeepsPossibleBillingState() {
        ObjectNode project=store.create(PROJECT,obj().put("name","未知状态测试").put("episodeCount",1).put("targetDuration",24));
        ObjectNode failed=uncertain(project,"VIDEO");
        ObjectNode resumed=jobs.reconcile(id(failed),obj().put("decision","CONFIRMED_SUBMITTED").put("providerTaskId","cgt-provider-task")
                .put("evidence","服务商已接单").put("reviewer","operator"));
        ObjectNode unknown=jobs.unknown(id(resumed),"POLL_EXHAUSTED","轮询无法确认最终状态");

        assertThat(text(unknown,"billingStatus")).isEqualTo("POSSIBLY_BILLED");
        assertThat(store.list(COST_RECORD,id(project),null).stream().filter(cost->"UNKNOWN".equals(text(cost,"terminalStatus"))).toList()).singleElement().satisfies(cost->{
            assertThat(text(cost,"billingStatus")).isEqualTo("POSSIBLY_BILLED");
            assertThat(text(cost,"terminalStatus")).isEqualTo("UNKNOWN");
        });
        assertThatThrownBy(()->jobs.retry(id(unknown))).isInstanceOf(WorkflowException.class);
        assertThatThrownBy(()->jobs.reconcile(id(unknown),obj().put("decision","CONFIRMED_NOT_SUBMITTED")
                .put("evidence","错误结论").put("reviewer","operator"))).isInstanceOf(WorkflowException.class);
        assertThat(store.list(GENERATION_JOB,id(project),null)).hasSize(1);
    }

    @Test void confirmedNotSubmittedUnlocksAControlledRetryEvenWhenRequestIdWasRecorded() {
        ObjectNode project=store.create(PROJECT,obj().put("name","未接单测试").put("episodeCount",1).put("targetDuration",24));
        ObjectNode failed=uncertain(project,"KEYFRAME");

        ObjectNode reconciled=jobs.reconcile(id(failed),
                obj().put("decision","CONFIRMED_NOT_SUBMITTED").put("evidence","服务商无此请求记录").put("reviewer","operator"));
        ObjectNode retry=jobs.retry(id(reconciled));

        assertThat(text(reconciled,"status")).isEqualTo("FAILED");
        assertThat(reconciled.path("submissionUncertain").asBoolean()).isFalse();
        assertThat(text(reconciled,"reconciliationStatus")).isEqualTo("CONFIRMED_NOT_SUBMITTED");
        assertThat(text(retry.path("inputSnapshot"),"retryOfJobId")).isEqualTo(id(failed));
    }

    @Test void cannotPretendACompletedImageRequestCanBePolled() {
        ObjectNode project=store.create(PROJECT,obj().put("name","错误恢复测试").put("episodeCount",1).put("targetDuration",24));
        ObjectNode failed=uncertain(project,"KEYFRAME");

        assertThatThrownBy(()->jobs.reconcile(id(failed),
                obj().put("decision","CONFIRMED_SUBMITTED").put("providerTaskId","cgt-image-task")
                        .put("evidence","已提交").put("reviewer","operator")))
                .isInstanceOf(WorkflowException.class);
    }

    private ObjectNode uncertain(ObjectNode project,String type){
        String shotId=null;ObjectNode input=obj().put("prompt","test");
        if("VIDEO".equals(type)){
            ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));
            ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","院内"));
            ObjectNode shot=store.create(SHOT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("shotNo",1).put("duration",3));shotId=id(shot);
            ObjectNode prompt=store.create(PROMPT_VERSION,obj().put("projectId",id(project)).put("shotId",shotId).put("purpose","VIDEO").put("version",1).put("prompt","test"));
            ObjectNode frame=store.create(KEYFRAME,obj().put("projectId",id(project)).put("shotId",shotId).put("provider","VOLCENGINE")
                    .put("sourceModel","seedream").put("providerUrl","https://provider/frame.png").put("handoffStatus","HANDED_OFF"));
            input.put("keyframeId",id(frame)).put("promptVersionId",id(prompt)).put("firstFrameProviderUrl","https://provider/frame.png").put("takeNo",1);
        }
        ObjectNode queued=jobs.enqueue(id(project),shotId,type,input,"reconcile-"+type);
        ObjectNode running=jobs.claim().orElseThrow();
        assertThat(id(running)).isEqualTo(id(queued));
        jobs.mutate(id(running),j->j.put("providerRequestId","021-provider-request"));
        return jobs.fail(id(running),"REQUEST_TIMEOUT","响应丢失",false,true);
    }
}
