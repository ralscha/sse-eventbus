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
package ch.rasc.sse.eventbus.distributed;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

import ch.rasc.sse.eventbus.DistributedEventBus;
import ch.rasc.sse.eventbus.SseEvent;

/**
 * A {@link DistributedEventBus} implementation that uses Spring's
 * {@link ApplicationEventPublisher} as the transport.
 *
 * <p>
 * Each published {@link SseEvent} is wrapped in a {@link RemoteSseEventEnvelope} and
 * dispatched via {@link ApplicationEventPublisher#publishEvent}. Any Spring application
 * listener (including other {@code SpringApplicationEventDistributedEventBus} instances
 * in the same JVM) receives the envelope. Envelopes whose {@code originNodeId} matches
 * the local node's id are silently dropped to prevent delivery loops.
 *
 * <p>
 * <strong>Single-JVM usage:</strong> Out of the box this implementation only bridges
 * events within the same Spring application context. To propagate events across JVMs,
 * pair it with a Spring Cloud Stream binding, a Spring Integration channel adapter, or
 * any other mechanism that relays Spring application events between nodes.
 *
 * <p>
 * <strong>Opt-in:</strong> This class is not registered automatically. Declare a bean to
 * activate it: <pre>{@code
 * &#64;Bean
 * SpringApplicationEventDistributedEventBus distributedEventBus(
 *         ApplicationEventPublisher publisher) {
 *     return new SpringApplicationEventDistributedEventBus(publisher);
 * }
 * }</pre>
 */
public class SpringApplicationEventDistributedEventBus implements DistributedEventBus {

	private final ApplicationEventPublisher publisher;

	private final String nodeId;

	private final AtomicReference<Consumer<SseEvent>> remoteEventConsumer = new AtomicReference<>();

	/**
	 * Creates a new instance with a randomly generated node identifier.
	 * @param publisher the Spring application event publisher; must not be {@code null}
	 */
	public SpringApplicationEventDistributedEventBus(ApplicationEventPublisher publisher) {
		this(publisher, UUID.randomUUID().toString());
	}

	/**
	 * Creates a new instance with an explicit node identifier. Intended for testing.
	 * @param publisher the Spring application event publisher; must not be {@code null}
	 * @param nodeId a unique identifier for this node; must not be {@code null}
	 */
	public SpringApplicationEventDistributedEventBus(ApplicationEventPublisher publisher, String nodeId) {
		this.publisher = publisher;
		this.nodeId = nodeId;
	}

	@Override
	public void publishRemote(SseEvent event) {
		this.publisher.publishEvent(new RemoteSseEventEnvelope(this.nodeId, event));
	}

	@Override
	public void setRemoteEventConsumer(Consumer<SseEvent> consumer) {
		if (!this.remoteEventConsumer.compareAndSet(null, consumer)) {
			throw new IllegalStateException(
					"setRemoteEventConsumer has already been called; only one SseEventBus may be associated with this DistributedEventBus instance");
		}
	}

	/**
	 * Receives {@link RemoteSseEventEnvelope}s published to the Spring application
	 * context. Envelopes originating from this node are dropped to prevent loops; all
	 * others are forwarded to the local {@link ch.rasc.sse.eventbus.SseEventBus}.
	 * @param envelope the received envelope
	 */
	@EventListener
	public void onEnvelope(RemoteSseEventEnvelope envelope) {
		if (this.nodeId.equals(envelope.originNodeId())) {
			return;
		}
		Consumer<SseEvent> consumer = this.remoteEventConsumer.get();
		if (consumer != null) {
			consumer.accept(envelope.event());
		}
	}

	/**
	 * Returns the unique identifier of this node.
	 * @return the node id
	 */
	public String getNodeId() {
		return this.nodeId;
	}

}
