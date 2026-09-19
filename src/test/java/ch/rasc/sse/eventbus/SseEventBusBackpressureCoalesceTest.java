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

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ch.rasc.sse.eventbus.config.EnableSseEventBus;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;

@ContextConfiguration
@DirtiesContext
@SpringJUnitConfig
class SseEventBusBackpressureCoalesceTest {

	private static final ConcurrentMap<String, Client> CLIENTS_MAP = new ConcurrentHashMap<>();

	private static final MeterRegistry REGISTRY = new SimpleMeterRegistry();

	private static final AtomicInteger SENT_NOTIFICATIONS = new AtomicInteger();

	@Configuration
	@EnableSseEventBus
	static class Config implements SseEventBusConfigurer {

		@Override
		public int clientSendBufferCapacity() {
			// large enough so the buffer never overflows; the coalescer is the
			// mechanism under test
			return 1000;
		}

		@Override
		public EventCoalescer eventCoalescer() {
			return new DefaultEventCoalescer();
		}

		@Override
		public MeterRegistry meterRegistry() {
			return REGISTRY;
		}

		@Override
		public ConcurrentMap<String, Client> clients() {
			return CLIENTS_MAP;
		}

		@Override
		public int noOfSendResponseTries() {
			return 1;
		}

		@Override
		public SseEventBusListener listener() {
			return new SseEventBusListener() {
				@Override
				public void afterEventSent(ClientEvent clientEvent, @Nullable Exception exception) {
					if (exception == null) {
						SENT_NOTIFICATIONS.incrementAndGet();
					}
				}
			};
		}

	}

	@Autowired
	private SseEventBus eventBus;

	@BeforeEach
	void cleanup() {
		SENT_NOTIFICATIONS.set(0);
		for (String clientId : this.eventBus.getAllClientIds()) {
			this.eventBus.unregisterClient(clientId);
		}
	}

	@Test
	void coalescerMergesEventsIntoFewerWrites() {
		this.eventBus.registerClient("coalesced", new SseEmitter(0L));
		this.eventBus.subscribe("coalesced", "test");
		for (int i = 0; i < 50; i++) {
			this.eventBus.handleEvent(SseEvent.of("test", "msg-" + i));
		}

		// the dispatcher merged adjacent events into single SSE frames
		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(
					REGISTRY.get("sse.eventbus.client.buffer.coalesced.events").counter().count())
				.isGreaterThan(0));
		// far fewer writes than published events, and at least one write happened
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(SENT_NOTIFICATIONS.get()).isGreaterThan(0));
		assertThat(SENT_NOTIFICATIONS.get()).isLessThan(50);

		// the client stays connected
		assertThat(this.eventBus.getAllClientIds()).contains("coalesced");
		this.eventBus.unregisterClient("coalesced");
	}

}
