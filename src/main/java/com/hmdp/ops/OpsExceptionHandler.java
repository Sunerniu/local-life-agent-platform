package com.hmdp.ops;

import com.hmdp.agent.AgentException;
import com.hmdp.dto.Result;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=OpsController.class)
public class OpsExceptionHandler {
    @ExceptionHandler(AgentException.class) public ResponseEntity<Result> denied(AgentException ex) { return ResponseEntity.status(ex.getStatus()).body(Result.fail(ex.getMessage())); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Result> invalid(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Result.fail(ex.getMessage())); }
    @ExceptionHandler(Exception.class) public ResponseEntity<Result> failed(Exception ex) { return ResponseEntity.status(503).body(Result.fail("运维服务暂不可用；执行结果请刷新核对，禁止自动重试操作")); }
}
