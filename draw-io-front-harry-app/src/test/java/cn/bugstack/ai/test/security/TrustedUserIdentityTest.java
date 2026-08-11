package cn.bugstack.ai.test.security;

import cn.bugstack.ai.domain.agent.model.valobj.AgentChatResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.AgentOutputEventVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import cn.bugstack.ai.trigger.http.AgentServiceController;
import cn.bugstack.ai.trigger.security.AuthenticatedUserProvider;
import cn.bugstack.ai.trigger.security.JsonAccessDeniedHandler;
import cn.bugstack.ai.trigger.security.JsonAuthenticationEntryPoint;
import cn.bugstack.ai.trigger.security.SupabaseSecurityConfig;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import java.util.List;


import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@RunWith(SpringRunner.class)
@WebMvcTest(controllers = AgentServiceController.class)
@Import({
        SupabaseSecurityConfig.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        AuthenticatedUserProvider.class
})
@TestPropertySource(properties = {
        "supabase.auth.issuer=https://test-project.invalid/auth/v1",
        "supabase.auth.jwks-uri=http://127.0.0.1:9/jwks",
        "supabase.auth.allowed-user-ids=22222222-2222-2222-2222-222222222222"
})
public class TrustedUserIdentityTest {

    private static final String AUTHENTICATED_USER_ID =
            "22222222-2222-2222-2222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IChatService chatService;

    @MockitoBean
    private MyTestMcpService myTestMcpService;

    @Test
    public void shouldUseJwtSubjectInsteadOfRequestUserId() throws Exception {
        when(chatService.createSession("100001", AUTHENTICATED_USER_ID))
                .thenReturn("trusted-session");

        when(chatService.createSession("100001", "attacker"))
                .thenReturn("attacker-session");

        mockMvc.perform(post("/api/v1/create_session")
                        .with(jwt().jwt(token -> token
                                .subject(AUTHENTICATED_USER_ID)
                                .claim("role", "authenticated")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "100001",
                                  "userId": "attacker"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId")
                        .value("trusted-session"));

        verify(chatService)
                .createSession("100001", AUTHENTICATED_USER_ID);

        verify(chatService, never())
                .createSession("100001", "attacker");
    }

    @Test
    public void shouldUseJwtSubjectForSynchronousChat() throws Exception {
        AgentChatResultVO trustedResult = AgentChatResultVO.builder()
                .content("trusted-answer")
                .traces(List.of())
                .build();

        AgentChatResultVO attackerResult = AgentChatResultVO.builder()
                .content("attacker-answer")
                .traces(List.of())
                .build();

        when(chatService.handleMessage(
                "100001",
                AUTHENTICATED_USER_ID,
                "session-1",
                "hello"))
                .thenReturn(trustedResult);

        when(chatService.handleMessage(
                "100001",
                "attacker",
                "session-1",
                "hello"))
                .thenReturn(attackerResult);

        mockMvc.perform(post("/api/v1/chat")
                        .with(jwt().jwt(token -> token
                                .subject(AUTHENTICATED_USER_ID)
                                .claim("role", "authenticated")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "agentId": "100001",
                              "userId": "attacker",
                              "sessionId": "session-1",
                              "message": "hello"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content")
                        .value("trusted-answer"));

        verify(chatService).handleMessage(
                "100001",
                AUTHENTICATED_USER_ID,
                "session-1",
                "hello");

        verify(chatService, never()).handleMessage(
                "100001",
                "attacker",
                "session-1",
                "hello");
    }

    @Test
    public void shouldUseJwtSubjectForStreamingChat() throws Exception {
        AgentOutputEventVO trustedOutput =
                AgentOutputEventVO.builder()
                        .type(AgentOutputEventVO.Type.FINAL)
                        .agentName("drawio-agent")
                        .content("trusted-stream")
                        .completed(true)
                        .build();

        AgentOutputEventVO attackerOutput =
                AgentOutputEventVO.builder()
                        .type(AgentOutputEventVO.Type.FINAL)
                        .agentName("drawio-agent")
                        .content("attacker-stream")
                        .completed(true)
                        .build();

        when(chatService.handleMessageStream(
                "100001",
                AUTHENTICATED_USER_ID,
                "session-1",
                "hello"))
                .thenReturn(Flowable.just(trustedOutput));

        when(chatService.handleMessageStream(
                "100001",
                "attacker",
                "session-1",
                "hello"))
                .thenReturn(Flowable.just(attackerOutput));

        MvcResult asyncResult = mockMvc.perform(
                        post("/api/v1/chat_stream")
                                .with(jwt().jwt(token -> token
                                        .subject(AUTHENTICATED_USER_ID)
                                        .claim("role", "authenticated")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content("""
                                    {
                                      "agentId": "100001",
                                      "userId": "attacker",
                                      "sessionId": "session-1",
                                      "message": "hello"
                                    }
                                    """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("trusted-stream")));

        verify(chatService).handleMessageStream(
                "100001",
                AUTHENTICATED_USER_ID,
                "session-1",
                "hello");

        verify(chatService, never()).handleMessageStream(
                "100001",
                "attacker",
                "session-1",
                "hello");
    }
}
