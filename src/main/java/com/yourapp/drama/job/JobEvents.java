package com.yourapp.drama.job;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class JobEvents {
    private record Client(String projectId,SseEmitter emitter){}
    private final CopyOnWriteArrayList<Client> clients=new CopyOnWriteArrayList<>();
    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    public JobEvents(StringRedisTemplate redis,@Value("${drama.redis.enabled:false}")boolean enabled){this.redis=redis;this.redisEnabled=enabled;}
    public SseEmitter subscribe(String projectId){
        SseEmitter emitter=new SseEmitter(300_000L); Client client=new Client(projectId,emitter); clients.add(client);
        emitter.onCompletion(()->clients.remove(client)); emitter.onTimeout(()->{clients.remove(client);emitter.complete();}); emitter.onError(e->clients.remove(client));
        try{emitter.send(SseEmitter.event().name("connected").data("ok"));}catch(Exception e){clients.remove(client);}
        return emitter;
    }
    public void publish(ObjectNode job){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()){
            ObjectNode snapshot=job.deepCopy();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){deliver(snapshot);}});
        }else deliver(job);
    }
    private void deliver(ObjectNode job){
        // PostgreSQL is the durable queue. Redis is an acceleration/notification channel only.
        if(redisEnabled)try{redis.convertAndSend("drama:jobs",job.path("id").asText());}catch(Exception ignored){}
        for(Client c:clients)if(c.projectId()==null||c.projectId().equals(job.path("projectId").asText()))try{
            var safe=job.deepCopy(); safe.remove(java.util.List.of("inputSnapshot","outputSnapshot"));
            c.emitter().send(SseEmitter.event().name("job").id(job.path("id").asText()).data(safe));
        }catch(Exception e){clients.remove(c);c.emitter().complete();}
    }
}
