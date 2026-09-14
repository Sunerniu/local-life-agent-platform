package com.hmdp.ops;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.OpsWorkflowService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class OpsControllerTest {
    OpsWorkflowService workflow=mock(OpsWorkflowService.class);OpsChat chat=mock(OpsChat.class);MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new OpsController(workflow,chat)).setControllerAdvice(new OpsExceptionHandler()).build();}
    @AfterEach void cleanup(){UserHolder.removeUser();}
    @Test void diagnosisRequiresServerIdentity() throws Exception {
        mvc.perform(post("/agent/ops/diagnose")).andExpect(status().isUnauthorized());verifyNoInteractions(workflow);
    }
    @Test void suppliedUserAndResourceCannotReplaceServerContext() throws Exception {
        var user=new UserDTO();user.setId(7L);UserHolder.saveUser(user);
        mvc.perform(post("/agent/ops/proposals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":99,\"queue\":\"other\",\"incidentId\":\"test\",\"count\":1}"))
                .andExpect(status().isOk());verify(workflow).propose(7,"test",1);
    }
    @Test void nullDecisionIsRejectedAndMissingRepairIsNotAssumed() throws Exception {
        var user=new UserDTO();user.setId(7L);UserHolder.saveUser(user);
        mvc.perform(post("/agent/ops/actions/test/decision").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());verifyNoInteractions(workflow);
        mvc.perform(post("/agent/ops/actions/test/decision").contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
            .andExpect(status().isOk());verify(workflow).decide(7,"test",true,false);
    }
}
