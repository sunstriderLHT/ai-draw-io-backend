package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AgentServiceControllerHeartbeatTest {

    @Test
    public void shouldScheduleHeartbeatWhenStreamStarts() {
        IChatService chatService = mock(IChatService.class);
        AgentStreamHeartbeatScheduler scheduler = mock(AgentStreamHeartbeatScheduler.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(scheduler).schedule(any(Runnable.class));
        doReturn(Flowable.never()).when(chatService)
                .handleMessageStream("100004", "admin", "session-1", "draw a flowchart");
        AgentServiceController controller = controller(chatService, scheduler);

        controller.chatStream(request());

        verify(scheduler).schedule(any(Runnable.class));
        verify(chatService).handleMessageStream(
                "100004", "admin", "session-1", "draw a flowchart");
    }

    @Test
    public void shouldCancelHeartbeatWhenStreamCompletes() {
        IChatService chatService = mock(IChatService.class);
        AgentStreamHeartbeatScheduler scheduler = mock(AgentStreamHeartbeatScheduler.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(scheduler).schedule(any(Runnable.class));
        doReturn(Flowable.empty()).when(chatService)
                .handleMessageStream("100004", "admin", "session-1", "draw a flowchart");
        AgentServiceController controller = controller(chatService, scheduler);

        controller.chatStream(request());

        verify(heartbeat).cancel(false);
    }

    private AgentServiceController controller(
            IChatService chatService,
            AgentStreamHeartbeatScheduler scheduler) {
        AgentServiceController controller = new AgentServiceController();
        ReflectionTestUtils.setField(controller, "chatService", chatService);
        ReflectionTestUtils.setField(controller, "heartbeatScheduler", scheduler);
        return controller;
    }

    private ChatRequestDTO request() {
        ChatRequestDTO request = new ChatRequestDTO();
        request.setAgentId("100004");
        request.setUserId("admin");
        request.setSessionId("session-1");
        request.setMessage("draw a flowchart");
        return request;
    }
}
