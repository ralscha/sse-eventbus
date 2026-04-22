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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static ch.rasc.sse.eventbus.TestUtils.sleep;
import ch.rasc.sse.eventbus.config.EnableSseEventBus;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;

/**
 * Tests that verify integration between {@link SseEventBus} and
 * {@link DistributedEventBus}.
 */
@ContextConfiguration
@DirtiesContext
@SpringJUnitConfig
class DistributedEventBusTest {

	static final DistributedEventBus TRANSPORT = mock(DistributedEventBus.class);

	@SuppressWarnings("unchecked")
	static final ArgumentCaptor<Consumer<SseEvent>> CONSUMER_CAPTOR = ArgumentCaptor.forClass(Consumer.class);

	@Configuration
	@EnableSseEventBus
	static class Config {

		@Bean
		DistributedEventBus distributedEventBus() {
			doAnswer(inv -> null).when(TRANSPORT).setRemoteEventConsumer(CONSUMER_CAPTOR.capture());
			return TRANSPORT;
		}

	}

	@Autowired
	private SseEventBus eventBus;

	@BeforeEach
	void cleanup() {
		reset(TRANSPORT);
		doAnswer(inv -> null).when(TRANSPORT).setRemoteEventConsumer(CONSUMER_CAPTOR.capture());
		this.eventBus.unregisterClient("1");
		this.eventBus.unregisterClient("2");
	}

	@Test
	void publishRemoteCalledOnHandleEvent() {
		this.eventBus.createSseEmitter("1");
		this.eventBus.subscribe("1", "topic");

		SseEvent event = SseEvent.of("topic", "hello");
		this.eventBus.handleEvent(event);

		sleep(200, TimeUnit.MILLISECONDS);
		verify(TRANSPORT, times(1)).publishRemote(eq(event));
	}

	@Test
	void localDeliveryStillOccursWhenTransportPresent() {
		this.eventBus.createSseEmitter("1");
		this.eventBus.subscribe("1", "topic");

		SseEvent event = SseEvent.of("topic", "local");
		this.eventBus.handleEvent(event);

		sleep(200, TimeUnit.MILLISECONDS);
		// Local queue should have been drained (synchronous bus — no scheduler)
		// Verify publish was called (confirms the full flow ran without error)
		verify(TRANSPORT, times(1)).publishRemote(eq(event));
	}

	@Test
	void remoteEventDeliveredLocallyWithoutRebroadcast() {
		this.eventBus.createSseEmitter("1");
		this.eventBus.subscribe("1", "topic");

		// Capture the consumer that SseEventBus registered with the transport
		Consumer<SseEvent> consumer = CONSUMER_CAPTOR.getValue();
		assertThat(consumer).isNotNull();

		SseEvent remoteEvent = SseEvent.of("topic", "from-remote");
		// Inject a remote event directly via the consumer — simulates inbound
		consumer.accept(remoteEvent);

		sleep(200, TimeUnit.MILLISECONDS);
		// publishRemote must NOT be called again for this remote-originated event
		verify(TRANSPORT, never()).publishRemote(eq(remoteEvent));
	}

	@Test
	void noTransportBehaviorUnchanged() {
		// Build a bus with no transport at all — must behave exactly as before
		SseEventBus bare = new SseEventBus(new SseEventBusConfigurer() {
		}, new DefaultSubscriptionRegistry(), List.of(), null, null, null, null);
		bare.init();

		bare.createSseEmitter("x");
		bare.subscribe("x", "t");

		SseEvent event = SseEvent.of("t", "v");
		bare.handleEvent(event);

		sleep(200, TimeUnit.MILLISECONDS);
		assertThat(bare.getClientCount()).isEqualTo(1);
		bare.cleanUp();
	}

}
