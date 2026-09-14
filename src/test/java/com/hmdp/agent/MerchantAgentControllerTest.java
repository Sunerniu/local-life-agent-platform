package com.hmdp.agent;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MerchantAgentControllerTest {
    MerchantAgent agent = mock(MerchantAgent.class);
    ToolRuntime runtime = mock(ToolRuntime.class);
    MerchantWorkspace workspace = mock(MerchantWorkspace.class);
    AgentPermissionService permissions = mock(AgentPermissionService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new MerchantAgentController(agent, runtime, workspace, permissions))
                .setControllerAdvice(new AgentExceptionHandler()).build();
    }
    @AfterEach void cleanup() { UserHolder.removeUser(); }
    @Test void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(post("/agent/merchant/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\",\"userId\":7}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(agent, runtime);
    }
    @Test void identityComesFromExistingLoginContext() throws Exception {
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        when(permissions.allows(7, 1, "shop:read")).thenReturn(true);
        when(agent.chat(7, "你好", 1L)).thenReturn(new MerchantAgent.Reply("COMPLETED", "你好", List.of()));
        mvc.perform(post("/agent/merchant/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\",\"userId\":99,\"shopId\":1}")).andExpect(status().isOk());
        verify(agent).chat(7, "你好", 1L);
    }
    @Test void approvalRequiresExplicitDecision() throws Exception {
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        mvc.perform(post("/agent/merchant/approvals/00000000-0000-0000-0000-000000000001/decision")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(runtime);
    }
}
