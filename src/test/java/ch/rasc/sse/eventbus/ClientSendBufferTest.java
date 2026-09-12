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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ch.rasc.sse.eventbus.ClientSendBuffer.DeliveryListener;
import ch.rasc.sse.eventbus.ClientSendBuffer.EventSink;
import ch.rasc.sse.eventbus.ClientSendBuffer.OfferResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ClientSendBufferTest {

	@RepeatedTest(25)
	void concurrentOverflowsDisconnectAndCountClientOnlyOnce() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger disconnects = new AtomicInteger();
		AtomicInteger notifications = new AtomicInteger();
		var producers = Executors.newFixedThreadPool(8);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try (ClientSendBuffer buffer = new ClientSendBuffer("race", 1, OverflowPolicy.DISCONNECT,
				(builder, heartbeat) -> {
				}, noopDeliveryListener(), closedBuffer -> disconnects.incrementAndGet(),
				event -> notifications.incrementAndGet(), new SseBackpressureMetrics(registry))) {
			buffer.offer(clientEvent("race", "queued"));
			List<Future<OfferResult>> offers = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				offers.add(producers.submit(() -> {
					start.await();
					return buffer.offer(clientEvent("race", "overflow"));
				}));
			}
			start.countDown();
			List<OfferResult> results = new ArrayList<>();
			for (Future<OfferResult> offer : offers) {
				results.add(offer.get(3, TimeUnit.SECONDS));
			}
			assertThat(results).filteredOn(result -> result == OfferResult.DISCONNECTED).hasSize(1);
			assertThat(results).filteredOn(result -> result == OfferResult.CLOSED).hasSize(7);
			await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(notifications).hasValue(1));
			assertThat(disconnects).hasValue(1);
			assertThat(buffer.queueSize()).isZero();
			assertThat(registry.get("sse.eventbus.client.buffer.overflow.total").counter().count()).isEqualTo(1);
			assertThat(registry.get("sse.eventbus.client.buffer.disconnected.clients").counter().count()).isEqualTo(1);
		}
		finally {
			start.countDown();
			producers.shutdownNow();
			assertThat(producers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
			registry.close();
		}
	}

	@RepeatedTest(25)
	void concurrentStartCreatesOnlyOneDispatcher() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		var starters = Executors.newFixedThreadPool(8);
		try (ClientSendBuffer buffer = new ClientSendBuffer("concurrent-start", 1, OverflowPolicy.DROP,
				(builder, heartbeat) -> {
				}, noopDeliveryListener(), null, null, null)) {
			List<Future<?>> tasks = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				tasks.add(starters.submit(() -> {
					awaitIgnoringInterrupts(start);
					buffer.start();
				}));
			}
			start.countDown();
			for (Future<?> task : tasks) {
				task.get(3, TimeUnit.SECONDS);
			}
			assertThat(Thread.getAllStackTraces().keySet())
				.filteredOn(thread -> thread.getName().equals("sse-client-send-concurrent-start"))
				.hasSize(1);
			buffer.close();
			buffer.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
		}
		finally {
			start.countDown();
			starters.shutdownNow();
			assertThat(starters.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void throwingSlowClientListenerDoesNotStopDelivery() {
		AtomicInteger sent = new AtomicInteger();
		try (ClientSendBuffer buffer = new ClientSendBuffer("throwing-listener", 1, OverflowPolicy.DROP,
				(builder, heartbeat) -> sent.incrementAndGet(), noopDeliveryListener(), null, event -> {
					throw new IllegalStateException("listener failed");
				}, null)) {
			buffer.offer(clientEvent("throwing-listener", "accepted"));
			assertThat(buffer.offer(clientEvent("throwing-listener", "overflow"))).isEqualTo(OfferResult.DROPPED);
			buffer.start();
			buffer.drain(2000);
			assertThat(sent).hasValue(1);
		}
	}

	private static ClientEvent clientEvent(String id, String data) {
		Client client = new Client(id, new SseEmitter(0L), false);
		return new ClientEvent(client, SseEvent.of("test", data), data);
	}

	private static DeliveryListener noopDeliveryListener() {
		return new DeliveryListener() {
			@Override
			public void delivered(ClientEvent event) {
				// nothing here
			}

			@Override
			public void failed(ClientEvent event, Exception exception) {
				// nothing here
			}
		};
	}

	@Test
	void dispatchDeliversEventsInOrder() {
		List<String> delivered = new CopyOnWriteArrayList<>();
		DeliveryListener listener = new DeliveryListener() {
			@Override
			public void delivered(ClientEvent event) {
				delivered.add(String.valueOf(event.getSseEvent().data()));
			}

			@Override
			public void failed(ClientEvent event, Exception exception) {
				// nothing here
			}
		};
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 10, OverflowPolicy.DROP, (builder, heartbeat) -> {
		}, listener, null, null, null);
		buffer.start();
		buffer.offer(clientEvent("c1", "1"));
		buffer.offer(clientEvent("c1", "2"));
		buffer.offer(clientEvent("c1", "3"));
		await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(delivered).containsExactly("1", "2", "3"));
		buffer.close();
	}

	@Test
	void deliveredIsAccountedAfterSinkRuns() {
		List<String> sent = new CopyOnWriteArrayList<>();
		List<String> delivered = new CopyOnWriteArrayList<>();
		DeliveryListener listener = new DeliveryListener() {
			@Override
			public void delivered(ClientEvent event) {
				delivered.add(String.valueOf(event.getSseEvent().data()));
			}

			@Override
			public void failed(ClientEvent event, Exception exception) {
				// nothing here
			}
		};
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 10, OverflowPolicy.DROP,
				(builder, heartbeat) -> sent.add(builder.toString()), listener, null, null, null);
		buffer.start();
		buffer.offer(clientEvent("c1", "1"));
		await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(delivered).containsExactly("1"));
		assertThat(sent).hasSize(1);
		buffer.close();
	}

	@Test
	void sinkFailureAccountsFailedAndNotifiesDisconnect() {
		AtomicBoolean disconnected = new AtomicBoolean();
		AtomicInteger failedEvents = new AtomicInteger();
		DeliveryListener listener = new DeliveryListener() {
			@Override
			public void delivered(ClientEvent event) {
				// nothing here
			}

			@Override
			public void failed(ClientEvent event, Exception exception) {
				failedEvents.incrementAndGet();
			}
		};
		EventSink failingSink = (builder, heartbeat) -> {
			throw new IOException("connection lost");
		};
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 5, OverflowPolicy.DROP, failingSink, listener,
				closedBuffer -> disconnected.set(true), null, null);
		buffer.start();
		buffer.offer(clientEvent("c1", "1"));
		await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
			assertThat(disconnected).isTrue();
			assertThat(failedEvents).hasValue(1);
		});
		assertThat(buffer.isClosed()).isTrue();
		buffer.close();
	}

	@Test
	void dropPolicyRejectsOverflowAndNotifiesListener() {
		List<SlowClientEvent> slowClientEvents = new CopyOnWriteArrayList<>();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 2, OverflowPolicy.DROP, (builder, heartbeat) -> {
		}, noopDeliveryListener(), null, slowClientEvents::add, null);

		// Fill the buffer while the dispatcher is not running to keep the queue size
		// deterministic, then start it so the pending notification is delivered
		assertThat(buffer.offer(clientEvent("c1", "1"))).isEqualTo(OfferResult.ACCEPTED);
		assertThat(buffer.offer(clientEvent("c1", "2"))).isEqualTo(OfferResult.ACCEPTED);
		assertThat(buffer.offer(clientEvent("c1", "3"))).isEqualTo(OfferResult.DROPPED);

		assertThat(buffer.queueSize()).isEqualTo(2);
		assertThat(buffer.isClosed()).isFalse();

		buffer.start();
		await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(slowClientEvents).hasSize(1));
		SlowClientEvent slowClientEvent = slowClientEvents.get(0);
		assertThat(slowClientEvent.clientId()).isEqualTo("c1");
		assertThat(slowClientEvent.policy()).isEqualTo(OverflowPolicy.DROP);
		assertThat(slowClientEvent.queueSize()).isEqualTo(2);
		assertThat(slowClientEvent.capacity()).isEqualTo(2);
		buffer.close();
	}

	@Test
	void disconnectPolicyClosesBufferAndNotifies() {
		AtomicBoolean disconnected = new AtomicBoolean();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 1, OverflowPolicy.DISCONNECT, (builder, heartbeat) -> {
		}, noopDeliveryListener(), closedBuffer -> disconnected.set(true), event -> {
		}, null);

		assertThat(buffer.offer(clientEvent("c1", "1"))).isEqualTo(OfferResult.ACCEPTED);
		assertThat(buffer.offer(clientEvent("c1", "2"))).isEqualTo(OfferResult.DISCONNECTED);

		assertThat(buffer.isClosed()).isTrue();
		await().atMost(Duration.ofSeconds(2)).untilTrue(disconnected);
		assertThat(buffer.offer(clientEvent("c1", "3"))).isEqualTo(OfferResult.CLOSED);
	}

	@Test
	void offerReturnsClosedAfterClose() {
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 5, OverflowPolicy.DROP, (builder, heartbeat) -> {
		}, noopDeliveryListener(), null, null, null);
		buffer.close();
		assertThat(buffer.offer(clientEvent("c1", "1"))).isEqualTo(OfferResult.CLOSED);
	}

	@Test
	void closeDoesNotNotifyDisconnect() {
		AtomicBoolean disconnected = new AtomicBoolean();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 5, OverflowPolicy.DISCONNECT, (builder, heartbeat) -> {
		}, noopDeliveryListener(), closedBuffer -> disconnected.set(true), null, null);
		buffer.close();
		assertThat(disconnected).isFalse();
	}

	@Test
	void drainDeliversQueuedEvents() {
		List<String> sent = new CopyOnWriteArrayList<>();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 10, OverflowPolicy.DROP, (builder, heartbeat) -> {
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			sent.add(builder.toString());
		}, noopDeliveryListener(), null, null, null);
		buffer.start();
		buffer.offer(clientEvent("c1", "1"));
		buffer.offer(clientEvent("c1", "2"));
		buffer.offer(clientEvent("c1", "3"));
		buffer.drain(2000);
		assertThat(sent).hasSize(3);
		buffer.close();
	}

	@Test
	void drainingRejectsNewEventsEvenAfterDispatcherExits() {
		try (ClientSendBuffer buffer = new ClientSendBuffer("drain", 2, OverflowPolicy.DROP, (builder, heartbeat) -> {
		}, noopDeliveryListener(), null, null, null)) {
			buffer.start();
			buffer.startDraining();
			assertThat(buffer.offer(clientEvent("drain", "late"))).isEqualTo(OfferResult.CLOSED);
			buffer.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
			assertThat(buffer.offer(clientEvent("drain", "later"))).isEqualTo(OfferResult.CLOSED);
			assertThat(buffer.queueSize()).isZero();
		}
	}

	@Test
	void closeDiscardsQueuedEventsAndStopsSinkThatConsumesInterrupt() throws Exception {
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger sends = new AtomicInteger();
		try (ClientSendBuffer buffer = new ClientSendBuffer("close", 2, OverflowPolicy.DROP, (builder, heartbeat) -> {
			sends.incrementAndGet();
			sending.countDown();
			awaitIgnoringInterrupts(release);
		}, noopDeliveryListener(), null, null, null)) {
			buffer.start();
			buffer.offer(clientEvent("close", "in-flight"));
			assertThat(sending.await(2, TimeUnit.SECONDS)).isTrue();
			buffer.offer(clientEvent("close", "queued"));
			buffer.close();
			release.countDown();
			buffer.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
			assertThat(sends).hasValue(1);
			assertThat(buffer.queueSize()).isZero();
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void disconnectDoesNotBlockProducerOnSlowListener() throws Exception {
		CountDownLatch listenerEntered = new CountDownLatch(1);
		CountDownLatch releaseListener = new CountDownLatch(1);
		AtomicBoolean disconnected = new AtomicBoolean();
		var producer = Executors.newSingleThreadExecutor();
		try (ClientSendBuffer buffer = new ClientSendBuffer("disconnect", 1, OverflowPolicy.DISCONNECT,
				(builder, heartbeat) -> {
				}, noopDeliveryListener(), closedBuffer -> disconnected.set(true), event -> {
					listenerEntered.countDown();
					awaitIgnoringInterrupts(releaseListener);
				}, null)) {
			buffer.offer(clientEvent("disconnect", "queued"));
			var overflow = producer.submit(() -> buffer.offer(clientEvent("disconnect", "overflow")));
			try {
				assertThat(listenerEntered.await(2, TimeUnit.SECONDS)).isTrue();
				assertThat(overflow.get(1, TimeUnit.SECONDS)).isEqualTo(OfferResult.DISCONNECTED);
				await().atMost(Duration.ofSeconds(2)).untilTrue(disconnected);
				assertThat(buffer.queueSize()).isZero();
			}
			finally {
				releaseListener.countDown();
			}
		}
		finally {
			releaseListener.countDown();
			producer.shutdownNow();
			assertThat(producer.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
		}
	}

	private static void awaitIgnoringInterrupts(CountDownLatch latch) {
		boolean released = false;
		while (!released) {
			try {
				released = latch.await(5, TimeUnit.SECONDS);
				if (!released) {
					throw new IllegalStateException("Test latch was not released");
				}
			}
			catch (InterruptedException ex) {
				// Model a transport or callback that consumes interruption.
			}
		}
	}

	@Test
	void recordsMetricsOnOverflow() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SseBackpressureMetrics metrics = new SseBackpressureMetrics(registry);
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 1, OverflowPolicy.DROP, (builder, heartbeat) -> {
		}, noopDeliveryListener(), null, event -> {
		}, metrics);

		assertThat(buffer.offer(clientEvent("c1", "1"))).isEqualTo(OfferResult.ACCEPTED);
		assertThat(buffer.offer(clientEvent("c1", "2"))).isEqualTo(OfferResult.DROPPED);

		assertThat(registry.get("sse.eventbus.client.buffer.overflow.total").counter().count()).isEqualTo(1);
		assertThat(registry.get("sse.eventbus.client.buffer.dropped.events").counter().count()).isEqualTo(1);

		buffer.start();
		await().atMost(Duration.ofSeconds(2))
			.untilAsserted(() -> assertThat(
					registry.get("sse.eventbus.client.buffer.slow.client.notifications").counter().count())
				.isEqualTo(1));
		buffer.close();
	}

}
