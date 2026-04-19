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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ch.rasc.sse.eventbus.config.EnableSseEventBus;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;
import ch.rasc.sse.eventbus.observation.SseEventBusObservationContext;
import ch.rasc.sse.eventbus.observation.SseEventBusObservationContext.Operation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

@ContextConfiguration
@DirtiesContext
@SpringJUnitConfig
@SuppressWarnings("unused")
public class SseEventBusObservationTest {

	@Configuration
	@EnableSseEventBus
	static class Config implements SseEventBusConfigurer {

		@Bean
		RecordingObservationHandler recordingObservationHandler() {
			return new RecordingObservationHandler();
		}

		@Bean
		ObservationRegistry observationRegistry(RecordingObservationHandler handler) {
			ObservationRegistry registry = ObservationRegistry.create();
			registry.observationConfig().observationHandler(handler);
			return registry;
		}

		@Override
		public Duration clientExpiration() {
			return Duration.ofSeconds(5);
		}

		@Override
		public @Nullable ScheduledExecutorService taskScheduler() {
			return null;
		}

	}

	static class RecordingObservationHandler implements ObservationHandler<SseEventBusObservationContext> {

		private final List<SseEventBusObservationContext> contexts = new CopyOnWriteArrayList<>();

		@Override
		public void onStop(SseEventBusObservationContext context) {
			this.contexts.add(context);
		}

		@Override
		public boolean supportsContext(Observation.Context context) {
			return context instanceof SseEventBusObservationContext;
		}

		void reset() {
			this.contexts.clear();
		}

		List<SseEventBusObservationContext> getContexts() {
			return this.contexts;
		}

	}

	@Autowired
	private SseEventBus eventBus;

	@Autowired
	private RecordingObservationHandler observationHandler;

	@BeforeEach
	public void cleanup() {
		this.eventBus.unregisterClient("1");
		this.observationHandler.reset();
	}

	@Test
	public void shouldObserveRegisterHandleSendAndUnregister() throws Exception {
		SseEmitter emitter = mock(SseEmitter.class);
		doNothing().when(emitter).send(any(SseEmitter.SseEventBuilder.class));

		this.eventBus.registerClient("1", emitter);
		this.eventBus.subscribe("1", "orders");
		this.eventBus.handleEvent(SseEvent.of("orders", "payload"));
		this.eventBus.unregisterClient("1");

		assertThat(this.observationHandler.getContexts()).extracting(SseEventBusObservationContext::getOperation)
			.containsExactly(Operation.REGISTER_CLIENT, Operation.SEND_EVENT, Operation.HANDLE_EVENT,
					Operation.UNREGISTER_CLIENT);

		assertThat(this.observationHandler.getContexts()).extracting(SseEventBusObservationContext::getOutcome)
			.containsExactly("success", "success", "success", "success");

		assertThat(this.observationHandler.getContexts())
			.filteredOn(context -> context.getOperation() == Operation.HANDLE_EVENT)
			.singleElement()
			.satisfies(context -> {
				assertThat(context.getEventName()).isEqualTo("orders");
				assertThat(context.getDeliveryCount()).isEqualTo(1);
			});

		assertThat(this.observationHandler.getContexts())
			.filteredOn(context -> context.getOperation() == Operation.SEND_EVENT)
			.singleElement()
			.satisfies(context -> {
				assertThat(context.getClientId()).isEqualTo("1");
				assertThat(context.getAttempt()).isEqualTo(1);
			});
	}

}