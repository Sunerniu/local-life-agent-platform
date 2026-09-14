package com.hmdp.ops;

import com.hmdp.agent.AgentException;
import com.hmdp.dto.Result;
import com.hmdp.service.OpsWorkflowService;
import com.hmdp.utils.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent/ops")
public class OpsController {
    private final OpsWorkflowService workflow;
    private final OpsChat chat;
    public OpsController(OpsWorkflowService workflow,OpsChat chat) { this.workflow=workflow; this.chat=chat; }
    public record Chat(String message,String incidentId) { }
    @PostMapping("/chat") public Result chat(@RequestBody Chat input) { return Result.ok(chat.chat(user(),input.message(),input.incidentId())); }
    public record Proposal(String incidentId,int count) { }
    public record Decision(Boolean approve,Boolean repaired) { }
    @GetMapping("/context") public Result context() { return Result.ok(workflow.context(user())); }
    @GetMapping("/incidents/{id}") public Result report(@PathVariable String id) { return Result.ok(workflow.report(user(),id)); }
    @PostMapping("/diagnose") public Result diagnose() { return Result.ok(workflow.diagnose(user())); }
    @PostMapping("/proposals") public Result propose(@RequestBody Proposal input) { return Result.ok(workflow.propose(user(),input.incidentId(),input.count())); }
    @PostMapping("/actions/{id}/decision") public Result decide(@PathVariable String id,@RequestBody Decision input) {
        if(input.approve()==null) throw new IllegalArgumentException("必须明确 approve");
        return Result.ok(workflow.decide(user(),id,input.approve(),Boolean.TRUE.equals(input.repaired())));
    }
    @PostMapping("/actions/{id}/verify") public Result verify(@PathVariable String id) { return Result.ok(workflow.verify(user(),id)); }
    static long user() {
        var user=UserHolder.getUser();
        if(user==null || user.getId()==null) throw new AgentException(HttpStatus.UNAUTHORIZED,"请先登录"); return user.getId();
    }
}
