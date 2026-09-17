package com.yourapp.drama.api;

import com.yourapp.drama.persistence.*;
import com.yourapp.drama.workflow.WorkflowException;
import com.yourapp.drama.model.ProviderException;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.LinkedHashMap;

@RestControllerAdvice
public class ApiErrors {
    private final Logger log=LoggerFactory.getLogger(getClass());
    @ExceptionHandler(ResourceNotFoundException.class) ResponseEntity<?> missing(ResourceNotFoundException e){return error(404,"NOT_FOUND","记录不存在");}
    @ExceptionHandler(RevisionConflictException.class) ResponseEntity<?> revision(RevisionConflictException e){return error(409,"REVISION_CONFLICT","内容已更新，请刷新后再修改");}
    @ExceptionHandler(WorkflowException.class) ResponseEntity<?> workflow(WorkflowException e){return error(409,e.code(),e.getMessage());}
    @ExceptionHandler(ProviderException.class) ResponseEntity<?> provider(ProviderException e){
        Map<String,Object> body=new LinkedHashMap<>();body.put("code",e.code());body.put("message",e.getMessage()==null?"三方服务请求失败":e.getMessage());
        if(e.requestId()!=null&&!e.requestId().isBlank())body.put("providerRequestId",e.requestId());body.put("retryable",e.retryable());body.put("submissionUncertain",e.uncertain());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException e){return error(400,"INVALID_INPUT",e.getMessage());}
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> json(HttpMessageNotReadableException e){return error(400,"INVALID_JSON","请求 JSON 格式无效");}
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict(DataIntegrityViolationException e){return error(409,"DATA_CONFLICT","记录与现有数据冲突，请检查关联对象或刷新后重试");}
    @ExceptionHandler(UncheckedIOException.class) ResponseEntity<?> media(UncheckedIOException e){return error(404,"MEDIA_UNAVAILABLE","媒体文件尚未就绪或无法读取");}
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> route(NoResourceFoundException e){return error(404,"NOT_FOUND","接口不存在");}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unknown(Exception e){
        if(e instanceof AsyncRequestNotUsableException){log.debug("Media client disconnected before the response completed");return null;}
        log.error("Request failed: {}",e.getClass().getSimpleName());return error(500,"INTERNAL_ERROR","操作失败，请查看任务状态后重试");
    }
    private ResponseEntity<?> error(int status,String code,String message){return ResponseEntity.status(status).body(Map.of("code",code,"message",message==null?code:message));}
}
