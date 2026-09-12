/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ch.rasc.sse.eventbus;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SseEventBusBackpressureLifecycleTest {

	@Test
	void replayReplacesEventsAlreadyWaitingInClientBuffer() throws Exception {
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Object> delivered = new CopyOnWriteArrayList<>();
		try (Fixture fixture = new Fixture()) {
			fixture.store = new InMemoryReplayStore();
			fixture.rebuildBus();
			fixture.register("client", blockingEmitter(sending, release));
			doAnswer(invocation -> {
				ClientEvent event = invocation.getArgument(0);
				delivered.add(event.getSseEvent().data());
				return null;
			}).when(fixture.listener).afterEventSent(any(), isNull());
			fixture.send("client", "in-flight");
			assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
			fixture.bus.handleEvent(SseEvent.builder().id("1").data("one").build());
			fixture.bus.handleEvent(SseEvent.builder().id("2").data("two").build());
			fixture.bus.replayMissedEvents("client", "1");
			release.countDown();
			fixture.bus.cleanUp();
			assertThat(delivered).containsExactly("in-flight", "two");
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void heartbeatUsesBufferWithoutCompletingOneShotConnection() throws Exception {
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (Fixture fixture = new Fixture()) {
			SseEmitter emitter = blockingEmitter(sending, release);
			fixture.bus.registerClient("client", emitter, true);
			fixture.bus.subscribe("client");
			assertTimeoutPreemptively(Duration.ofSeconds(1),
					() -> ReflectionTestUtils.invokeMethod(fixture.bus, "sendHeartbeat"));
			assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
			fixture.send("client", "message");
			verify(emitter, never()).complete();
			verify(fixture.listener, never()).afterEventSent(any(), any());
			release.countDown();
			fixture.bus.cleanUp();
			verify(emitter).complete();
			verify(fixture.listener).afterEventSent(any(), isNull());
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void lateDisconnectCannotRemoveNewBufferOrItsGauge() throws Exception {
		CountDownLatch disconnecting = new CountDownLatch(1);
		CountDownLatch releaseDisconnect = new CountDownLatch(1);
		CountDownLatch notified = new CountDownLatch(1);
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch releaseSend = new CountDownLatch(1);
		AtomicBoolean delayDisconnect = new AtomicBoolean();
		Thread producer = Thread.currentThread();
		try (Fixture fixture = new Fixture()) {
			fixture.clients = new ConcurrentHashMap<>() {
				@Override
				public Client compute(String key,
						BiFunction<? super String, ? super Client, ? extends Client> function) {
					if (!Thread.currentThread().equals(producer) && delayDisconnect.compareAndSet(true, false)) {
						disconnecting.countDown();
						awaitIgnoringInterrupts(releaseDisconnect);
					}
					return super.compute(key, function);
				}
			};
			fixture.policy = OverflowPolicy.DISCONNECT;
			fixture.slowListener = event -> notified.countDown();
			fixture.rebuildBus();
			fixture.register("client", blockingEmitter(sending, releaseSend));
			fixture.send("client", "in-flight");
			assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
			fixture.send("client", "queued-1");
			fixture.send("client", "queued-2");
			delayDisconnect.set(true);
			fixture.send("client", "overflow");
			assertThat(disconnecting.await(3, TimeUnit.SECONDS)).isTrue();
			SseEmitter replacement = mock(SseEmitter.class);
			fixture.bus.registerClient("client", replacement);
			releaseDisconnect.countDown();
			assertThat(notified.await(3, TimeUnit.SECONDS)).isTrue();
			assertThat(fixture.bus.isClientRegistered("client")).isTrue();
			assertThat(fixture.registry.get("sse.eventbus.client.buffer.active.clients").gauge().value()).isEqualTo(1);
			assertThat(fixture.registry.get("sse.eventbus.client.buffer.queue.size").gauge().value()).isZero();
			verify(replacement, never()).complete();
			fixture.send("client", "new");
			await().atMost(Duration.ofSeconds(3))
				.untilAsserted(() -> verify(replacement).send(any(SseEmitter.SseEventBuilder.class)));
		}
		finally {
			releaseSend.countDown();
			releaseDisconnect.countDown();
		}
	}

	@Test
	void shutdownUsesOneDeadlineForAllBlockedClients() throws Exception {
		CountDownLatch sending = new CountDownLatch(4);
		CountDownLatch release = new CountDownLatch(1);
		try (Fixture fixture = new Fixture()) {
			for (int i = 0; i < 4; i++) {
				String id = "blocked-" + i;
				fixture.register(id, blockingEmitter(sending, release));
				fixture.send(id, "in-flight");
			}
			assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
			assertTimeoutPreemptively(Duration.ofMillis(2500), fixture.bus::cleanUp);
			assertThat(fixture.registry.get("sse.eventbus.client.buffer.active.clients").gauge().value()).isZero();
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void failureHookCanReconnectWithoutOldBufferUnregisteringReplacement() throws Exception {
		try (Fixture fixture = new Fixture()) {
			SseEmitter oldEmitter = mock(SseEmitter.class);
			SseEmitter replacement = mock(SseEmitter.class);
			doThrow(new IOException("disconnected")).when(oldEmitter).send(any(SseEmitter.SseEventBuilder.class));
			fixture.register("client", oldEmitter);
			ClientSendBuffer oldBuffer = fixture.buffer("client");
			doAnswer(invocation -> {
				fixture.bus.registerClient("client", replacement);
				return null;
			}).when(fixture.listener).afterEventSent(any(), any(IOException.class));
			fixture.send("client", "failure");
			await().atMost(Duration.ofSeconds(3)).until(oldBuffer::isClosed);
			oldBuffer.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
			assertThat(fixture.bus.isClientRegistered("client")).isTrue();
			assertThat(fixture.bus.getSubscribers(SseEvent.DEFAULT_EVENT)).containsExactly("client");
			verify(replacement, never()).complete();
			fixture.send("client", "success");
			await().atMost(Duration.ofSeconds(3))
				.untilAsserted(() -> verify(replacement).send(any(SseEmitter.SseEventBuilder.class)));
			assertThat(fixture.bus.getErrorQueueSize()).isZero();
		}
	}

	@Test
	void oldBufferNeverSendsToReplacementEmitter() throws Exception {
		CountDownLatch building = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (Fixture fixture = new Fixture()) {
			SseEmitter oldEmitter = mock(SseEmitter.class);
			SseEmitter replacement = mock(SseEmitter.class);
			fixture.register("client", oldEmitter);
			ClientSendBuffer oldBuffer = fixture.buffer("client");
			ClientEvent event = new ClientEvent(fixture.client("client"), SseEvent.ofData("old"), null) {
				@Override
				public SseEmitter.SseEventBuilder createSseEventBuilder() {
					building.countDown();
					awaitIgnoringInterrupts(release);
					return super.createSseEventBuilder();
				}
			};
			oldBuffer.offer(event);
			assertThat(building.await(3, TimeUnit.SECONDS)).isTrue();
			fixture.bus.registerClient("client", replacement);
			release.countDown();
			oldBuffer.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
			verify(replacement, never()).send(any(SseEmitter.SseEventBuilder.class));
			fixture.send("client", "new");
			await().atMost(Duration.ofSeconds(3))
				.untilAsserted(() -> verify(replacement).send(any(SseEmitter.SseEventBuilder.class)));
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void deliveryHookAndLastTransferWaitForActualSend() throws Exception {
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (Fixture fixture = new Fixture()) {
			fixture.register("client", blockingEmitter(sending, release));
			long lastTransfer = fixture.client("client").lastTransfer();
			fixture.send("client", "pending");
			assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
			verify(fixture.listener, never()).afterEventSent(any(), any());
			assertThat(fixture.client("client").lastTransfer()).isEqualTo(lastTransfer);
			release.countDown();
			await().atMost(Duration.ofSeconds(3))
				.untilAsserted(() -> verify(fixture.listener).afterEventSent(any(), isNull()));
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void queueGaugesFollowReconnectAndUnregister() throws Exception {
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (Fixture fixture = new Fixture()) {
			fixture.register("client", mock(SseEmitter.class));
			ClientSendBuffer oldBuffer = fixture.buffer("client");
			fixture.bus.registerClient("client", blockingEmitter(sending, release));
			assertThat(oldBuffer.isClosed()).isTrue();
			fixture.send("client", "sending");
			assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
			fixture.send("client", "queued-1");
			fixture.send("client", "queued-2");
			assertThat(fixture.registry.get("sse.eventbus.client.buffer.queue.size")
				.tag("clientId", "client")
				.gauge()
				.value()).isEqualTo(2);
			assertThat(fixture.registry.get("sse.eventbus.client.buffer.active.clients").gauge().value()).isEqualTo(1);
			fixture.bus.unregisterClient("client");
			assertThat(fixture.registry.find("sse.eventbus.client.buffer.queue.size").gauge()).isNull();
			assertThat(fixture.registry.get("sse.eventbus.client.buffer.active.clients").gauge().value()).isZero();
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void shutdownDrainsAcceptedEventsWithoutScheduler() throws Exception {
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Object> delivered = new CopyOnWriteArrayList<>();
		try (Fixture fixture = new Fixture()) {
			fixture.register("client", blockingEmitter(sending, release));
			doAnswer(invocation -> {
				ClientEvent event = invocation.getArgument(0);
				delivered.add(event.getSseEvent().data());
				return null;
			}).when(fixture.listener).afterEventSent(any(), isNull());
			fixture.send("client", "1");
			assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
			fixture.send("client", "2");
			fixture.send("client", "3");
			release.countDown();
			fixture.bus.cleanUp();
			assertThat(delivered).containsExactly("1", "2", "3");
			assertThat(fixture.registry.find("sse.eventbus.client.buffer.queue.size").gauge()).isNull();
			assertThat(fixture.registry.get("sse.eventbus.client.buffer.active.clients").gauge().value()).isZero();
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void blockedDisconnectCallbackDoesNotStopSharedSendWorker() throws Exception {
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch releaseSend = new CountDownLatch(1);
		CountDownLatch notifying = new CountDownLatch(1);
		CountDownLatch releaseListener = new CountDownLatch(1);
		try (Fixture fixture = new Fixture()) {
			fixture.policy = OverflowPolicy.DISCONNECT;
			fixture.scheduler = Executors.newScheduledThreadPool(3);
			fixture.slowListener = event -> {
				notifying.countDown();
				awaitIgnoringInterrupts(releaseListener);
			};
			fixture.rebuildBus();
			fixture.register("slow", blockingEmitter(sending, releaseSend));
			SseEmitter healthy = mock(SseEmitter.class);
			fixture.register("healthy", healthy);
			fixture.bus.init();
			fixture.send("slow", "in-flight");
			assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
			fixture.send("slow", "queued-1");
			fixture.send("slow", "queued-2");
			fixture.send("slow", "overflow");
			assertThat(notifying.await(3, TimeUnit.SECONDS)).isTrue();
			fixture.send("healthy", "success");
			await().atMost(Duration.ofSeconds(2))
				.untilAsserted(() -> verify(healthy).send(any(SseEmitter.SseEventBuilder.class)));
			assertThat(fixture.bus.isClientRegistered("slow")).isFalse();
			assertThat(fixture.bus.getErrorQueueSize()).isZero();
		}
		finally {
			releaseSend.countDown();
			releaseListener.countDown();
		}
	}

	private static SseEmitter blockingEmitter(CountDownLatch sending, CountDownLatch release) throws IOException {
		SseEmitter emitter = mock(SseEmitter.class);
		doAnswer(invocation -> {
			sending.countDown();
			awaitIgnoringInterrupts(release);
			return null;
		}).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
		return emitter;
	}

	private static void awaitIgnoringInterrupts(CountDownLatch latch) {
		while (true) {
			try {
				if (!latch.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Test latch was not released");
				}
				return;
			}
			catch (InterruptedException ex) {
				// Model a transport or callback that consumes interruption.
			}
		}
	}

	private static class Fixture implements SseEventBusConfigurer, AutoCloseable {

		ConcurrentMap<String, Client> clients = new ConcurrentHashMap<>();

		final SimpleMeterRegistry registry = new SimpleMeterRegistry();

		final SseEventBusListener listener = mock(SseEventBusListener.class);

		OverflowPolicy policy = OverflowPolicy.DROP;

		SlowClientListener slowListener = event -> {
		};

		@Nullable ScheduledExecutorService scheduler;

		@Nullable ReplayStore store;

		SseEventBus bus = new SseEventBus(this, new DefaultSubscriptionRegistry(), null, null);

		void rebuildBus() {
			this.bus.cleanUp();
			this.registry.clear();
			this.bus = new SseEventBus(this, new DefaultSubscriptionRegistry(), null, this.store);
		}

		void register(String id, SseEmitter emitter) {
			this.bus.registerClient(id, emitter);
			this.bus.subscribe(id);
		}

		void send(String id, String data) {
			this.bus.handleEvent(SseEvent.builder().addClientId(id).data(data).build());
		}

		Client client(String id) {
			return Objects.requireNonNull(this.clients.get(id));
		}

		ClientSendBuffer buffer(String id) {
			return Objects.requireNonNull(client(id).sendBuffer());
		}

		@Override
		public ConcurrentMap<String, Client> clients() {
			return this.clients;
		}

		@Override
		public int clientSendBufferCapacity() {
			return 2;
		}

		@Override
		public OverflowPolicy overflowPolicy() {
			return this.policy;
		}

		@Override
		public SlowClientListener slowClientListener() {
			return this.slowListener;
		}

		@Override
		public SseEventBusListener listener() {
			return this.listener;
		}

		@Override
		public MeterRegistry meterRegistry() {
			return this.registry;
		}

		@Override
		public @Nullable ScheduledExecutorService taskScheduler() {
			return this.scheduler;
		}

		@Override
		public void close() {
			this.bus.cleanUp();
			this.registry.close();
		}

	}

}
