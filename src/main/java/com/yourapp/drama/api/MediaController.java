package com.yourapp.drama.api;

import com.yourapp.drama.storage.MediaStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/media")
public class MediaController {
    private final MediaStorage storage;
    public MediaController(MediaStorage storage) { this.storage = storage; }
    @GetMapping("/**") public ResponseEntity<InputStreamResource> read(HttpServletRequest request) {
        String key = request.getRequestURI().substring("/api/media/".length());
        String mime = key.endsWith(".mp4") ? "video/mp4" : key.endsWith(".wav") ? "audio/wav" : key.endsWith(".mp3") ? "audio/mpeg" : key.endsWith(".png") ? "image/png" : key.endsWith(".jpg") ? "image/jpeg" : key.endsWith(".srt") ? "application/x-subrip" : "application/octet-stream";
        return ResponseEntity.ok().header("X-Content-Type-Options", "nosniff").contentType(MediaType.parseMediaType(mime)).body(new InputStreamResource(storage.open(key)));
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Map<String,String> upload(@RequestParam MultipartFile file) throws IOException {
        String original = Objects.toString(file.getOriginalFilename(), "");
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        if (!Set.of(".mp4", ".wav", ".mp3", ".png", ".jpg", ".jpeg").contains(ext)) throw new IllegalArgumentException("支持上传 PNG、JPG、MP4、WAV 和 MP3");
        String key = "uploads/" + UUID.randomUUID() + ext;
        try (var input = file.getInputStream()) { return Map.of("key",key,"url",storage.put(key,input,file.getContentType()==null ? "application/octet-stream" : file.getContentType())); }
    }
}
