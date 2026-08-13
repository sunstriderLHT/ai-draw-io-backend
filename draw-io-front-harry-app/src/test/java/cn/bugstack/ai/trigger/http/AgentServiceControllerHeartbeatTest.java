package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.trigger.security.AuthenticatedUserProvider;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AgentServiceControllerHeartbeatTest {

    private static final String USER_ID =
            "22222222-2222-2222-2222-222222222222";

    private static final String REQUEST_ID =
            "44444444-4444-4444-4444-444444444444";

    @Test
    public void shouldScheduleHeartbeatWhenStreamStarts() {
        MeteredAgentChatFacade facade = mock(MeteredAgentChatFacade.class);
        AgentStreamHeartbeatScheduler scheduler = mock(AgentStreamHeartbeatScheduler.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(scheduler).schedule(any(Runnable.class));
        doReturn(Flowable.never()).when(facade)
                .chatStream(USER_ID, REQUEST_ID, "100004", "session-1", "draw a flowchart");
        AgentServiceController controller = controller(facade, scheduler);

        controller.chatStream(REQUEST_ID, request());

        verify(scheduler).schedule(any(Runnable.class));
        verify(facade).chatStream(
                USER_ID, REQUEST_ID, "100004", "session-1", "draw a flowchart");
    }

    @Test
    public void shouldCancelHeartbeatWhenStreamCompletes() {
        MeteredAgentChatFacade facade = mock(MeteredAgentChatFacade.class);
        AgentStreamHeartbeatScheduler scheduler = mock(AgentStreamHeartbeatScheduler.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(scheduler).schedule(any(Runnable.class));
        doReturn(Flowable.empty()).when(facade)
                .chatStream(USER_ID, REQUEST_ID, "100004", "session-1", "draw a flowchart");
        AgentServiceController controller = controller(facade, scheduler);

        controller.chatStream(REQUEST_ID, request());

        verify(heartbeat).cancel(false);
    }

    private AgentServiceController controller(
            MeteredAgentChatFacade facade,
            AgentStreamHeartbeatScheduler scheduler) {
        AgentServiceController controller = new AgentServiceController();
        AuthenticatedUserProvider userProvider = mock(AuthenticatedUserProvider.class);
        doReturn(USER_ID).when(userProvider).requireUserId();
        ReflectionTestUtils.setField(controller, "authenticatedUserProvider", userProvider);
        ReflectionTestUtils.setField(controller, "meteredAgentChatFacade", facade);
        ReflectionTestUtils.setField(controller, "heartbeatScheduler", scheduler);
        return controller;
    }

    private ChatRequestDTO request() {
        ChatRequestDTO request = new ChatRequestDTO();
        request.setAgentId("100004");
        request.setSessionId("session-1");
        request.setMessage("draw a flowchart");
        return request;
    }
}
