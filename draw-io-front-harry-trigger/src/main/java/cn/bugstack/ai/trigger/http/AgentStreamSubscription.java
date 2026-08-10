package cn.bugstack.ai.trigger.http;

import io.reactivex.rxjava3.disposables.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AgentStreamSubscription {

    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicReference<Disposable> disposable = new AtomicReference<>();

    public void set(Disposable value) {
        if (disposed.get()) {
            value.dispose();
            return;
        }

        if (!disposable.compareAndSet(null, value)) {
            value.dispose();
            return;
        }

        if (disposed.get() && disposable.compareAndSet(value, null)) {
            value.dispose();
        }
    }

    public void dispose() {
        disposed.set(true);
        Disposable value = disposable.getAndSet(null);
        if (value != null && !value.isDisposed()) {
            value.dispose();
        }
    }
}
