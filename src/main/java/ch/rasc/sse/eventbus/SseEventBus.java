/**
 * Copyright 2016-2018 the original author or authors.
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;

public class SseEventBus {

	/**
	 * Client Id -> SseEmitter
	 */
	private final ConcurrentMap<String, Client> clients;

	private final SubscriptionRegistry subscriptionRegistry;

	private final ScheduledExecutorService taskScheduler;

	private final int noOfSendResponseTries;

	private final Duration clientExpiration;

	private List<DataObjectConverter> dataObjectConverters;

	private final BlockingQueue<ClientEvent> errorQueue;

	private final BlockingQueue<ClientEvent> sendQueue;

	public SseEventBus(SseEventBusConfigurer configurer,
			SubscriptionRegistry subscriptionRegistry) {

		this.subscriptionRegistry = subscriptionRegistry;

		this.taskScheduler = configurer.taskScheduler();
		this.noOfSendResponseTries = configurer.noOfSendResponseTries();
		this.clientExpiration = configurer.clientExpiration();

		this.clients = new ConcurrentHashMap<>();

		this.errorQueue = configurer.errorQueue();
		this.sendQueue = configurer.sendQueue();

		this.taskScheduler.submit(this::eventLoop);
		this.taskScheduler.scheduleWithFixedDelay(this::reScheduleFailedEvents, 0,
				configurer.schedulerDelay().toMillis(), TimeUnit.MILLISECONDS);
		this.taskScheduler.scheduleWithFixedDelay(this::cleanUpClients, 0,
				configurer.clientExpirationJobDelay().toMillis(), TimeUnit.MILLISECONDS);
	}

	@PreDestroy
	public void cleanUp() {
		this.taskScheduler.shutdownNow();
	}

	public SseEmitter createSseEmitter(String clientId) {
		return createSseEmitter(clientId, 180_000L);
	}

	public SseEmitter createSseEmitter(String clientId, String... events) {
		return createSseEmitter(clientId, 180_000L, false, false, events);
	}

	public SseEmitter createSseEmitter(String clientId, boolean unsubscribe,
			String... events) {
		return createSseEmitter(clientId, 180_000L, unsubscribe, false, events);
	}

	public SseEmitter createSseEmitter(String clientId, Long timeout, String... events) {
		return createSseEmitter(clientId, timeout, false, false, events);
	}

	public SseEmitter createSseEmitter(String clientId, Long timeout, boolean unsubscribe,
			String... events) {
		return createSseEmitter(clientId, timeout, unsubscribe, false, events);
	}

	/**
	 * Creates a {@link SseEmitter} and registers the client in the internal database.
	 * Client will be subscribed to the provided events if specified.
	 *
	 * @param clientId unique client identifier
	 * @param timeout timeout value in milliseconds
	 * @param unsubscribe if true unsubscribes from all events that are not provided with
	 * the next parameter
	 * @param events events the client wants to subscribe
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createSseEmitter(String clientId, Long timeout, boolean unsubscribe,
			boolean completeAfterMessage, String... events) {
		SseEmitter emitter = new SseEmitter(timeout);
		emitter.onTimeout(emitter::complete);
		registerClient(clientId, emitter, completeAfterMessage);

		if (events != null && events.length > 0) {
			if (unsubscribe) {
				unsubscribeFromAllEvents(clientId, events);
			}
			for (String event : events) {
				subscribe(clientId, event);
			}
		}

		return emitter;
	}

	public void registerClient(String clientId, SseEmitter emitter) {
		this.registerClient(clientId, emitter, false);
	}

	public void registerClient(String clientId, SseEmitter emitter,
			boolean completeAfterMessage) {
		Client client = this.clients.get(clientId);
		if (client == null) {
			this.clients.put(clientId,
					new Client(clientId, emitter, completeAfterMessage));
		}
		else {
			client.updateEmitter(emitter);
		}
	}

	public void unregisterClient(String clientId) {
		unsubscribeFromAllEvents(clientId);
		this.clients.remove(clientId);
	}

	/**
	 * Subscribe to the default event (message)
	 */
	public void subscribe(String clientId) {
		subscribe(clientId, SseEvent.DEFAULT_EVENT);
	}

	public void subscribe(String clientId, String event) {
		this.subscriptionRegistry.subscribe(clientId, event);
	}

	/**
	 * Subscribe to the event and unsubscribe to all other currently subscribed events
	 */
	public void subscribeOnly(String clientId, String event) {
		this.subscriptionRegistry.subscribe(clientId, event);
		this.unsubscribeFromAllEvents(clientId, event);
	}

	public void unsubscribe(String clientId, String event) {
		this.subscriptionRegistry.unsubscribe(clientId, event);
	}

	/**
	 * Unsubscribe the client from all events except the events provided with the
	 * keepEvents parameter. When keepEvents is null the client unsubscribes from all
	 * events
	 */
	public void unsubscribeFromAllEvents(String clientId, String... keepEvents) {
		Set<String> keepEventsSet = null;
		if (keepEvents != null && keepEvents.length > 0) {
			keepEventsSet = new HashSet<>();
			for (String keepEvent : keepEvents) {
				keepEventsSet.add(keepEvent);
			}
		}

		Set<String> events = this.subscriptionRegistry.getAllEvents();
		if (keepEventsSet != null) {
			events = new HashSet<>(events);
			events.removeAll(keepEventsSet);
		}
		events.forEach(event -> unsubscribe(clientId, event));
	}

	@EventListener
	public void handleEvent(SseEvent event) {
		try {

			String convertedValue = null;
			if (!(event.data() instanceof String)) {
				convertedValue = this.convertObject(event);
			}

			if (event.clientIds().isEmpty()) {
				for (Client client : this.clients.values()) {
					if (!event.excludeClientIds().contains(client.getId())
							&& this.subscriptionRegistry.isClientSubscribedToEvent(
									client.getId(), event.event())) {
						this.sendQueue
								.put(new ClientEvent(client, event, convertedValue));
					}
				}
			}
			else {
				for (String clientId : event.clientIds()) {
					if (this.subscriptionRegistry.isClientSubscribedToEvent(clientId,
							event.event())) {
						this.sendQueue.put(new ClientEvent(this.clients.get(clientId),
								event, convertedValue));
					}
				}
			}
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private void reScheduleFailedEvents() {
		List<ClientEvent> failedEvents = new ArrayList<>();
		this.errorQueue.drainTo(failedEvents);

		for (ClientEvent sseClientEvent : failedEvents) {
			if (this.subscriptionRegistry.isClientSubscribedToEvent(
					sseClientEvent.getClient().getId(),
					sseClientEvent.getSseEvent().event())) {
				try {
					this.sendQueue.put(sseClientEvent);
				}
				catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}

	private void eventLoop() {
		try {
			while (true) {
				ClientEvent clientEvent = this.sendQueue.take();
				if (clientEvent.getErrorCounter() < this.noOfSendResponseTries) {
					Client client = clientEvent.getClient();
					boolean ok = sendEventToClient(clientEvent);
					if (ok) {
						client.updateLastTransfer();
					}
					else {
						clientEvent.incErrorCounter();
						this.errorQueue.put(clientEvent);
					}
				}
				else {
					this.unregisterClient(clientEvent.getClient().getId());
				}
			}
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private static boolean sendEventToClient(ClientEvent clientEvent) {
		Client client = clientEvent.getClient();
		try {
			client.sseEmitter().send(clientEvent.createSseEventBuilder());
			if (client.isCompleteAfterMessage()) {
				client.sseEmitter().complete();
			}
			return true;
		}
		catch (Exception e) {
			return false;
		}

	}

	private String convertObject(SseEvent event) {
		if (this.dataObjectConverters != null) {
			for (DataObjectConverter converter : this.dataObjectConverters) {
				if (converter.supports(event)) {
					return converter.convert(event);
				}
			}
		}
		return null;
	}

	private void cleanUpClients() {
		if (!this.clients.isEmpty()) {
			long expirationTime = System.currentTimeMillis()
					- this.clientExpiration.toMillis();
			Iterator<Entry<String, Client>> it = this.clients.entrySet().iterator();
			Set<String> staleClients = new HashSet<>();
			while (it.hasNext()) {
				Entry<String, Client> entry = it.next();
				if (entry.getValue().lastTransfer() < expirationTime) {
					staleClients.add(entry.getKey());
				}
			}
			staleClients.forEach(this::unregisterClient);
		}
	}

	public List<DataObjectConverter> getDataObjectConverters() {
		return this.dataObjectConverters;
	}

	public void setDataObjectConverters(List<DataObjectConverter> dataObjectConverters) {
		this.dataObjectConverters = dataObjectConverters;
	}

	/**
	 * Get a collection of all registered clientIds
	 *
	 * @return an unmodifiable set of all registered clientIds
	 */
	public Set<String> getAllClientIds() {
		return Collections.unmodifiableSet(this.clients.keySet());
	}

	/**
	 * Get a collection of all registered events
	 *
	 * @return an unmodifiable set of all events
	 */
	public Set<String> getAllEvents() {
		return this.subscriptionRegistry.getAllEvents();
	}

	/**
	 * Get a map that maps events to a collection of clientIds
	 *
	 * @return map with the event as key, the value is a set of clientIds
	 */
	public Map<String, Set<String>> getAllSubscriptions() {
		return this.subscriptionRegistry.getAllSubscriptions();
	}

	/**
	 * Get all subscribers to a particular event
	 *
	 * @return an unmodifiable set of all subscribed clientIds to this event. Empty when
	 * nobody is subscribed
	 */
	public Set<String> getSubscribers(String event) {
		return this.subscriptionRegistry.getSubscribers(event);
	}

	/**
	 * Get the number of subscribers to a particular event
	 *
	 * @return the number of clientIds subscribed to this event. 0 when nobody is
	 * subscribed
	 */
	public int countSubscribers(String event) {
		return this.subscriptionRegistry.countSubscribers(event);
	}

	/**
	 * Check if a particular event has subscribers
	 *
	 * @return true when the event has 1 or more subscribers.
	 */
	public boolean hasSubscribers(String event) {
		return countSubscribers(event) != 0;
	}
}
