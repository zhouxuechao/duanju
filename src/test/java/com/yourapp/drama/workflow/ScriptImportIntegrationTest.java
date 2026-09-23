package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class ScriptImportIntegrationTest {
    @Autowired StudioService studio;
    @Autowired ScriptImportService imports;
    @Autowired ScriptWorkspaceService scripts;
    @Autowired DocumentStore store;

    @Test void scriptProjectNeedsNoIdeaOrNovelAnalysisAndTxtEntersReviewWorkspace(){
        ObjectNode project=studio.create(PROJECT,obj().put("name","上传剧本").put("sourceMode","SCRIPT").put("episodeCount",1).put("targetDuration",60));
        String text="第1集 夜归\n场景：旧屋\n人物：林川、阿宁\n动作：林川推门。\n林川：你听见了吗？\n这是一条无法归类的制片备注";
        ObjectNode result=imports.importBytes(id(project),"night.txt","TXT",text.getBytes(StandardCharsets.UTF_8));ObjectNode version=(ObjectNode)result.path("versions").get(0);
        assertThat(text(version,"status")).isEqualTo("REVIEW");assertThat(text(version,"sourceMode")).isEqualTo("SCRIPT");assertThat(text(version,"importStatus")).isEqualTo("IMPORT_REVIEW_REQUIRED");assertThat(version.path("structuredContent").path("scenes")).hasSize(1);assertThat(version.path("structuredContent").path("scenes").get(0).path("dialogues")).hasSize(1);
        ObjectNode importedVersion=version;assertThatThrownBy(()->scripts.confirm(id(importedVersion),obj().put("revision",revision(importedVersion)))).isInstanceOf(WorkflowException.class).hasMessageContaining("人工复核");ObjectNode reviewed=version.path("structuredContent").deepCopy();reviewed.put("importStatus","REVIEWED");version=scripts.update(id(version),obj().put("revision",revision(version)).set("structuredContent",reviewed));assertThat(text(scripts.confirm(id(version),obj().put("revision",revision(version))),"status")).isEqualTo("CONFIRMED");
        assertThat(store.list(NOVEL_SOURCE,id(project),null)).isEmpty();assertThat(store.list(NOVEL_ANALYSIS_JOB,id(project),null)).isEmpty();assertThat(store.list(GENERATION_JOB,id(project),null)).isEmpty();
    }

    @Test void docxScriptParagraphsAreImportedIntoTheSameWorkspaceContract() throws Exception {
        ObjectNode project=studio.create(PROJECT,obj().put("name","DOCX 剧本").put("sourceMode","SCRIPT").put("episodeCount",1).put("targetDuration",60));byte[] docx=zip(Map.of("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>","word/document.xml","<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>第1集</w:t></w:r></w:p><w:p><w:r><w:t>场景：石桥</w:t></w:r></w:p><w:p><w:r><w:t>阿宁：钥匙在这里。</w:t></w:r></w:p></w:body></w:document>"));ObjectNode result=imports.importBytes(id(project),"script.docx","DOCX",docx);ObjectNode version=(ObjectNode)result.path("versions").get(0);assertThat(text(version,"status")).isEqualTo("REVIEW");assertThat(version.path("structuredContent").path("scenes").get(0).path("dialogues").get(0).path("text").asText()).isEqualTo("钥匙在这里。");
    }
    private static byte[] zip(Map<String,String> entries)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return bytes.toByteArray();}
}
