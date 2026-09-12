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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import ch.rasc.sse.eventbus.config.EnableSseEventBus;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ContextConfiguration
@DirtiesContext
@SpringJUnitConfig
class SseEventBusBackpressureDropTest {

	private static final ConcurrentMap<String, Client> CLIENTS_MAP = new ConcurrentHashMap<>();

	private static final List<SlowClientEvent> SLOW_CLIENT_EVENTS = new CopyOnWriteArrayList<>();

	private static final MeterRegistry REGISTRY = new SimpleMeterRegistry();

	@Configuration
	@EnableSseEventBus
	static class Config implements SseEventBusConfigurer {

		@Override
		public int clientSendBufferCapacity() {
			return 2;
		}

		@Override
		public OverflowPolicy overflowPolicy() {
			return OverflowPolicy.DROP;
		}

		@Override
		public SlowClientListener slowClientListener() {
			return SLOW_CLIENT_EVENTS::add;
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

	}

	@Autowired
	private SseEventBus eventBus;

	@BeforeEach
	void cleanup() {
		SLOW_CLIENT_EVENTS.clear();
		for (String clientId : this.eventBus.getAllClientIds()) {
			this.eventBus.unregisterClient(clientId);
		}
	}

	@Test
	void dropPolicyDropsEventsForSlowClient() {
		this.eventBus.registerClient("slow", new SlowSseEmitter());
		this.eventBus.subscribe("slow", "test");
		for (int i = 0; i < 50; i++) {
			this.eventBus.handleEvent(SseEvent.of("test", "msg-" + i));
		}

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(SLOW_CLIENT_EVENTS).isNotEmpty());
		assertThat(REGISTRY.get("sse.eventbus.client.buffer.dropped.events").counter().count()).isGreaterThan(0);
		assertThat(REGISTRY.get("sse.eventbus.client.buffer.overflow.total").counter().count()).isGreaterThan(0);
		// DROP policy keeps the connection open
		assertThat(this.eventBus.getAllClientIds()).contains("slow");
		this.eventBus.unregisterClient("slow");
	}

	static class SlowSseEmitter extends SseEmitter {

		SlowSseEmitter() {
			super(0L);
		}

		@Override
		public void send(SseEventBuilder builder) throws IOException {
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			super.send(builder);
		}

	}

}
