package com.yourapp.drama.provider.mock;

import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.model.VideoGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MockVideoGenerator implements VideoGenerator {
    private final Map<String, VideoTask> tasks = new ConcurrentHashMap<>();
    @Override public Submission submit(VideoRequest request) {
        if (request.prompt() == null || request.prompt().isBlank()) throw ProviderException.invalid("PROMPT_REQUIRED", "提示词不能为空");
        String id = "mock-" + UUID.nameUUIDFromBytes(request.prompt().getBytes());
        tasks.put(id, new VideoTask(id, Status.QUEUED, null, null, id, null, null, true));
        return new Submission(id, id, true);
    }
    @Override public VideoTask poll(String taskId) {
        VideoTask current = tasks.get(taskId); if (current == null) throw ProviderException.invalid("TASK_NOT_FOUND", "模拟任务不存在");
        if (current.status() == Status.QUEUED) current = new VideoTask(taskId, Status.SUCCEEDED, "https://mock.volcengine.invalid/videos/"+taskId+".mp4?signature=simulated", Instant.now().plusSeconds(86400), current.requestId(), null, null, true);
        tasks.put(taskId, current); return current;
    }
    @Override public void cancel(String taskId) {
        VideoTask current = tasks.get(taskId); if (current == null) throw ProviderException.invalid("TASK_NOT_FOUND", "模拟任务不存在");
        if (current.status() == Status.SUCCEEDED) throw ProviderException.invalid("CANCEL_NOT_ALLOWED", "模拟任务已经完成");
        tasks.put(taskId, new VideoTask(taskId, Status.CANCELLED, null, null, current.requestId(), null, null, true));
    }
}
