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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.rasc.sse.eventbus.config.DefaultSseEventBusConfiguration;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;

class SseEventBusRegressionTest {

	@Test
	void replacingSubscriptionsWithAnEmptyListUnsubscribesEverything() {
		SseEventBus bus = synchronousBus();
		bus.createSseEmitter("client", "orders");
		bus.createSseEmitter("client", 180_000L, true, false);
		assertThat(bus.getAllSubscriptions()).isEmpty();
		bus.subscribe("client", "orders");
		bus.createSseEmitter("client", 180_000L, true, false, (String[]) null);
		assertThat(bus.getAllSubscriptions()).isEmpty();
	}

	@Test
	void unregisterAlsoRemovesSubscriptionsMadeBeforeRegistration() {
		SseEventBus bus = synchronousBus();
		bus.subscribe("client", "orders");
		bus.unregisterClient("client");
		assertThat(bus.getAllSubscriptions()).isEmpty();
	}

	@Test
	void reregisteringTheSameEmitterDoesNotCompleteIt() {
		SseEventBus bus = synchronousBus();
		SseEmitter emitter = mock(SseEmitter.class);
		bus.registerClient("client", emitter);
		bus.registerClient("client", emitter);
		verify(emitter, never()).complete();
	}

	@Test
	void completingASendDoesNotCloseAnEmitterRegisteredDuringTheSend() throws Exception {
		SseEventBus bus = synchronousBus();
		SseEmitter oldEmitter = mock(SseEmitter.class);
		SseEmitter newEmitter = mock(SseEmitter.class);
		bus.registerClient("client", oldEmitter, true);
		bus.subscribe("client");
		doAnswer(invocation -> {
			bus.registerClient("client", newEmitter, true);
			return null;
		}).when(oldEmitter).send(any(SseEmitter.SseEventBuilder.class));
		bus.handleEvent(SseEvent.ofData("payload"));
		verify(newEmitter, never()).complete();
	}

	@Test
	void unregisterClearsPendingEventsEvenWithoutIds() {
		TestConfig config = new TestConfig();
		SseEventBus bus = bus(config, null);
		bus.createSseEmitter("client", SseEvent.DEFAULT_EVENT);
		bus.handleEvent(SseEvent.ofData("pending"));
		ClientEvent retry = new ClientEvent(config.client("client"), SseEvent.ofData("retry"), null);
		config.errors.add(retry);
		bus.unregisterClient("client");
		assertThat(bus.getSendQueueSize()).isZero();
		assertThat(bus.getErrorQueueSize()).isZero();
	}

	@Test
	void replayDoesNotRemovePendingEmptyIdEvents() {
		TestConfig config = new TestConfig();
		config.sends = new LinkedBlockingQueue<>(4);
		SseEventBus bus = bus(config, new InMemoryReplayStore());
		bus.createSseEmitter("client", SseEvent.DEFAULT_EVENT);
		bus.handleEvent(SseEvent.builder().id("").data("reset").build());
		bus.handleEvent(SseEvent.builder().id("1").data("first").build());
		bus.handleEvent(SseEvent.builder().id("2").data("second").build());
		bus.replayMissedEvents("client", "1");
		assertThat(config.sends).extracting(event -> event.getSseEvent().id().orElseThrow()).containsExactly("", "2");
	}

	@Test
	void reschedulingDoesNotBlockWhenSendQueueIsFull() {
		TestConfig config = new TestConfig();
		SseEventBus bus = bus(config, null);
		bus.createSseEmitter("client", SseEvent.DEFAULT_EVENT);
		Client client = config.client("client");
		config.sends.add(new ClientEvent(client, SseEvent.ofData("pending"), null));
		config.sends.add(new ClientEvent(client, SseEvent.ofData("also pending"), null));
		ClientEvent retry = new ClientEvent(client, SseEvent.ofData("retry"), null);
		config.errors.add(retry);
		assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> ReflectionTestUtils.invokeMethod(bus, "reScheduleFailedEvents"));
		assertThat(config.errors).containsExactly(retry);
		assertThat(config.sends).hasSize(2);
	}

	@Test
	void fullRetryQueueDoesNotPreventDeliveryToHealthyClients() throws Exception {
		TestConfig config = new TestConfig();
		SseEventBus bus = bus(config, null);
		SseEmitter failing = mock(SseEmitter.class);
		doThrow(new IOException("disconnected")).when(failing).send(any(SseEmitter.SseEventBuilder.class));
		CountDownLatch delivered = new CountDownLatch(1);
		SseEmitter healthy = mock(SseEmitter.class);
		doAnswer(invocation -> {
			delivered.countDown();
			return null;
		}).when(healthy).send(any(SseEmitter.SseEventBuilder.class));
		bus.registerClient("failing", failing);
		bus.registerClient("healthy", healthy);
		bus.subscribe("failing");
		bus.subscribe("healthy");
		config.errors.add(new ClientEvent(config.client("failing"), SseEvent.ofData("old failure"), null));
		bus.handleEvent(SseEvent.builder().addClientId("failing").data("failure").build());
		ClientEvent dropped = config.sends.element();
		doThrow(new IllegalStateException("listener failure")).when(config.listener).afterEventDropped(dropped);
		bus.handleEvent(SseEvent.builder().addClientId("healthy").data("success").build());
		var worker = Executors.newSingleThreadExecutor();
		var task = worker.submit(() -> ReflectionTestUtils.invokeMethod(bus, "eventLoop"));
		try {
			assertThat(delivered.await(3, TimeUnit.SECONDS)).isTrue();
			verify(config.listener).afterEventDropped(dropped);
			assertThat(config.errors).hasSize(1);
		}
		finally {
			task.cancel(true);
			worker.shutdownNow();
			assertThat(worker.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void cleanupListenerFailuresDoNotStopFutureCleanupRuns() {
		TestConfig config = new TestConfig();
		SseEventBus bus = bus(config, null);
		doThrow(new IllegalStateException("listener failure")).when(config.listener).afterClientsUnregistered(any());
		for (String id : List.of("first", "second")) {
			bus.createSseEmitter(id);
			ReflectionTestUtils.setField(config.client(id), "lastTransfer", 0L);
			ReflectionTestUtils.invokeMethod(bus, "cleanUpClients");
			assertThat(bus.isClientRegistered(id)).isFalse();
		}
	}

	@Test
	void defaultSchedulerLeavesRoomForMaintenanceWithMultipleSendWorkers() throws Exception {
		CountDownLatch heartbeat = new CountDownLatch(1);
		SseEventBusConfigurer config = new SseEventBusConfigurer() {
			@Override
			public int sendWorkerCount() {
				return 3;
			}

			@Override
			public Duration heartbeatInterval() {
				return Duration.ofMillis(10);
			}
		};
		SseEventBus bus = bus(config, null);
		SseEmitter emitter = mock(SseEmitter.class);
		doAnswer(invocation -> {
			heartbeat.countDown();
			return null;
		}).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
		bus.registerClient("client", emitter);
		try {
			bus.init();
			assertThat(heartbeat.await(3, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			bus.cleanUp();
		}
	}

	@Test
	void configurationAcceptsImmutableConverterListsWithoutMutatingThem() {
		class Configuration extends DefaultSseEventBusConfiguration {

			Configuration() {
				this.configurer = new SseEventBusConfigurer() {
					@Override
					public @Nullable ScheduledExecutorService taskScheduler() {
						return null;
					}
				};
				this.dataObjectConverters = List.of(new DefaultDataObjectConverter());
			}

		}
		Configuration configuration = new Configuration();
		assertThat(configuration.eventBus().getDataObjectConverters()).hasSize(2);
		assertThat(configuration.eventBus().getDataObjectConverters()).hasSize(2);
	}

	private static SseEventBus synchronousBus() {
		return bus(new SseEventBusConfigurer() {
			@Override
			public @Nullable ScheduledExecutorService taskScheduler() {
				return null;
			}
		}, null);
	}

	private static SseEventBus bus(SseEventBusConfigurer config, @Nullable ReplayStore store) {
		return new SseEventBus(config, new DefaultSubscriptionRegistry(), null, store);
	}

	private static class TestConfig implements SseEventBusConfigurer {

		final ConcurrentMap<String, Client> clients = new ConcurrentHashMap<>();

		BlockingQueue<ClientEvent> sends = new LinkedBlockingQueue<>(2);

		final BlockingQueue<ClientEvent> errors = new LinkedBlockingQueue<>(1);

		final SseEventBusListener listener = mock(SseEventBusListener.class);

		Client client(String id) {
			return Objects.requireNonNull(this.clients.get(id));
		}

		@Override
		public ConcurrentMap<String, Client> clients() {
			return this.clients;
		}

		@Override
		public BlockingQueue<ClientEvent> sendQueue() {
			return this.sends;
		}

		@Override
		public BlockingQueue<ClientEvent> errorQueue() {
			return this.errors;
		}

		@Override
		public SseEventBusListener listener() {
			return this.listener;
		}

		@Override
		public ScheduledExecutorService taskScheduler() {
			return mock(ScheduledExecutorService.class);
		}

	}

}
