package com.yourapp.drama.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.yourapp.drama.persistence.ResourceKind.PROJECT;
import static com.yourapp.drama.workflow.Documents.id;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GenerationJobTypeConstraintTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentStore store;

    @Test void legacyJobTypeCannotReenterTheActiveQueue(){
        ObjectNode project=store.create(PROJECT,obj().put("name","旧任务约束").put("idea","验证旧定义不能执行"));
        assertThatThrownBy(()->insert(id(project),"SHOT_PLAN","QUEUED"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void completedLegacyHistoryAndCurrentQueuedTypesRemainReadable(){
        ObjectNode project=store.create(PROJECT,obj().put("name","任务约束正例").put("idea","保留历史记录"));
        assertThatCode(()->insert(id(project),"SHOT_PLAN","FAILED")).doesNotThrowAnyException();
        assertThatCode(()->insert(id(project),"SHOT_DETAIL","QUEUED")).doesNotThrowAnyException();
    }

    private void insert(String projectId,String type,String status){
        UUID id=UUID.randomUUID(),project=UUID.fromString(projectId);OffsetDateTime now=OffsetDateTime.now();
        jdbc.update("""
            INSERT INTO "generation_job"(id,project_id,parent_id,revision,created_at,updated_at,document,type,status,
              attempts,max_attempts,progress,cost,cancel_requested)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,project,project,1,now,now,"{}",type,status,0,3,0,0,false);
    }
}
