package com.yourapp.drama.job;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.StoryDevelopmentService;
import com.yourapp.drama.workflow.WorkflowException;
import org.springframework.stereotype.Service;

import static com.yourapp.drama.workflow.Documents.text;

/** Routes synchronous creative jobs to their durable stage processor. */
@Service
public class CreativeJobs {
    private final StoryDevelopmentService development;private final DirectorGenerationService director;
    public CreativeJobs(StoryDevelopmentService development,DirectorGenerationService director){this.development=development;this.director=director;}

    public void process(ObjectNode job){
        switch(text(job,"type")){
            case "STORY","SCRIPT"->development.process(job);
            case "DIRECTOR_PLAN","SHOT_DETAIL"->director.process(job);
            case "KEYFRAME_QC"->throw new WorkflowException("VISUAL_REVIEW_REQUIRED","当前文本模型不能执行视觉质检，请查看实际画面后提交人工审查");
            case "VIDEO_QC"->throw new WorkflowException("MANUAL_VIDEO_REVIEW","请在播放器中审核视频的身份、服装、动作、道具和镜头衔接，再提交质检结果");
            default->throw new WorkflowException("UNSUPPORTED_JOB","尚未配置此类任务的执行器："+text(job,"type"));
        }
    }
    public void syncDevelopmentFailure(ObjectNode job){development.syncFailure(job);}
    public void checkProductionInput(ObjectNode job){development.checkProductionInput(job);}
}
