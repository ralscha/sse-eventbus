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

import java.util.function.Consumer;

/**
 * SPI for distributing {@link SseEvent}s across multiple nodes.
 *
 * <p>
 * When a {@link DistributedEventBus} bean is present in the Spring application context,
 * {@link SseEventBus} will automatically detect it and:
 * <ol>
 * <li>Call {@link #setRemoteEventConsumer} once during startup, passing a callback that
 * delivers an event to local subscribers on this node.</li>
 * <li>Call {@link #publishRemote} after every locally handled event so other nodes can
 * receive it.</li>
 * </ol>
 *
 * <p>
 * Implementations are responsible for <em>not</em> echoing events back to the node that
 * originally published them (loop prevention). The simplest way to achieve this is to
 * attach a unique node identifier to every outbound message and silently discard inbound
 * messages that carry the local node's identifier.
 *
 * <p>
 * Implementations must be thread-safe.
 *
 * <p>
 * This interface is not automatically enabled. Users must declare a Spring bean of this
 * type to activate distributed delivery.
 *
 * <h2>Broadcast semantics</h2> By default implementations should broadcast each event to
 * all cluster nodes. Every receiving node then filters the event against its own local
 * {@link SubscriptionRegistry}; only clients that have a local SSE connection and are
 * subscribed to the event's topic will receive it.
 */
public interface DistributedEventBus {

	/**
	 * Publishes an event to all other nodes in the cluster.
	 *
	 * <p>
	 * This method is called by {@link SseEventBus} after local delivery has taken place.
	 * Implementations MUST NOT echo the event back to the originating node to avoid
	 * infinite re-broadcast loops.
	 * @param event the event to distribute; never {@code null}
	 */
	void publishRemote(SseEvent event);

	/**
	 * Supplies the consumer that implementations must call for every event received from
	 * a remote node. The consumer delivers the event to local subscribers without
	 * triggering another {@link #publishRemote} call.
	 *
	 * <p>
	 * {@link SseEventBus} calls this method exactly once, during its initialization.
	 * Implementations should throw {@link IllegalStateException} if called a second time.
	 * @param consumer the callback to invoke for each inbound remote event; never
	 * {@code null}
	 */
	void setRemoteEventConsumer(Consumer<SseEvent> consumer);

}
