package com.hmdp.agent;

import com.hmdp.dto.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MerchantAgentController.class)
public class AgentExceptionHandler {
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Result> handle(AgentException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Result.fail(ex.getMessage()));
    }
}
