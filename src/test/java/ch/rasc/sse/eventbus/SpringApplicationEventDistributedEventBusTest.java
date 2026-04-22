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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.springframework.context.ApplicationEventPublisher;

import ch.rasc.sse.eventbus.distributed.RemoteSseEventEnvelope;
import ch.rasc.sse.eventbus.distributed.SpringApplicationEventDistributedEventBus;

/**
 * Unit tests for {@link SpringApplicationEventDistributedEventBus}.
 */
class SpringApplicationEventDistributedEventBusTest {

	private ApplicationEventPublisher publisher;

	private SpringApplicationEventDistributedEventBus bus;

	@BeforeEach
	void setUp() {
		this.publisher = mock(ApplicationEventPublisher.class);
		this.bus = new SpringApplicationEventDistributedEventBus(this.publisher, "node-A");
	}

	@Test
	void publishRemoteWrapsEventInEnvelope() {
		SseEvent event = SseEvent.of("topic", "data");

		this.bus.publishRemote(event);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Object> captor = forClass(Object.class);
		verify(this.publisher).publishEvent(captor.capture());

		assertThat(captor.getValue()).isInstanceOf(RemoteSseEventEnvelope.class);
		RemoteSseEventEnvelope envelope = (RemoteSseEventEnvelope) captor.getValue();
		assertThat(envelope.originNodeId()).isEqualTo("node-A");
		assertThat(envelope.event()).isSameAs(event);
	}

	@Test
	void selfOriginatedEnvelopeIsDropped() {
		List<SseEvent> received = new ArrayList<>();
		this.bus.setRemoteEventConsumer(received::add);

		// Same nodeId — must be silently dropped
		this.bus.onEnvelope(new RemoteSseEventEnvelope("node-A", SseEvent.of("t", "v")));

		assertThat(received).isEmpty();
	}

	@Test
	void remoteEnvelopeIsForwardedToConsumer() {
		List<SseEvent> received = new ArrayList<>();
		this.bus.setRemoteEventConsumer(received::add);

		SseEvent event = SseEvent.of("topic", "from-B");
		this.bus.onEnvelope(new RemoteSseEventEnvelope("node-B", event));

		assertThat(received).containsExactly(event);
	}

	@Test
	void secondSetRemoteEventConsumerThrows() {
		this.bus.setRemoteEventConsumer(e -> {
		});
		assertThatThrownBy(() -> this.bus.setRemoteEventConsumer(e -> {
		})).isInstanceOf(IllegalStateException.class).hasMessageContaining("setRemoteEventConsumer");
	}

	@Test
	void nodeIdIsReturnedCorrectly() {
		assertThat(this.bus.getNodeId()).isEqualTo("node-A");
	}

	@Test
	void defaultConstructorGeneratesUniqueNodeIds() {
		String id1 = new SpringApplicationEventDistributedEventBus(this.publisher).getNodeId();
		String id2 = new SpringApplicationEventDistributedEventBus(this.publisher).getNodeId();
		assertThat(id1).isNotEqualTo(id2);
	}

	@Test
	void noConsumerEnvelopeDoesNotThrow() {
		// Consumer not set yet; receiving an envelope from another node should not fail
		this.bus.onEnvelope(new RemoteSseEventEnvelope("node-B", SseEvent.of("t", "v")));
	}

}
