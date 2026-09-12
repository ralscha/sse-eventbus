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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import ch.rasc.sse.eventbus.ClientSendBuffer.DeliveryListener;
import ch.rasc.sse.eventbus.ClientSendBuffer.EventSink;
import ch.rasc.sse.eventbus.ClientSendBuffer.OfferResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ClientSendBufferTest {

	private static SseEventBuilder event(String data) {
		return SseEmitter.event().data(data);
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
		List<String> delivered = new ArrayList<>();
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
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 10, OverflowPolicy.DROP, builder -> {
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
		List<String> sent = new ArrayList<>();
		List<String> delivered = new ArrayList<>();
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
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 10, OverflowPolicy.DROP, builder -> sent.add(builder.toString()),
				listener, null, null, null);
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
		EventSink failingSink = builder -> {
			throw new IOException("connection lost");
		};
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 5, OverflowPolicy.DROP, failingSink, listener,
				() -> disconnected.set(true), null, null);
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
		List<SlowClientEvent> slowClientEvents = new ArrayList<>();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 2, OverflowPolicy.DROP, builder -> {
		}, noopDeliveryListener(), null, slowClientEvents::add, null);

		assertThat(buffer.offer(clientEvent("c1", "1"))).isEqualTo(OfferResult.ACCEPTED);
		assertThat(buffer.offer(clientEvent("c1", "2"))).isEqualTo(OfferResult.ACCEPTED);
		assertThat(buffer.offer(clientEvent("c1", "3"))).isEqualTo(OfferResult.DROPPED);

		assertThat(buffer.queueSize()).isEqualTo(2);
		assertThat(buffer.isClosed()).isFalse();
		assertThat(slowClientEvents).hasSize(1);
		SlowClientEvent slowClientEvent = slowClientEvents.get(0);
		assertThat(slowClientEvent.clientId()).isEqualTo("c1");
		assertThat(slowClientEvent.policy()).isEqualTo(OverflowPolicy.DROP);
		assertThat(slowClientEvent.queueSize()).isEqualTo(2);
		assertThat(slowClientEvent.capacity()).isEqualTo(2);
	}

	@Test
	void disconnectPolicyClosesBufferAndNotifies() {
		AtomicBoolean disconnected = new AtomicBoolean();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 1, OverflowPolicy.DISCONNECT, builder -> {
		}, noopDeliveryListener(), () -> disconnected.set(true), event -> {
		}, null);

		assertThat(buffer.offer(clientEvent("c1", "1"))).isEqualTo(OfferResult.ACCEPTED);
		assertThat(buffer.offer(clientEvent("c1", "2"))).isEqualTo(OfferResult.DISCONNECTED);

		assertThat(buffer.isClosed()).isTrue();
		assertThat(disconnected).isTrue();
		assertThat(buffer.offer(clientEvent("c1", "3"))).isEqualTo(OfferResult.CLOSED);
	}

	@Test
	void offerReturnsClosedAfterClose() {
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 5, OverflowPolicy.DROP, builder -> {
		}, noopDeliveryListener(), null, null, null);
		buffer.close();
		assertThat(buffer.offer(clientEvent("c1", "1"))).isEqualTo(OfferResult.CLOSED);
	}

	@Test
	void closeDoesNotNotifyDisconnect() {
		AtomicBoolean disconnected = new AtomicBoolean();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 5, OverflowPolicy.DISCONNECT, builder -> {
		}, noopDeliveryListener(), () -> disconnected.set(true), null, null);
		buffer.close();
		assertThat(disconnected).isFalse();
	}

	@Test
	void drainDeliversQueuedEvents() {
		List<String> sent = new ArrayList<>();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 10, OverflowPolicy.DROP,
				builder -> {
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
	void recordsMetricsOnOverflow() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SseBackpressureMetrics metrics = new SseBackpressureMetrics(registry);
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 1, OverflowPolicy.DROP, builder -> {
		}, noopDeliveryListener(), null, event -> {
		}, metrics);

		assertThat(buffer.offer(clientEvent("c1", "1"))).isEqualTo(OfferResult.ACCEPTED);
		assertThat(buffer.offer(clientEvent("c1", "2"))).isEqualTo(OfferResult.DROPPED);

		assertThat(registry.get("sse.eventbus.client.buffer.overflow.total").counter().count()).isEqualTo(1);
		assertThat(registry.get("sse.eventbus.client.buffer.dropped.events").counter().count()).isEqualTo(1);
		assertThat(registry.get("sse.eventbus.client.buffer.slow.client.notifications").counter().count()).isEqualTo(1);
	}

}
