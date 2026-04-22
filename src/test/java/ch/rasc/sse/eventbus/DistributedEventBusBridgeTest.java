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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ch.rasc.sse.eventbus.TestUtils.sleep;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;

/**
 * End-to-end bridge test: two {@link SseEventBus} instances are connected by an in-memory
 * paired {@link DistributedEventBus} bridge. Publishing on node A must deliver the event
 * to a subscriber on node B, and vice versa, without infinite loops.
 */
class DistributedEventBusBridgeTest {

	/**
	 * A simple in-memory {@link DistributedEventBus} that forwards every published event
	 * directly to its paired peer's consumer.
	 */
	static class InMemoryTransport implements DistributedEventBus {

		private @Nullable Consumer<SseEvent> consumer;

		private @Nullable InMemoryTransport peer;

		void setPeer(InMemoryTransport peer) {
			this.peer = peer;
		}

		@Override
		public void publishRemote(SseEvent event) {
			// Deliver to the peer's consumer — simulates cross-node transport
			InMemoryTransport p = this.peer;
			if (p == null) {
				return;
			}
			Consumer<SseEvent> peerConsumer = p.consumer;
			if (peerConsumer != null) {
				peerConsumer.accept(event);
			}
		}

		@Override
		public void setRemoteEventConsumer(Consumer<SseEvent> consumer) {
			if (this.consumer != null) {
				throw new IllegalStateException(
						"setRemoteEventConsumer has already been called; only one SseEventBus may be associated with this DistributedEventBus instance");
			}
			this.consumer = consumer;
		}

	}

	private SseEventBus busA;

	private SseEventBus busB;

	@BeforeEach
	void setUp() {
		InMemoryTransport transportA = new InMemoryTransport();
		InMemoryTransport transportB = new InMemoryTransport();
		transportA.setPeer(transportB);
		transportB.setPeer(transportA);

		SseEventBusConfigurer cfg = new SseEventBusConfigurer() {
			@Override
			public @Nullable ScheduledExecutorService taskScheduler() {
				return null; // synchronous mode — no scheduler
			}
		};

		this.busA = new SseEventBus(cfg, new DefaultSubscriptionRegistry(), List.of(), null, null, null, transportA);
		this.busA.init();

		this.busB = new SseEventBus(cfg, new DefaultSubscriptionRegistry(), List.of(), null, null, null, transportB);
		this.busB.init();
	}

	@AfterEach
	void tearDown() {
		this.busA.cleanUp();
		this.busB.cleanUp();
	}

	@Test
	void eventPublishedOnADeliveredToSubscriberOnB() {
		List<String> delivered = new ArrayList<>();

		// Register a client on bus B with a fake emitter that captures payloads
		CapturingEmitter emitterB = new CapturingEmitter(delivered);
		this.busB.registerClient("clientB", emitterB.emitter(), false);
		this.busB.subscribe("clientB", "news");

		// Publish on bus A
		this.busA.handleEvent(SseEvent.of("news", "hello from A"));

		sleep(200, TimeUnit.MILLISECONDS);
		assertThat(delivered).isNotEmpty();
	}

	@Test
	void noLoopWhenPublishingOnA() {
		// Neither bus should process the event more than once (no infinite loop)
		List<String> deliveredOnA = new ArrayList<>();
		List<String> deliveredOnB = new ArrayList<>();

		CapturingEmitter emitterA = new CapturingEmitter(deliveredOnA);
		this.busA.registerClient("clientA", emitterA.emitter(), false);
		this.busA.subscribe("clientA", "chat");

		CapturingEmitter emitterB = new CapturingEmitter(deliveredOnB);
		this.busB.registerClient("clientB", emitterB.emitter(), false);
		this.busB.subscribe("clientB", "chat");

		this.busA.handleEvent(SseEvent.of("chat", "msg"));

		sleep(300, TimeUnit.MILLISECONDS);

		// clientA subscribed on busA must receive the original local dispatch
		assertThat(deliveredOnA).hasSize(1);
		// clientB subscribed on busB must receive exactly once from the remote delivery
		assertThat(deliveredOnB).hasSize(1);
	}

	@Test
	void eventPublishedOnBDeliveredToSubscriberOnA() {
		List<String> delivered = new ArrayList<>();

		CapturingEmitter emitterA = new CapturingEmitter(delivered);
		this.busA.registerClient("clientA", emitterA.emitter(), false);
		this.busA.subscribe("clientA", "alerts");

		this.busB.handleEvent(SseEvent.of("alerts", "hello from B"));

		sleep(200, TimeUnit.MILLISECONDS);
		assertThat(delivered).isNotEmpty();
	}

	// ---------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------

	/**
	 * Creates a real
	 * {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter} and
	 * captures the sent data strings so assertions can be made on them.
	 */
	static class CapturingEmitter {

		private final org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter;

		CapturingEmitter(List<String> captured) {
			this.emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L) {
				@Override
				public synchronized void send(SseEventBuilder builder) throws java.io.IOException {
					captured.add("event-sent");
				}
			};
		}

		org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter() {
			return this.emitter;
		}

	}

}
