package cn.bugstack.ai.test.trigger;

import cn.bugstack.ai.domain.agent.model.valobj.AgentChatResultVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import cn.bugstack.ai.trigger.http.MeteredAgentChatFacade;
import cn.bugstack.ai.trigger.http.MeteredChatResult;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.Assert.fail;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class MeteredSynchronousChatTest {

    private static final String USER_ID =
            "22222222-2222-2222-2222-222222222222";

    private static final String REQUEST_ID =
            "33333333-3333-3333-3333-333333333333";


    private static final String AGENT_ID = "100001";
    private static final String SESSION_ID = "session-1";
    private static final String MESSAGE = "hello";

    @Test
    public void shouldReserveInvokeModelAndCommitInOrder() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        AgentChatResultVO modelResult =
                AgentChatResultVO.builder()
                        .content("drawio xml")
                        .traces(List.of())
                        .build();

        when(chatService.handleMessage(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenReturn(modelResult);

        when(quotaService.commit(USER_ID, REQUEST_ID))
                .thenReturn(
                        new QuotaSnapshotEntity(
                                3,
                                0,
                                1,
                                0
                        )
                );

        MeteredAgentChatFacade facade =
                new MeteredAgentChatFacade(
                        quotaService,
                        chatService
                );

        MeteredChatResult result =
                facade.chat(
                        USER_ID,
                        REQUEST_ID,
                        AGENT_ID,
                        SESSION_ID,
                        MESSAGE
                );

        assertSame(modelResult, result.result());
        assertEquals(2, result.remaining());

        InOrder order =
                inOrder(quotaService, chatService);

        order.verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat"
        );

        order.verify(chatService).handleMessage(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        );

        order.verify(quotaService).commit(
                USER_ID,
                REQUEST_ID
        );

        verifyNoMoreInteractions(
                quotaService,
                chatService
        );
    }

    @Test
    public void shouldReleaseReservationWhenModelInvocationFails() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        RuntimeException modelFailure =
                new RuntimeException("model unavailable");

        when(chatService.handleMessage(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenThrow(modelFailure);

        MeteredAgentChatFacade facade =
                new MeteredAgentChatFacade(
                        quotaService,
                        chatService
                );

        try {
            facade.chat(
                    USER_ID,
                    REQUEST_ID,
                    AGENT_ID,
                    SESSION_ID,
                    MESSAGE
            );

            fail("模型调用失败时应该抛出原异常");
        } catch (RuntimeException actual) {
            assertSame(modelFailure, actual);
        }

        InOrder order =
                inOrder(quotaService, chatService);

        order.verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat"
        );

        order.verify(chatService).handleMessage(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        );

        order.verify(quotaService).release(
                USER_ID,
                REQUEST_ID
        );

        verify(quotaService, never()).commit(
                USER_ID,
                REQUEST_ID
        );
    }

    @Test
    public void shouldReleaseReservationWhenModelReturnsBlankContent() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        AgentChatResultVO blankResult =
                AgentChatResultVO.builder()
                        .content("   ")
                        .traces(List.of())
                        .build();

        when(chatService.handleMessage(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenReturn(blankResult);

        when(quotaService.commit(USER_ID, REQUEST_ID))
                .thenReturn(
                        new QuotaSnapshotEntity(
                                3,
                                0,
                                1,
                                0
                        )
                );

        MeteredAgentChatFacade facade =
                new MeteredAgentChatFacade(
                        quotaService,
                        chatService
                );

        try {
            facade.chat(
                    USER_ID,
                    REQUEST_ID,
                    AGENT_ID,
                    SESSION_ID,
                    MESSAGE
            );

            fail("模型返回空白内容时应该拒绝本次响应");
        } catch (AppException exception) {
            assertEquals("0007", exception.getCode());
        }

        InOrder order =
                inOrder(quotaService, chatService);

        order.verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat"
        );

        order.verify(chatService).handleMessage(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        );

        order.verify(quotaService).release(
                USER_ID,
                REQUEST_ID
        );

        verify(quotaService, never()).commit(
                USER_ID,
                REQUEST_ID
        );
    }

    @Test
    public void shouldCreateTrustedUserSessionWhenSessionIdIsBlank() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        String generatedSessionId = "generated-session";

        AgentChatResultVO modelResult =
                AgentChatResultVO.builder()
                        .content("drawio xml")
                        .traces(List.of())
                        .build();

        when(chatService.createSession(
                AGENT_ID,
                USER_ID
        )).thenReturn(generatedSessionId);

        when(chatService.handleMessage(
                AGENT_ID,
                USER_ID,
                "   ",
                MESSAGE
        )).thenReturn(modelResult);

        when(chatService.handleMessage(
                AGENT_ID,
                USER_ID,
                generatedSessionId,
                MESSAGE
        )).thenReturn(modelResult);

        when(quotaService.commit(USER_ID, REQUEST_ID))
                .thenReturn(
                        new QuotaSnapshotEntity(
                                3,
                                0,
                                1,
                                0
                        )
                );

        MeteredAgentChatFacade facade =
                new MeteredAgentChatFacade(
                        quotaService,
                        chatService
                );

        MeteredChatResult result =
                facade.chat(
                        USER_ID,
                        REQUEST_ID,
                        AGENT_ID,
                        "   ",
                        MESSAGE
                );

        assertSame(modelResult, result.result());
        assertEquals(2, result.remaining());

        InOrder order =
                inOrder(quotaService, chatService);

        order.verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat"
        );

        order.verify(chatService).createSession(
                AGENT_ID,
                USER_ID
        );

        order.verify(chatService).handleMessage(
                AGENT_ID,
                USER_ID,
                generatedSessionId,
                MESSAGE
        );

        order.verify(quotaService).commit(
                USER_ID,
                REQUEST_ID
        );

        verifyNoMoreInteractions(
                quotaService,
                chatService
        );
    }

}