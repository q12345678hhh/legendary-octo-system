package com.acme.platform.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OutboxDispatcherTest {

    private final OutboxProperties properties = new OutboxProperties(10, 3, Duration.ofSeconds(5));

    @Test
    void marksPublishedWhenHandlerSucceeds() {
        FakeStore store = new FakeStore(List.of(event(SmokePingEventHandler.EVENT_NAME)));
        OutboxDispatcher dispatcher = new OutboxDispatcher(store, properties, List.of(new SmokePingEventHandler()));

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(store.published).containsExactly(store.claimed.get(0).eventId());
        assertThat(store.failed).isEmpty();
    }

    @Test
    void retriesWhenHandlerThrows() {
        FakeStore store = new FakeStore(List.of(event(SmokePingEventHandler.EVENT_NAME)));
        OutboxEventHandler broken = handler(SmokePingEventHandler.EVENT_NAME, () -> {
            throw new IllegalStateException("下游不可用");
        });
        OutboxDispatcher dispatcher = new OutboxDispatcher(store, properties, List.of(broken));

        dispatcher.dispatchOnce();

        assertThat(store.published).isEmpty();
        assertThat(store.failed).hasSize(1);
        assertThat(store.failed.get(0)).contains("下游不可用").contains("3").contains("PT5S");
    }

    @Test
    void retriesWhenNoHandlerIsRegistered() {
        FakeStore store = new FakeStore(List.of(event("platform.unknown.v1")));
        OutboxDispatcher dispatcher = new OutboxDispatcher(store, properties, List.of());

        dispatcher.dispatchOnce();

        assertThat(store.published).isEmpty();
        assertThat(store.failed).hasSize(1);
        assertThat(store.failed.get(0)).contains("platform.unknown.v1");
    }

    @Test
    void rejectsDuplicateHandlersAtStartup() {
        OutboxEventHandler first = handler(SmokePingEventHandler.EVENT_NAME, () -> { });
        OutboxEventHandler second = handler(SmokePingEventHandler.EVENT_NAME, () -> { });

        assertThatThrownBy(() -> new OutboxDispatcher(new FakeStore(List.of()), properties, List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SmokePingEventHandler.EVENT_NAME);
    }

    private static OutboxEvent event(String eventName) {
        return new OutboxEvent(UUID.randomUUID(), eventName, 1, UUID.randomUUID(),
                "corr-1", "trace-1", "platform-api", OffsetDateTime.now(), "{}", 1);
    }

    private static OutboxEventHandler handler(String eventName, ThrowingRunnable body) {
        return new OutboxEventHandler() {
            @Override
            public String eventName() {
                return eventName;
            }

            @Override
            public void handle(OutboxEvent event) throws Exception {
                body.run();
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FakeStore implements OutboxStore {

        private final List<OutboxEvent> claimed;
        private final List<UUID> published = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();

        private FakeStore(List<OutboxEvent> claimed) {
            this.claimed = claimed;
        }

        @Override
        public List<OutboxEvent> claim(int batchSize) {
            return claimed;
        }

        @Override
        public void markPublished(UUID eventId) {
            published.add(eventId);
        }

        @Override
        public void markFailed(UUID eventId, String error, int maxAttempts, Duration retryDelay) {
            failed.add(eventId + " " + error + " max=" + maxAttempts + " delay=" + retryDelay);
        }
    }
}
