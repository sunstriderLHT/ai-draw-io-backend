package cn.bugstack.ai.test.trigger;

import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import cn.bugstack.ai.trigger.http.AgentServiceController;
import cn.bugstack.ai.trigger.http.AgentStreamHeartbeatScheduler;
import cn.bugstack.ai.trigger.http.MeteredAgentChatFacade;
import cn.bugstack.ai.trigger.security.AuthenticatedUserProvider;
import cn.bugstack.ai.trigger.security.JsonAccessDeniedHandler;
import cn.bugstack.ai.trigger.security.JsonAuthenticationEntryPoint;
import cn.bugstack.ai.trigger.security.SupabaseSecurityConfig;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestAlreadyCompletedException;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestInProgressException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import cn.bugstack.ai.domain.quota.exception.QuotaExhaustedException;
import org.springframework.http.MediaType;

import java.util.UUID;


import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request
        .MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request
        .MockMvcRequestBuilders.post;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;

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
        "supabase.auth.allowed-user-ids=" +
                "22222222-2222-2222-2222-222222222222"
})
public class AiApiContractTest {

    private static final String USER_ID =
            "22222222-2222-2222-2222-222222222222";

    private static final String REQUEST_ID =
            "55555555-5555-5555-5555-555555555555";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IAiQuotaService quotaService;

    @MockitoBean
    private IChatService chatService;

    @MockitoBean
    private MeteredAgentChatFacade meteredAgentChatFacade;

    @MockitoBean
    private MyTestMcpService myTestMcpService;

    @MockitoBean
    private AgentStreamHeartbeatScheduler heartbeatScheduler;

    @Test
    public void shouldExposeQuotaWithoutConsumingIt()
            throws Exception {

        when(quotaService.getSnapshot(USER_ID))
                .thenReturn(
                        new QuotaSnapshotEntity(
                                3,
                                0,
                                0,
                                0
                        )
                );

        mockMvc.perform(
                        get("/api/v1/quota")
                                .with(jwt().jwt(token -> token
                                        .subject(USER_ID)
                                        .claim(
                                                "role",
                                                "authenticated"
                                        )))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code")
                        .value("0000"))
                .andExpect(jsonPath("$.data.freeGranted")
                        .value(3))
                .andExpect(jsonPath("$.data.purchasedGranted")
                        .value(0))
                .andExpect(jsonPath("$.data.consumed")
                        .value(0))
                .andExpect(jsonPath("$.data.reserved")
                        .value(0))
                .andExpect(jsonPath("$.data.remaining")
                        .value(3));

        verify(quotaService).getSnapshot(USER_ID);
    }

    @Test
    public void shouldReturn402WhenQuotaIsExhausted()
            throws Exception {

        QuotaSnapshotEntity exhaustedSnapshot =
                new QuotaSnapshotEntity(
                        3,
                        0,
                        3,
                        0
                );

        when(meteredAgentChatFacade.chat(
                USER_ID,
                REQUEST_ID,
                "100001",
                "session-1",
                "hello"
        )).thenThrow(
                new QuotaExhaustedException(
                        exhaustedSnapshot
                )
        );

        mockMvc.perform(
                        post("/api/v1/chat")
                                .with(jwt().jwt(token -> token
                                        .subject(USER_ID)
                                        .claim(
                                                "role",
                                                "authenticated"
                                        )))
                                .header(
                                        "Idempotency-Key",
                                        REQUEST_ID
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                {
                                  "agentId": "100001",
                                  "sessionId": "session-1",
                                  "message": "hello"
                                }
                                """)
                )
                .andExpect(
                        status().isPaymentRequired()
                )
                .andExpect(
                        jsonPath("$.code")
                                .value("0004")
                )
                .andExpect(
                        jsonPath("$.info")
                                .value("额度不足")
                )
                .andExpect(
                        jsonPath("$.data.remaining")
                                .value(0)
                );
    }

    @Test
    public void shouldReturn400ForMalformedIdempotencyKey()
            throws Exception {

        mockMvc.perform(
                        post("/api/v1/chat")
                                .with(jwt().jwt(token -> token
                                        .subject(USER_ID)
                                        .claim(
                                                "role",
                                                "authenticated"
                                        )))
                                .header(
                                        "Idempotency-Key",
                                        "not-a-uuid"
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                {
                                  "agentId": "100001",
                                  "sessionId": "session-1",
                                  "message": "hello"
                                }
                                """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("0002"))
                .andExpect(jsonPath("$.info")
                        .value("非法参数"));

        verify(
                meteredAgentChatFacade,
                never()
        ).chat(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    public void shouldReturn409WhenRequestIsInProgress()
            throws Exception {

        when(meteredAgentChatFacade.chat(
                USER_ID,
                REQUEST_ID,
                "100001",
                "session-1",
                "hello"
        )).thenThrow(
                new QuotaRequestInProgressException()
        );

        mockMvc.perform(validChatRequest())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("0005"))
                .andExpect(jsonPath("$.info")
                        .value("相同请求正在处理中"));
    }

    @Test
    public void shouldReturn409WhenRequestAlreadyCompleted()
            throws Exception {

        when(meteredAgentChatFacade.chat(
                USER_ID,
                REQUEST_ID,
                "100001",
                "session-1",
                "hello"
        )).thenThrow(
                new QuotaRequestAlreadyCompletedException()
        );

        mockMvc.perform(validChatRequest())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("0006"))
                .andExpect(jsonPath("$.info")
                        .value("相同请求已处理完成"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validChatRequest() {
        return post("/api/v1/chat")
                .with(jwt().jwt(token -> token
                        .subject(USER_ID)
                        .claim(
                                "role",
                                "authenticated"
                        )))
                .header(
                        "Idempotency-Key",
                        REQUEST_ID
                )
                .contentType(
                        MediaType.APPLICATION_JSON
                )
                .content("""
                {
                  "agentId": "100001",
                  "sessionId": "session-1",
                  "message": "hello"
                }
                """);
    }

    @Test
    public void shouldReturnJson400WhenIdempotencyKeyIsMissing()
            throws Exception {

        mockMvc.perform(
                        post("/api/v1/chat")
                                .with(jwt().jwt(token -> token
                                        .subject(USER_ID)
                                        .claim(
                                                "role",
                                                "authenticated"
                                        )))
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                {
                                  "agentId": "100001",
                                  "sessionId": "session-1",
                                  "message": "hello"
                                }
                                """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("0002"))
                .andExpect(jsonPath("$.info")
                        .value("非法参数"));

        verify(
                meteredAgentChatFacade,
                never()
        ).chat(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    public void shouldReturn400ForMalformedStreamingIdempotencyKey()
            throws Exception {

        mockMvc.perform(
                        post("/api/v1/chat_stream")
                                .with(jwt().jwt(token -> token
                                        .subject(USER_ID)
                                        .claim(
                                                "role",
                                                "authenticated"
                                        )))
                                .header(
                                        "Idempotency-Key",
                                        "not-a-uuid"
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .accept(
                                        MediaType.TEXT_EVENT_STREAM,
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                {
                                  "agentId": "100001",
                                  "sessionId": "session-1",
                                  "message": "hello"
                                }
                                """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("0002"))
                .andExpect(jsonPath("$.info")
                        .value("非法参数"));

        verify(
                meteredAgentChatFacade,
                never()
        ).chatStream(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }
}
