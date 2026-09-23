package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NovelFormatSecurityTest {
    @Autowired DocumentStore store;
    @Autowired NovelIngestionService novels;

    @Test void txtSupportsUtf8BomGb18030AndSyntheticChapterThenAllowsManualCorrection() throws Exception {
        String projectId=id(store.create(PROJECT,obj().put("name","编码与章节").put("sourceMode","NOVEL")));
        byte[] utf8=("\ufeff第一章 雨夜\n林舟回到旧城。\n").getBytes(StandardCharsets.UTF_8);
        ObjectNode utf8Source=importFile(projectId,"bom.txt","TXT","text/plain",utf8);
        assertThat(text(utf8Source,"encoding")).isEqualTo("UTF-8");
        ObjectNode first=store.list(NOVEL_CHAPTER,projectId,id(utf8Source)).getFirst();
        assertThat(text(first,"title")).isEqualTo("第一章 雨夜");
        assertThat(first.path("synthetic").asBoolean()).isFalse();

        byte[] gb18030="没有章节标题，但文本仍须形成可编辑的合成章节。".getBytes(Charset.forName("GB18030"));
        ObjectNode legacy=importFile(projectId,"legacy.txt","TXT","text/plain",gb18030);
        assertThat(text(legacy,"encoding")).isEqualTo("GB18030");
        ObjectNode synthetic=store.list(NOVEL_CHAPTER,projectId,id(legacy)).getFirst();
        assertThat(synthetic.path("synthetic").asBoolean()).isTrue();
        ObjectNode renamed=novels.renameChapter(id(synthetic),obj().put("revision",revision(synthetic)).put("title","人工第一章"));
        assertThat(text(renamed,"title")).isEqualTo("人工第一章");
        assertThat(renamed.path("manuallyCorrected").asBoolean()).isTrue();
        assertThat(store.get(NOVEL_SOURCE,id(legacy)).path("parsingRevision").asInt()).isEqualTo(2);
    }

    @Test void docxParagraphsAndEpubSpineOrderAreParsedWithoutProviderJobs() throws Exception {
        String projectId=id(store.create(PROJECT,obj().put("name","归档格式").put("sourceMode","NOVEL")));
        byte[] docx=zip(Map.of(
            "[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "word/document.xml","<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>第一章 石桥</w:t></w:r></w:p><w:p><w:r><w:t>阿宁拾到钥匙。</w:t></w:r></w:p></w:body></w:document>"));
        ObjectNode word=importFile(projectId,"story.docx","DOCX","application/vnd.openxmlformats-officedocument.wordprocessingml.document",docx);
        assertThat(store.list(NOVEL_CHAPTER,projectId,id(word))).singleElement().satisfies(chapter->{
            assertThat(text(chapter,"title")).isEqualTo("第一章 石桥");
            assertThat(text(chapter,"normalizedText")).contains("阿宁拾到钥匙");
        });

        LinkedHashMap<String,String> entries=new LinkedHashMap<>();
        entries.put("mimetype","application/epub+zip");
        entries.put("META-INF/container.xml","<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>");
        entries.put("OEBPS/content.opf","<package xmlns=\"http://www.idpf.org/2007/opf\"><manifest><item id=\"b\" href=\"b.xhtml\"/><item id=\"a\" href=\"a.xhtml\"/></manifest><spine><itemref idref=\"a\"/><itemref idref=\"b\"/></spine></package>");
        entries.put("OEBPS/a.xhtml","<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>第一章 先</h1><p>先发生。</p></body></html>");
        entries.put("OEBPS/b.xhtml","<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>第二章 后</h1><p>后发生。</p></body></html>");
        ObjectNode epub=importFile(projectId,"story.epub","EPUB","application/epub+zip",zip(entries));
        assertThat(store.list(NOVEL_CHAPTER,projectId,id(epub))).extracting(c->text(c,"title"))
            .containsExactly("第一章 先","第二章 后");
        assertThat(store.list(GENERATION_JOB,projectId,null)).isEmpty();
    }

    @Test void rejectsArchiveTraversalZipBombAndWrongWholeFileHash() throws Exception {
        String projectId=id(store.create(PROJECT,obj().put("name","归档安全").put("sourceMode","NOVEL")));
        byte[] traversal=zip(Map.of("../outside","x","word/document.xml","<w:document xmlns:w=\"x\"/>"));
        assertThatThrownBy(()->importFile(projectId,"bad.docx","DOCX","application/zip",traversal))
            .isInstanceOf(WorkflowException.class).extracting(e->((WorkflowException)e).code()).isEqualTo("ZIP_TRAVERSAL");

        byte[] bomb=zip(Map.of("word/document.xml","A".repeat(300_000)));
        assertThatThrownBy(()->importFile(projectId,"bomb.docx","DOCX","application/zip",bomb))
            .isInstanceOf(WorkflowException.class).extracting(e->((WorkflowException)e).code()).isEqualTo("ZIP_BOMB");

        byte[] plain="第一章\n内容".getBytes(StandardCharsets.UTF_8);
        ObjectNode session=start(projectId,"wrong.txt","TXT","text/plain",plain,"0".repeat(64));
        novels.putPart(id(session),1,new ByteArrayInputStream(plain),plain.length,sha(plain));
        assertThatThrownBy(()->novels.complete(id(session))).isInstanceOf(WorkflowException.class)
            .extracting(e->((WorkflowException)e).code()).isEqualTo("UPLOAD_FILE_HASH_MISMATCH");
    }

    @Test void chapterSplitMergeAndReorderCreateANewParsingRevision() throws Exception {
        String projectId=id(store.create(PROJECT,obj().put("name","章节校正").put("sourceMode","NOVEL")));
        ObjectNode source=importFile(projectId,"edit.txt","TXT","text/plain","第一章 起点\n前半段。后半段。\n第二章 转折\n第二章正文。".getBytes(StandardCharsets.UTF_8));
        ObjectNode first=store.list(NOVEL_CHAPTER,projectId,id(source)).get(0);
        int splitAt=text(first,"normalizedText").indexOf("后半段");
        ObjectNode created=novels.splitChapter(id(first),obj().put("revision",revision(first)).put("offset",splitAt).put("title","第一章下"));
        assertThat(text(created,"normalizedText")).startsWith("后半段");
        assertThat(store.get(NOVEL_SOURCE,id(source)).path("parsingRevision").asInt()).isEqualTo(2);
        ObjectNode second=store.list(NOVEL_CHAPTER,projectId,id(source)).stream().filter(c->c.path("active").asBoolean(true)&&!id(c).equals(id(created))).skip(1).findFirst().orElseThrow();
        novels.mergeChapters(id(created),id(second),obj().put("revision",revision(created)));
        assertThat(store.get(NOVEL_CHAPTER,id(second)).path("active").asBoolean()).isFalse();
        var active=store.list(NOVEL_CHAPTER,projectId,id(source)).stream().filter(c->c.path("active").asBoolean(true)).toList();
        var order=obj().putArray("chapterIds");for(int i=active.size()-1;i>=0;i--)order.add(id(active.get(i)));
        novels.reorderChapters(id(source),order);
        assertThat(store.get(NOVEL_SOURCE,id(source)).path("parsingRevision").asInt()).isEqualTo(4);
    }

    @Test void undecodableTxtRequiresEncodingReviewInsteadOfSilentMojibake() throws Exception {
        String projectId=id(store.create(PROJECT,obj().put("name","编码复核").put("sourceMode","NOVEL")));
        byte[] bytes={(byte)0x81};ObjectNode session=start(projectId,"unknown.txt","TXT","text/plain",bytes,sha(bytes));
        novels.putPart(id(session),1,new ByteArrayInputStream(bytes),bytes.length,sha(bytes));
        assertThatThrownBy(()->novels.complete(id(session))).isInstanceOf(WorkflowException.class)
            .extracting(e->((WorkflowException)e).code()).isEqualTo("ENCODING_REVIEW_REQUIRED");
        ObjectNode completed=novels.upload(id(session));
        assertThat(text(store.get(NOVEL_SOURCE,text(completed,"novelId")),"status")).isEqualTo("ENCODING_REVIEW_REQUIRED");
    }

    private ObjectNode importFile(String projectId,String name,String format,String contentType,byte[] bytes)throws Exception{
        ObjectNode session=start(projectId,name,format,contentType,bytes,sha(bytes));
        novels.putPart(id(session),1,new ByteArrayInputStream(bytes),bytes.length,sha(bytes));
        return novels.complete(id(session));
    }
    private ObjectNode start(String projectId,String name,String format,String contentType,byte[] bytes,String hash){
        return novels.startUpload(projectId,obj().put("fileName",name).put("fileSize",bytes.length).put("format",format)
            .put("contentType",contentType).put("partSize",bytes.length).put("totalParts",1).put("fileSha256",hash));
    }
    private static byte[] zip(Map<String,String> entries)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){
            for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        return bytes.toByteArray();
    }
    private static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
}
