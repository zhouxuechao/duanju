package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class NovelIngestionIntegrationTest {
    @Autowired DocumentStore store;@Autowired NovelIngestionService novels;

    @Test void multipartResumeParsesOneHundredChaptersWithTraceableSemanticChunks()throws Exception{
        ObjectNode project=store.create(PROJECT,obj().put("name","长篇导入").put("sourceMode","NOVEL"));StringBuilder text=new StringBuilder();for(int i=1;i<=100;i++)text.append("第").append(i).append("章 章节").append(i).append('\n').append("林川在旧宅发现第").append(i).append("条线索。\n\n");byte[] bytes=text.toString().getBytes(StandardCharsets.UTF_8);int partSize=137,total=(bytes.length+partSize-1)/partSize;
        ObjectNode session=novels.startUpload(id(project),obj().put("fileName","长篇.txt").put("fileSize",bytes.length).put("format","TXT").put("partSize",partSize).put("totalParts",total).put("fileSha256",sha(bytes)));
        for(int part=total;part>=1;part--){int start=(part-1)*partSize,length=Math.min(partSize,bytes.length-start);novels.putPart(id(session),part,new ByteArrayInputStream(bytes,start,length),length,sha(java.util.Arrays.copyOfRange(bytes,start,start+length)));}
        ObjectNode resumed=novels.upload(id(session));assertThat(resumed.path("missingParts")).isEmpty();
        ObjectNode source=novels.complete(id(session));assertThat(text(source,"status")).isEqualTo("READY");assertThat(source.path("chapterCount").asInt()).isEqualTo(100);
        assertThat(store.list(NOVEL_CHAPTER,id(project),id(source))).hasSize(100).allSatisfy(chapter->{assertThat(chapter.path("sourceEnd").asLong()).isGreaterThan(chapter.path("sourceStart").asLong());assertThat(text(chapter,"contentHash")).hasSize(64);});
        assertThat(store.list(NOVEL_CHUNK,id(project),null)).isNotEmpty().allSatisfy(chunk->{assertThat(text(chunk,"previousChunkId")).doesNotContain("null");assertThat(text(chunk,"contentHash")).hasSize(64);});
        assertThat(store.list(SOURCE_REFERENCE,id(project),null)).hasSameSizeAs(store.list(NOVEL_CHUNK,id(project),null));assertThat(store.list(GENERATION_JOB,id(project),null)).isEmpty();
    }

    @Test void unsafeNamesAndCorruptDuplicatePartsAreRejected()throws Exception{
        ObjectNode project=store.create(PROJECT,obj().put("name","安全上传").put("sourceMode","NOVEL"));
        assertThatThrownBy(()->novels.startUpload(id(project),obj().put("fileName","../escape.txt").put("fileSize",3).put("format","TXT").put("partSize",3).put("totalParts",1).put("fileSha256",sha("abc".getBytes())))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode session=novels.startUpload(id(project),obj().put("fileName","safe.txt").put("fileSize",3).put("format","TXT").put("partSize",3).put("totalParts",1).put("fileSha256",sha("abc".getBytes())));novels.putPart(id(session),1,new ByteArrayInputStream("abc".getBytes()),3,sha("abc".getBytes()));
        assertThatThrownBy(()->novels.putPart(id(session),1,new ByteArrayInputStream("xyz".getBytes()),3,sha("xyz".getBytes()))).isInstanceOf(WorkflowException.class).hasMessageContaining("不同");
    }
    private static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
}
