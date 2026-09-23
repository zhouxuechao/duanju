package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.NovelIngestionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController @RequestMapping("/api/novels")
public class NovelIngestionController {
    private final NovelIngestionService novels;public NovelIngestionController(NovelIngestionService novels){this.novels=novels;}
    @PostMapping("/uploads") public ObjectNode start(@RequestBody ObjectNode body){return novels.startUpload(body.path("projectId").asText(),body);}
    @PutMapping("/uploads/{id}/parts/{partNo}") public ObjectNode part(@PathVariable String id,@PathVariable int partNo,@RequestHeader("X-Part-SHA256")String hash,@RequestPart("file")MultipartFile file)throws IOException{return novels.putPart(id,partNo,file.getInputStream(),file.getSize(),hash);}
    @GetMapping("/uploads/{id}") public ObjectNode upload(@PathVariable String id){return novels.upload(id);}
    @PostMapping("/uploads/{id}/complete") public ObjectNode complete(@PathVariable String id){return novels.complete(id);}
    @PostMapping("/uploads/{id}/cancel") public ObjectNode cancel(@PathVariable String id){return novels.cancel(id);}
    @PutMapping("/chapters/{id}") public ObjectNode chapter(@PathVariable String id,@RequestBody ObjectNode body){return novels.renameChapter(id,body);}
    @PostMapping("/chapters/{id}/split") public ObjectNode split(@PathVariable String id,@RequestBody ObjectNode body){return novels.splitChapter(id,body);}
    @PostMapping("/chapters/{targetId}/merge/{sourceId}") public ObjectNode merge(@PathVariable String targetId,@PathVariable String sourceId,@RequestBody ObjectNode body){return novels.mergeChapters(targetId,sourceId,body);}
    @PutMapping("/{novelId}/chapters/order") public ObjectNode reorder(@PathVariable String novelId,@RequestBody ObjectNode body){return novels.reorderChapters(novelId,body.withArray("chapterIds"));}
}
