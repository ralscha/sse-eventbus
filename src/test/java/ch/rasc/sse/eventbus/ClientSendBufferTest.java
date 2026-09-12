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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ClientSendBufferTest {

	private static SseEventBuilder event(String data) {
		return SseEmitter.event().data(data);
	}

	@Test
	void dispatchDeliversEventsInOrder() {
		List<String> sent = new ArrayList<>();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 10, OverflowPolicy.DROP, builder -> sent.add(builder.toString()),
				null, null, null);
		buffer.start();
		buffer.offer(event("1"));
		buffer.offer(event("2"));
		buffer.offer(event("3"));
		await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(sent).hasSize(3));
		buffer.close();
	}

	@Test
	void dropPolicyRejectsOverflowAndNotifiesListener() {
		List<SlowClientEvent> slowClientEvents = new ArrayList<>();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 2, OverflowPolicy.DROP, builder -> {
		}, null, slowClientEvents::add, null);

		assertThat(buffer.offer(event("1"))).isTrue();
		assertThat(buffer.offer(event("2"))).isTrue();
		assertThat(buffer.offer(event("3"))).isFalse();

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
		}, () -> disconnected.set(true), event -> {
		}, null);

		assertThat(buffer.offer(event("1"))).isTrue();
		assertThat(buffer.offer(event("2"))).isFalse();

		assertThat(buffer.isClosed()).isTrue();
		assertThat(disconnected).isTrue();
		assertThat(buffer.offer(event("3"))).isFalse();
	}

	@Test
	void offerReturnsFalseAfterClose() {
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 5, OverflowPolicy.DROP, builder -> {
		}, null, null, null);
		buffer.close();
		assertThat(buffer.offer(event("1"))).isFalse();
	}

	@Test
	void closeDoesNotNotifyDisconnect() {
		AtomicBoolean disconnected = new AtomicBoolean();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 5, OverflowPolicy.DISCONNECT, builder -> {
		}, () -> disconnected.set(true), null, null);
		buffer.close();
		assertThat(disconnected).isFalse();
	}

	@Test
	void sinkFailureClosesBufferAndNotifiesDisconnect() {
		AtomicBoolean disconnected = new AtomicBoolean();
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 5, OverflowPolicy.DROP, builder -> {
			throw new IOException("connection lost");
		}, () -> disconnected.set(true), null, null);
		buffer.start();
		buffer.offer(event("1"));
		await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(disconnected).isTrue());
		assertThat(buffer.isClosed()).isTrue();
		buffer.close();
	}

	@Test
	void recordsMetricsOnOverflow() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SseBackpressureMetrics metrics = new SseBackpressureMetrics(registry);
		ClientSendBuffer buffer = new ClientSendBuffer("c1", 1, OverflowPolicy.DROP, builder -> {
		}, null, event -> {
		}, metrics);

		assertThat(buffer.offer(event("1"))).isTrue();
		assertThat(buffer.offer(event("2"))).isFalse();

		assertThat(registry.get("sse.eventbus.client.buffer.overflow.total").counter().count()).isEqualTo(1);
		assertThat(registry.get("sse.eventbus.client.buffer.dropped.events").counter().count()).isEqualTo(1);
		assertThat(registry.get("sse.eventbus.client.buffer.slow.client.notifications").counter().count()).isEqualTo(1);
	}

}
