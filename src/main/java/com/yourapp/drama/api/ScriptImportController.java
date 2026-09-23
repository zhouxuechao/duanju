package com.yourapp.drama.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.ScriptImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController @RequestMapping("/api/projects/{projectId}/script-import")
public class ScriptImportController {
    private final ScriptImportService imports;
    public ScriptImportController(ScriptImportService imports){this.imports=imports;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ObjectNode upload(@PathVariable String projectId,@RequestParam("file")MultipartFile file,@RequestParam String format)throws IOException{return imports.importBytes(projectId,file.getOriginalFilename()==null?"script."+format.toLowerCase():file.getOriginalFilename(),format,file.getBytes());}
}
