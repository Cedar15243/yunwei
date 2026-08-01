package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkflowDeliveryControllerTest {
    @Test
    public void foregroundNetworkAndPushShareOneFailClosedTriggerPath() {
        AtomicInteger prewarms = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        List<Long> hints = new ArrayList<>();
        FakeNetworkMonitor network = new FakeNetworkMonitor();
        WorkflowDeliveryController controller = new WorkflowDeliveryController(
                prewarms::incrementAndGet,
                hint -> {
                    hints.add(hint);
                    return true;
                },
                network,
                shutdowns::incrementAndGet);

        controller.start();
        controller.start();
        controller.onForeground();
        network.fireAvailable();
        controller.onPushHint(27L);

        assertEquals(2, prewarms.get());
        assertEquals(java.util.Arrays.asList(0L, 0L, 27L), hints);
        assertEquals(1, network.starts);

        controller.close();
        controller.close();
        controller.onForeground();
        network.fireAvailable();
        controller.onPushHint(28L);

        assertEquals(1, network.closes);
        assertEquals(1, shutdowns.get());
        assertEquals(java.util.Arrays.asList(0L, 0L, 27L), hints);
    }

    @Test
    public void prewarmFailureDoesNotBlockBackgroundReconciliation() {
        List<Long> hints = new ArrayList<>();
        WorkflowDeliveryController controller = new WorkflowDeliveryController(
                () -> { throw new IllegalStateException("session unavailable"); },
                hint -> {
                    hints.add(hint);
                    return true;
                },
                new FakeNetworkMonitor(),
                () -> { });
        controller.start();

        controller.onForeground();

        assertEquals(java.util.Collections.singletonList(0L), hints);
        assertThrows(IllegalArgumentException.class, () -> controller.onPushHint(-1L));
    }

    private static final class FakeNetworkMonitor
            implements WorkflowDeliveryController.NetworkMonitor {
        private Runnable listener;
        private int starts;
        private int closes;

        @Override
        public void start(Runnable onNetworkAvailable) {
            starts += 1;
            listener = onNetworkAvailable;
        }

        @Override
        public void close() {
            closes += 1;
            listener = null;
        }

        private void fireAvailable() {
            if (listener != null) listener.run();
        }
    }
}
