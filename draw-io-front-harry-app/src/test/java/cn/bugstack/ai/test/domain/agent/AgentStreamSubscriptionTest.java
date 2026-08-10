package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.trigger.http.AgentStreamSubscription;
import io.reactivex.rxjava3.disposables.Disposable;
import org.junit.Assert;
import org.junit.Test;

public class AgentStreamSubscriptionTest {

    @Test
    public void shouldDisposeRegisteredSubscription() {
        AgentStreamSubscription subscription = new AgentStreamSubscription();
        Disposable disposable = Disposable.empty();
        subscription.set(disposable);

        subscription.dispose();

        Assert.assertTrue(disposable.isDisposed());
    }

    @Test
    public void shouldDisposeSubscriptionRegisteredAfterDisconnect() {
        AgentStreamSubscription subscription = new AgentStreamSubscription();
        Disposable disposable = Disposable.empty();
        subscription.dispose();

        subscription.set(disposable);

        Assert.assertTrue(disposable.isDisposed());
    }
}
