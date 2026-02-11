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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * The central class for managing Server-Sent Events (SSE).
 * <p>
 * This class manages clients, subscriptions, and the broadcasting of events. It is
 * thread-safe.
 */
public class SseEventBus {

	private static final Log logger = LogFactory.getLog(SseEventBus.class);

	/**
	 * A map that holds all connected clients. The key is the client ID, and the value is
	 * the {@link Client} object.
	 */
	private final ConcurrentMap<String, Client> clients;

	private final SubscriptionRegistry subscriptionRegistry;

	private final ScheduledExecutorService taskScheduler;

	private final int noOfSendResponseTries;

	private final Duration clientExpiration;

	private volatile List<DataObjectConverter> dataObjectConverters;

	private final BlockingQueue<ClientEvent> errorQueue;

	private final BlockingQueue<ClientEvent> sendQueue;

	private final SseEventBusListener listener;

	private final Duration schedulerDelay;

	private final Duration clientExpirationJobDelay;

	/**
	 * Creates a new instance of the SseEventBus.
	 * @param configurer The configurer to use for this instance.
	 * @param subscriptionRegistry The subscription registry to use for this instance.
	 */
	public SseEventBus(SseEventBusConfigurer configurer, SubscriptionRegistry subscriptionRegistry) {

		this.subscriptionRegistry = subscriptionRegistry;

		this.noOfSendResponseTries = configurer.noOfSendResponseTries();
		this.clientExpiration = configurer.clientExpiration();

		this.clients = configurer.clients();

		this.errorQueue = configurer.errorQueue();
		this.sendQueue = configurer.sendQueue();

		this.listener = configurer.listener();

		this.taskScheduler = configurer.taskScheduler();
		this.schedulerDelay = configurer.schedulerDelay();
		this.clientExpirationJobDelay = configurer.clientExpirationJobDelay();
	}

	/**
	 * Starts the internal event loop and scheduled tasks. Called after the bean is fully
	 * initialized (including data object converters) to avoid race conditions.
	 */
	@PostConstruct
	public void init() {
		if (this.taskScheduler != null) {
			this.taskScheduler.submit(this::eventLoop);
			this.taskScheduler.scheduleWithFixedDelay(this::reScheduleFailedEvents, 0,
					this.schedulerDelay.toMillis(), TimeUnit.MILLISECONDS);
			this.taskScheduler.scheduleWithFixedDelay(this::cleanUpClients, 0,
					this.clientExpirationJobDelay.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	@PreDestroy
	public void cleanUp() {
		if (this.taskScheduler != null) {
			this.taskScheduler.shutdown();
			try {
				if (!this.taskScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
					this.taskScheduler.shutdownNow();
				}
			}
			catch (InterruptedException e) {
				this.taskScheduler.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Creates a new {@link SseEmitter} with a default timeout of 180_000 milliseconds (3
	 * minutes) and registers the client.
	 * @param clientId unique client identifier
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createSseEmitter(String clientId) {
		return createSseEmitter(clientId, 180_000L);
	}

	/**
	 * Creates a new {@link SseEmitter} with a default timeout of 180_000 milliseconds (3
	 * minutes), registers the client and subscribes the client to the provided events.
	 * @param clientId unique client identifier
	 * @param events events the client wants to subscribe
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createSseEmitter(String clientId, String... events) {
		return createSseEmitter(clientId, 180_000L, false, false, events);
	}

	/**
	 * Creates a new {@link SseEmitter} with a default timeout of 180_000 milliseconds (3
	 * minutes), registers the client and subscribes the client to the provided events.
	 * @param clientId unique client identifier
	 * @param unsubscribe if true unsubscribes from all events that are not provided with
	 * the next parameter
	 * @param events events the client wants to subscribe
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createSseEmitter(String clientId, boolean unsubscribe, String... events) {
		return createSseEmitter(clientId, 180_000L, unsubscribe, false, events);
	}

	/**
	 * Creates a new {@link SseEmitter}, registers the client and subscribes the client to
	 * the provided events.
	 * @param clientId unique client identifier
	 * @param timeout timeout value in milliseconds
	 * @param events events the client wants to subscribe
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createSseEmitter(String clientId, Long timeout, String... events) {
		return createSseEmitter(clientId, timeout, false, false, events);
	}

	/**
	 * Creates a new {@link SseEmitter}, registers the client and subscribes the client to
	 * the provided events.
	 * @param clientId unique client identifier
	 * @param timeout timeout value in milliseconds
	 * @param unsubscribe if true unsubscribes from all events that are not provided with
	 * the next parameter
	 * @param events events the client wants to subscribe
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createSseEmitter(String clientId, Long timeout, boolean unsubscribe, String... events) {
		return createSseEmitter(clientId, timeout, unsubscribe, false, events);
	}

	/**
	 * Creates a {@link SseEmitter} and registers the client in the internal database.
	 * Client will be subscribed to the provided events if specified.
	 * @param clientId unique client identifier
	 * @param timeout timeout value in milliseconds
	 * @param unsubscribe if true unsubscribes from all events that are not provided with
	 * the events parameter
	 * @param completeAfterMessage if true the connection is closed after sending the
	 * first message
	 * @param events events the client wants to subscribe
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createSseEmitter(String clientId, Long timeout, boolean unsubscribe, boolean completeAfterMessage,
			String... events) {
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

	/**
	 * Registers a client.
	 * @param clientId unique client identifier
	 * @param emitter the SseEmitter of the client
	 */
	public void registerClient(String clientId, SseEmitter emitter) {
		this.registerClient(clientId, emitter, false);
	}

	/**
	 * Registers a client.
	 * @param clientId unique client identifier
	 * @param emitter the SseEmitter of the client
	 * @param completeAfterMessage if true the connection is closed after sending the
	 * first message
	 */
	public void registerClient(String clientId, SseEmitter emitter, boolean completeAfterMessage) {
		this.clients.compute(clientId, (id, existing) -> {
			if (existing == null) {
				return new Client(id, emitter, completeAfterMessage);
			}
			existing.updateEmitter(emitter);
			return existing;
		});
	}

	/**
	 * Unregisters a client and unsubscribes the client from all events.
	 * @param clientId unique client identifier
	 */
	public void unregisterClient(String clientId) {
		unsubscribeFromAllEvents(clientId);
		Client removed = this.clients.remove(clientId);
		if (removed != null) {
			try {
				removed.sseEmitter().complete();
			}
			catch (Exception e) {
				logger.debug("Error completing emitter for client " + clientId, e);
			}
		}
	}

	/**
	 * Subscribe to the default event (message).
	 * @param clientId unique client identifier
	 */
	public void subscribe(String clientId) {
		subscribe(clientId, SseEvent.DEFAULT_EVENT);
	}

	/**
	 * Subscribe to a specific event.
	 * @param clientId unique client identifier
	 * @param event the event name
	 */
	public void subscribe(String clientId, String event) {
		this.subscriptionRegistry.subscribe(clientId, event);
	}

	/**
	 * Subscribe to the event and unsubscribe from all other currently subscribed events.
	 * @param clientId unique client identifier
	 * @param event the event name
	 */
	public void subscribeOnly(String clientId, String event) {
		this.subscriptionRegistry.subscribe(clientId, event);
		this.unsubscribeFromAllEvents(clientId, event);
	}

	/**
	 * Unsubscribe from a specific event.
	 * @param clientId unique client identifier
	 * @param event the event name
	 */
	public void unsubscribe(String clientId, String event) {
		this.subscriptionRegistry.unsubscribe(clientId, event);
	}

	/**
	 * Unsubscribe the client from all events except the events provided with the
	 * keepEvents parameter. When keepEvents is null or empty the client unsubscribes from
	 * all events.
	 * @param clientId unique client identifier
	 * @param keepEvents events the client should stay subscribed to
	 */
	public void unsubscribeFromAllEvents(String clientId, String... keepEvents) {
		Set<String> keepEventsSet = null;
		if (keepEvents != null && keepEvents.length > 0) {
			keepEventsSet = new HashSet<>();
			Collections.addAll(keepEventsSet, keepEvents);
		}

		Set<String> events = this.subscriptionRegistry.getAllEvents();
		if (keepEventsSet != null) {
			events = new HashSet<>(events);
			events.removeAll(keepEventsSet);
		}
		events.forEach(event -> unsubscribe(clientId, event));
	}

	/**
	 * Handles a {@link SseEvent} and sends it to the appropriate clients.
	 * @param event the event to handle
	 */
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
							&& this.subscriptionRegistry.isClientSubscribedToEvent(client.getId(), event.event())) {
						ClientEvent clientEvent = new ClientEvent(client, event, convertedValue);
						this.sendQueue.put(clientEvent);
						this.listener.afterEventQueued(clientEvent, true);
					}
				}
			}
			else {
				for (String clientId : event.clientIds()) {
					Client client = this.clients.get(clientId);
					if (client != null
								&& this.subscriptionRegistry.isClientSubscribedToEvent(clientId, event.event())) {
						ClientEvent clientEvent = new ClientEvent(client, event, convertedValue);
						this.sendQueue.put(clientEvent);
						this.listener.afterEventQueued(clientEvent, true);
					}
				}
			}
		}
		catch (InterruptedException e) {
			logger.error("handleEvent failed", e);
			Thread.currentThread().interrupt();
		}
	}

	private void reScheduleFailedEvents() {
		try {
			List<ClientEvent> failedEvents = new ArrayList<>();
			this.errorQueue.drainTo(failedEvents);

			for (ClientEvent sseClientEvent : failedEvents) {
				if (this.subscriptionRegistry.isClientSubscribedToEvent(sseClientEvent.getClient().getId(),
						sseClientEvent.getSseEvent().event())) {
					try {
						this.sendQueue.put(sseClientEvent);
						try {
							this.listener.afterEventQueued(sseClientEvent, false);
						}
						catch (Exception e) {
							logger.error("calling afterEventQueued hook failed", e);
						}
					}
					catch (InterruptedException ie) {
						logger.error("re-adding event into send queue failed", ie);
						Thread.currentThread().interrupt();
					}
					catch (Exception e) {
						logger.error("re-adding event into send queue failed", e);
						try {
							this.errorQueue.put(sseClientEvent);
						}
						catch (InterruptedException ie) {
							logger.error("re-adding event into error queue failed", ie);
							Thread.currentThread().interrupt();
						}
					}
				}
			}
		}
		catch (Exception e) {
			logger.error("reScheduleFailedEvents failed", e);
		}
	}

	private void eventLoop() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				ClientEvent clientEvent = this.sendQueue.take();
				if (clientEvent.getErrorCounter() < this.noOfSendResponseTries) {
					Client client = clientEvent.getClient();
					Exception e = sendEventToClient(clientEvent);
					if (e == null) {
						client.updateLastTransfer();
						try {
							this.listener.afterEventSent(clientEvent, null);
						}
						catch (Exception ex) {
							logger.error("calling afterEventSent hook failed", ex);
						}
					}
					else {
						clientEvent.incErrorCounter();
						try {
							this.errorQueue.put(clientEvent);
						}
						catch (InterruptedException ie) {
							logger.error("adding event into error queue failed", ie);
							Thread.currentThread().interrupt();
						}
						try {
							this.listener.afterEventSent(clientEvent, e);
						}
						catch (Exception ex) {
							logger.error("calling afterEventSent hook failed", ex);
						}
					}
				}
				else {
					String clientId = clientEvent.getClient().getId();
					this.unregisterClient(clientId);
					try {
						this.listener.afterClientsUnregistered(Collections.singleton(clientId));
					}
					catch (Exception ex) {
						logger.error("calling afterClientsUnregistered hook failed", ex);
					}
				}
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
			catch (Exception ex) {
				logger.error("eventLoop run failed", ex);
			}
		}
	}

	private static Exception sendEventToClient(ClientEvent clientEvent) {
		Client client = clientEvent.getClient();
		try {
			client.sseEmitter().send(clientEvent.createSseEventBuilder());
			if (client.isCompleteAfterMessage()) {
				client.sseEmitter().complete();
			}
			return null;
		}
		catch (Exception e) {
			return e;
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
			long expirationTime = System.currentTimeMillis() - this.clientExpiration.toMillis();
			Set<String> staleClients = new HashSet<>();
			for (Entry<String, Client> entry : this.clients.entrySet()) {
				if (entry.getValue().lastTransfer() < expirationTime) {
					staleClients.add(entry.getKey());
				}
			}
			staleClients.forEach(this::unregisterClient);
			if (!staleClients.isEmpty()) {
				this.listener.afterClientsUnregistered(staleClients);
			}
		}
	}

	/**
	 * Returns the list of {@link DataObjectConverter}s.
	 * @return the list of converters
	 */
	public List<DataObjectConverter> getDataObjectConverters() {
		return this.dataObjectConverters;
	}

	/**
	 * Sets the list of {@link DataObjectConverter}s.
	 * @param dataObjectConverters the list of converters
	 */
	public void setDataObjectConverters(List<DataObjectConverter> dataObjectConverters) {
		this.dataObjectConverters = dataObjectConverters;
	}

	/**
	 * Get a collection of all registered clientIds
	 * @return an unmodifiable set of all registered clientIds
	 */
	public Set<String> getAllClientIds() {
		return Collections.unmodifiableSet(this.clients.keySet());
	}

	/**
	 * Get a collection of all registered events
	 * @return an unmodifiable set of all events
	 */
	public Set<String> getAllEvents() {
		return this.subscriptionRegistry.getAllEvents();
	}

	/**
	 * Get a map that maps events to a collection of clientIds
	 * @return map with the event as key, the value is a set of clientIds
	 */
	public Map<String, Set<String>> getAllSubscriptions() {
		return this.subscriptionRegistry.getAllSubscriptions();
	}

	/**
	 * Get all subscribers to a particular event
	 * @param event the event name
	 * @return an unmodifiable set of all subscribed clientIds to this event. Empty when
	 * nobody is subscribed
	 */
	public Set<String> getSubscribers(String event) {
		return this.subscriptionRegistry.getSubscribers(event);
	}

	/**
	 * Get the number of subscribers to a particular event
	 * @param event the event name
	 * @return the number of clientIds subscribed to this event. 0 when nobody is
	 * subscribed
	 */
	public int countSubscribers(String event) {
		return this.subscriptionRegistry.countSubscribers(event);
	}

	/**
	 * Check if a particular event has subscribers
	 * @param event the event name
	 * @return true when the event has 1 or more subscribers.
	 */
	public boolean hasSubscribers(String event) {
		return countSubscribers(event) != 0;
	}

}
