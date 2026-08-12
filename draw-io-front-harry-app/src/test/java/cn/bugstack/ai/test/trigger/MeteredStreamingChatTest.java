package cn.bugstack.ai.test.trigger;

import cn.bugstack.ai.domain.agent.model.valobj.AgentOutputEventVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import cn.bugstack.ai.trigger.http.MeteredAgentChatFacade;
import cn.bugstack.ai.trigger.http.MeteredAgentOutput;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import io.reactivex.rxjava3.processors.PublishProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class MeteredStreamingChatTest {

    private static final String USER_ID =
            "22222222-2222-2222-2222-222222222222";

    private static final String REQUEST_ID =
            "44444444-4444-4444-4444-444444444444";

    private static final String AGENT_ID = "100001";
    private static final String SESSION_ID = "session-1";
    private static final String MESSAGE = "hello";

    /**
     * 订阅流时预占一次。
     * 第一条非空事件出现时提交一次，并在该事件附带 remaining=2。
     * 第二条事件不重复提交，remaining=null。
     * 已经产生有效输出，流正常完成时不能释放额度。
     */
    @Test
    public void shouldCommitOnlyOnFirstEffectiveOutput() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        AgentOutputEventVO firstOutput =
                AgentOutputEventVO.builder()
                        .type(AgentOutputEventVO.Type.TRACE)
                        .agentName("planner")
                        .content("first output")
                        .completed(false)
                        .build();

        AgentOutputEventVO secondOutput =
                AgentOutputEventVO.builder()
                        .type(AgentOutputEventVO.Type.FINAL)
                        .agentName("drawio-agent")
                        .content("second output")
                        .completed(true)
                        .build();

        when(chatService.handleMessageStream(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenReturn(
                Flowable.just(
                        firstOutput,
                        secondOutput
                )
        );

        when(quotaService.commit(
                USER_ID,
                REQUEST_ID
        )).thenReturn(
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

        TestSubscriber<MeteredAgentOutput> subscriber =
                facade.chatStream(
                                USER_ID,
                                REQUEST_ID,
                                AGENT_ID,
                                SESSION_ID,
                                MESSAGE
                        )
                        .test();

        subscriber
                .assertComplete()
                .assertNoErrors()
                .assertValueCount(2);

        MeteredAgentOutput first =
                subscriber.values().get(0);

        MeteredAgentOutput second =
                subscriber.values().get(1);

        assertEquals(firstOutput, first.output());
        assertEquals(Integer.valueOf(2), first.remaining());

        assertEquals(secondOutput, second.output());
        assertNull(second.remaining());

        verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat_stream"
        );

        verify(quotaService, times(1)).commit(
                USER_ID,
                REQUEST_ID
        );

        verify(quotaService, never()).release(
                USER_ID,
                REQUEST_ID
        );
    }

    @Test
    public void shouldReleaseReservationWhenUpstreamFailsBeforeOutput() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        RuntimeException modelFailure =
                new RuntimeException("stream unavailable");

        when(chatService.handleMessageStream(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenReturn(
                Flowable.error(modelFailure)
        );

        MeteredAgentChatFacade facade =
                new MeteredAgentChatFacade(
                        quotaService,
                        chatService
                );

        TestSubscriber<MeteredAgentOutput> subscriber =
                facade.chatStream(
                                USER_ID,
                                REQUEST_ID,
                                AGENT_ID,
                                SESSION_ID,
                                MESSAGE
                        )
                        .test();

        subscriber
                .assertError(modelFailure)
                .assertNoValues()
                .assertNotComplete();

        verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat_stream"
        );

        verify(quotaService).release(
                USER_ID,
                REQUEST_ID
        );

        verify(quotaService, never()).commit(
                USER_ID,
                REQUEST_ID
        );
    }

    @Test
    public void shouldReleaseReservationWhenCommitFails() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        AgentOutputEventVO output =
                AgentOutputEventVO.builder()
                        .type(AgentOutputEventVO.Type.FINAL)
                        .agentName("drawio-agent")
                        .content("effective output")
                        .completed(true)
                        .build();

        RuntimeException commitFailure =
                new RuntimeException("quota commit failed");

        when(chatService.handleMessageStream(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenReturn(
                Flowable.just(output)
        );

        when(quotaService.commit(
                USER_ID,
                REQUEST_ID
        )).thenThrow(commitFailure);

        MeteredAgentChatFacade facade =
                new MeteredAgentChatFacade(
                        quotaService,
                        chatService
                );

        TestSubscriber<MeteredAgentOutput> subscriber =
                facade.chatStream(
                                USER_ID,
                                REQUEST_ID,
                                AGENT_ID,
                                SESSION_ID,
                                MESSAGE
                        )
                        .test();

        subscriber
                .assertError(commitFailure)
                .assertNoValues()
                .assertNotComplete();

        verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat_stream"
        );

        verify(quotaService).commit(
                USER_ID,
                REQUEST_ID
        );

        verify(quotaService).release(
                USER_ID,
                REQUEST_ID
        );
    }

    @Test
    public void shouldReleaseReservationWhenCancelledBeforeOutput() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        when(chatService.handleMessageStream(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenReturn(
                Flowable.never()
        );

        MeteredAgentChatFacade facade =
                new MeteredAgentChatFacade(
                        quotaService,
                        chatService
                );

        TestSubscriber<MeteredAgentOutput> subscriber =
                facade.chatStream(
                                USER_ID,
                                REQUEST_ID,
                                AGENT_ID,
                                SESSION_ID,
                                MESSAGE
                        )
                        .test();

        subscriber.cancel();

        verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat_stream"
        );

        verify(quotaService).release(
                USER_ID,
                REQUEST_ID
        );

        verify(quotaService, never()).commit(
                USER_ID,
                REQUEST_ID
        );
    }

    @Test
    public void shouldReleaseReservationWhenStreamCompletesWithoutOutput() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        when(chatService.handleMessageStream(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenReturn(
                Flowable.empty()
        );

        MeteredAgentChatFacade facade =
                new MeteredAgentChatFacade(
                        quotaService,
                        chatService
                );

        TestSubscriber<MeteredAgentOutput> subscriber =
                facade.chatStream(
                                USER_ID,
                                REQUEST_ID,
                                AGENT_ID,
                                SESSION_ID,
                                MESSAGE
                        )
                        .test();

        subscriber
                .assertComplete()
                .assertNoErrors()
                .assertNoValues();

        verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat_stream"
        );

        verify(quotaService).release(
                USER_ID,
                REQUEST_ID
        );

        verify(quotaService, never()).commit(
                USER_ID,
                REQUEST_ID
        );
    }

    @Test
    public void shouldNotCommitBlankOutputAndShouldReleaseOnCompletion() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        AgentOutputEventVO blankOutput =
                AgentOutputEventVO.builder()
                        .type(AgentOutputEventVO.Type.TRACE)
                        .agentName("planner")
                        .content("   ")
                        .completed(false)
                        .build();

        when(chatService.handleMessageStream(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenReturn(
                Flowable.just(blankOutput)
        );

        MeteredAgentChatFacade facade =
                new MeteredAgentChatFacade(
                        quotaService,
                        chatService
                );

        TestSubscriber<MeteredAgentOutput> subscriber =
                facade.chatStream(
                                USER_ID,
                                REQUEST_ID,
                                AGENT_ID,
                                SESSION_ID,
                                MESSAGE
                        )
                        .test();

        subscriber
                .assertComplete()
                .assertNoErrors()
                .assertValueCount(1);

        MeteredAgentOutput meteredOutput =
                subscriber.values().get(0);

        assertEquals(
                blankOutput,
                meteredOutput.output()
        );

        assertNull(
                meteredOutput.remaining()
        );

        verify(quotaService, never()).commit(
                USER_ID,
                REQUEST_ID
        );

        verify(quotaService).release(
                USER_ID,
                REQUEST_ID
        );
    }

    @Test
    public void shouldNotReleaseWhenCancelledAfterEffectiveOutput() {
        IAiQuotaService quotaService =
                mock(IAiQuotaService.class);

        IChatService chatService =
                mock(IChatService.class);

        PublishProcessor<AgentOutputEventVO> processor =
                PublishProcessor.create();

        AgentOutputEventVO effectiveOutput =
                AgentOutputEventVO.builder()
                        .type(AgentOutputEventVO.Type.TRACE)
                        .agentName("planner")
                        .content("effective output")
                        .completed(false)
                        .build();

        when(chatService.handleMessageStream(
                AGENT_ID,
                USER_ID,
                SESSION_ID,
                MESSAGE
        )).thenReturn(processor);

        when(quotaService.commit(
                USER_ID,
                REQUEST_ID
        )).thenReturn(
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

        TestSubscriber<MeteredAgentOutput> subscriber =
                facade.chatStream(
                                USER_ID,
                                REQUEST_ID,
                                AGENT_ID,
                                SESSION_ID,
                                MESSAGE
                        )
                        .test();

        processor.onNext(effectiveOutput);

        subscriber.assertValueCount(1);

        MeteredAgentOutput meteredOutput =
                subscriber.values().get(0);

        assertEquals(
                effectiveOutput,
                meteredOutput.output()
        );

        assertEquals(
                Integer.valueOf(2),
                meteredOutput.remaining()
        );

        subscriber.cancel();

        verify(quotaService).reserve(
                USER_ID,
                REQUEST_ID,
                AGENT_ID,
                "chat_stream"
        );

        verify(quotaService, times(1)).commit(
                USER_ID,
                REQUEST_ID
        );

        verify(quotaService, never()).release(
                USER_ID,
                REQUEST_ID
        );
    }
}
