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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer metrics for the per-client send buffer backpressure protection.
 * <p>
 * Counters (all prefixed with {@code sse.eventbus.client.buffer}):
 * <ul>
 * <li>{@code overflow.total} - number of buffer overflows</li>
 * <li>{@code dropped.events} - events dropped by the {@link OverflowPolicy#DROP}
 * policy</li>
 * <li>{@code disconnected.clients} - clients disconnected by the
 * {@link OverflowPolicy#DISCONNECT} policy</li>
 * <li>{@code slow.client.notifications} - slow client callback invocations</li>
 * </ul>
 * Gauges:
 * <ul>
 * <li>{@code queue.size} (tag {@code clientId}) - current number of queued events per
 * client</li>
 * <li>{@code active.clients} - number of clients with an active send buffer</li>
 * </ul>
 */
final class SseBackpressureMetrics {

	private final MeterRegistry registry;

	private final String prefix;

	private final Counter overflows;

	private final Counter droppedEvents;

	private final Counter disconnectedClients;

	private final Counter slowClientNotifications;

	private final Map<String, Gauge> queueGauges = new ConcurrentHashMap<>();

	private final Map<String, Supplier<Number>> strongSuppliers = new ConcurrentHashMap<>();

	SseBackpressureMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.prefix = "sse.eventbus.client.buffer";
		this.overflows = Counter.builder(metric("overflow.total"))
			.description("Number of per-client send buffer overflows")
			.register(registry);
		this.droppedEvents = Counter.builder(metric("dropped.events"))
			.description("Events dropped because the client send buffer was full")
			.register(registry);
		this.disconnectedClients = Counter.builder(metric("disconnected.clients"))
			.description("Slow clients disconnected because the send buffer was full")
			.register(registry);
		this.slowClientNotifications = Counter.builder(metric("slow.client.notifications"))
			.description("Slow client callback invocations")
			.register(registry);
	}

	private String metric(String name) {
		return this.prefix + "." + name;
	}

	void recordOverflow(String clientId) {
		this.overflows.increment();
	}

	void recordDropped(String clientId) {
		this.droppedEvents.increment();
	}

	void recordDisconnected(String clientId) {
		this.disconnectedClients.increment();
	}

	void recordNotification(String clientId) {
		this.slowClientNotifications.increment();
	}

	void registerQueueGauge(String clientId, Supplier<Number> supplier) {
		this.strongSuppliers.put("queue:" + clientId, supplier);
		this.queueGauges.computeIfAbsent(clientId,
				id -> Gauge.builder(metric("queue.size"), supplier, s -> s.get().doubleValue())
					.description("Currently queued events in the client's send buffer")
					.tag("clientId", id)
					.register(this.registry));
	}

	void removeQueueGauge(String clientId) {
		Gauge gauge = this.queueGauges.remove(clientId);
		if (gauge != null) {
			this.registry.remove(gauge);
		}
		this.strongSuppliers.remove("queue:" + clientId);
	}

	void registerActiveClientsGauge(Supplier<Number> supplier) {
		this.strongSuppliers.put("active:clients", supplier);
		Gauge.builder(metric("active.clients"), supplier, s -> s.get().doubleValue())
			.description("Number of clients with an active send buffer")
			.register(this.registry);
	}

}
