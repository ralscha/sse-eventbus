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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
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

	private final @Nullable ScheduledExecutorService taskScheduler;

	private final int noOfSendResponseTries;

	private final Duration clientExpiration;

	private final List<DataObjectConverter> dataObjectConverters;

	private final BlockingQueue<ClientEvent> errorQueue;

	private final BlockingQueue<ClientEvent> sendQueue;

	private final SseEventBusListener listener;

	private final Duration schedulerDelay;

	private final Duration clientExpirationJobDelay;

	private final Duration heartbeatInterval;

	private final Duration replayRetention;

	private final Duration replayCleanupJobDelay;

	private final boolean schedulerEnabled;

	private final boolean replayEnabled;

	private final int sendWorkerCount;

	private final @Nullable ReplayStore replayStore;

	/**
	 * Per-client locks used to make {@link #storeReplayEvent} + {@link #queueOrSend}
	 * atomic with respect to {@link #replayMissedEvents}, preventing duplicate or
	 * out-of-order delivery on reconnect.
	 */
	private final ConcurrentMap<String, ReentrantLock> replayLocks = new ConcurrentHashMap<>();

	/**
	 * Creates a new instance of the SseEventBus.
	 * @param configurer The configurer to use for this instance.
	 * @param subscriptionRegistry The subscription registry to use for this instance.
	 * @param dataObjectConverters The list of data object converters.
	 * @param replayStore The replay store to use for this instance.
	 */
	public SseEventBus(SseEventBusConfigurer configurer, SubscriptionRegistry subscriptionRegistry,
			@Nullable List<DataObjectConverter> dataObjectConverters, @Nullable ReplayStore replayStore) {

		this.subscriptionRegistry = subscriptionRegistry;

		this.noOfSendResponseTries = configurer.noOfSendResponseTries();
		this.clientExpiration = configurer.clientExpiration();

		this.clients = configurer.clients();

		this.errorQueue = configurer.errorQueue();
		this.sendQueue = configurer.sendQueue();

		this.listener = configurer.listener();

		this.taskScheduler = configurer.taskScheduler();
		this.schedulerEnabled = this.taskScheduler != null;
		this.sendWorkerCount = Math.max(1, configurer.sendWorkerCount());
		this.schedulerDelay = configurer.schedulerDelay();
		this.clientExpirationJobDelay = configurer.clientExpirationJobDelay();
		this.heartbeatInterval = configurer.heartbeatInterval();
		this.replayStore = replayStore;
		this.replayEnabled = replayStore != null;
		this.replayRetention = configurer.replayRetention();
		this.replayCleanupJobDelay = configurer.replayCleanupJobDelay();

		this.dataObjectConverters = dataObjectConverters != null
				? Collections.unmodifiableList(new ArrayList<>(dataObjectConverters)) : List.of();
	}

	/**
	 * Starts the internal event loop and scheduled tasks. Called after the bean is fully
	 * initialized (including data object converters) to avoid race conditions.
	 */
	@PostConstruct
	public void init() {
		@Nullable ScheduledExecutorService scheduler = this.taskScheduler;
		if (scheduler != null) {
			for (int worker = 0; worker < this.sendWorkerCount; worker++) {
				ignoreFuture(scheduler.submit(this::eventLoop));
			}
			ignoreFuture(scheduler.scheduleWithFixedDelay(this::reScheduleFailedEvents, 0,
					this.schedulerDelay.toMillis(), TimeUnit.MILLISECONDS));
			ignoreFuture(scheduler.scheduleWithFixedDelay(this::cleanUpClients, 0,
					this.clientExpirationJobDelay.toMillis(), TimeUnit.MILLISECONDS));
			if (!this.heartbeatInterval.isZero() && !this.heartbeatInterval.isNegative()) {
				ignoreFuture(scheduler.scheduleWithFixedDelay(this::sendHeartbeat, this.heartbeatInterval.toMillis(),
						this.heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS));
			}
			if (this.replayEnabled && !this.replayCleanupJobDelay.isZero()
					&& !this.replayCleanupJobDelay.isNegative()) {
				ignoreFuture(scheduler.scheduleWithFixedDelay(this::purgeExpiredReplayEvents,
						this.replayCleanupJobDelay.toMillis(), this.replayCleanupJobDelay.toMillis(),
						TimeUnit.MILLISECONDS));
			}
			logger.info("SseEventBus started with " + this.sendWorkerCount + " send worker(s)");
		}
		else {
			logger
				.warn("SseEventBus started without scheduler; events are sent synchronously and retries are disabled");
		}
	}

	@PreDestroy
	public void cleanUp() {
		@Nullable ScheduledExecutorService scheduler = this.taskScheduler;
		if (scheduler != null) {
			logger.info("SseEventBus shutting down");
			scheduler.shutdownNow();
			try {
				scheduler.awaitTermination(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			// Flush remaining events after the event loop has stopped
			List<ClientEvent> remaining = new ArrayList<>();
			this.sendQueue.drainTo(remaining);
			for (ClientEvent clientEvent : remaining) {
				if (clientEvent.getErrorCounter() < this.noOfSendResponseTries) {
					Exception exception = sendEventToClient(clientEvent);
					if (exception != null && logger.isDebugEnabled()) {
						logger.debug("Shutdown flush failed for client " + clientEvent.getClient().getId(), exception);
					}
				}
			}
			if (!remaining.isEmpty()) {
				logger.info("SseEventBus flushed " + remaining.size() + " pending events on shutdown");
			}
			logger.info("SseEventBus shut down");
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
	public SseEmitter createSseEmitter(String clientId, String @Nullable ... events) {
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
	public SseEmitter createSseEmitter(String clientId, boolean unsubscribe, String @Nullable ... events) {
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
	public SseEmitter createSseEmitter(String clientId, Long timeout, String @Nullable ... events) {
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
	public SseEmitter createSseEmitter(String clientId, Long timeout, boolean unsubscribe, String @Nullable ... events) {
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
			String @Nullable ... events) {
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
	 * Creates a replay-aware {@link SseEmitter}, subscribes the client to the provided
	 * events and replays retained events after the provided {@code lastEventId}.
	 * @param clientId unique client identifier
	 * @param timeout timeout value in milliseconds
	 * @param unsubscribe if true unsubscribes from all events that are not provided with
	 * the events parameter
	 * @param completeAfterMessage if true the connection is closed after the first
	 * message
	 * @param lastEventId last event id received by the reconnecting client
	 * @param events events the client wants to subscribe
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createReplayableSseEmitter(String clientId, Long timeout, boolean unsubscribe,
			boolean completeAfterMessage, @Nullable String lastEventId, String @Nullable ... events) {
		SseEmitter emitter = createSseEmitter(clientId, timeout, unsubscribe, completeAfterMessage, events);
		replayMissedEvents(clientId, lastEventId);
		return emitter;
	}

	/**
	 * Creates a replay-aware {@link SseEmitter} with a default timeout of 180_000
	 * milliseconds (3 minutes).
	 * @param clientId unique client identifier
	 * @param lastEventId last event id received by the reconnecting client
	 * @param events events the client wants to subscribe
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createReplayableSseEmitter(String clientId, @Nullable String lastEventId,
			String @Nullable ... events) {
		return createReplayableSseEmitter(clientId, 180_000L, false, false, lastEventId, events);
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
		AtomicReference<SseEmitter> oldEmitter = new AtomicReference<>();
		this.clients.compute(clientId, (id, existing) -> {
			if (existing == null) {
				return new Client(id, emitter, completeAfterMessage);
			}
			oldEmitter.set(existing.sseEmitter());
			existing.updateEmitter(emitter);
			existing.updateCompleteAfterMessage(completeAfterMessage);
			existing.updateLastTransfer();
			return existing;
		});
		if (this.replayEnabled) {
			this.replayLocks.computeIfAbsent(clientId, k -> new ReentrantLock());
		}
		if (oldEmitter.get() != null) {
			try {
				oldEmitter.get().complete();
			}
			catch (Exception e) {
				logger.debug("Error completing old emitter for client " + clientId, e);
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Client re-registered: " + clientId);
			}
		}
		else if (logger.isDebugEnabled()) {
			logger.debug("Client registered: " + clientId);
		}
	}

	/**
	 * Registers a client and replays retained events after the provided last event id.
	 * @param clientId unique client identifier
	 * @param emitter the SseEmitter of the client
	 * @param completeAfterMessage if true the connection is closed after sending the
	 * first message
	 * @param lastEventId last event id received by the reconnecting client
	 */
	public void registerClient(String clientId, SseEmitter emitter, boolean completeAfterMessage,
			@Nullable String lastEventId) {
		registerClient(clientId, emitter, completeAfterMessage);
		replayMissedEvents(clientId, lastEventId);
	}

	/**
	 * Unregisters a client and unsubscribes the client from all events.
	 * @param clientId unique client identifier
	 */
	public void unregisterClient(String clientId) {
		AtomicReference<SseEmitter> removedEmitter = new AtomicReference<>();
		this.clients.computeIfPresent(clientId, (id, client) -> {
			removedEmitter.set(client.sseEmitter());
			this.subscriptionRegistry.unsubscribeAll(id);
			return null;
		});
		removePendingReplayableEvents(clientId);
		clearReplayEvents(clientId);
		this.replayLocks.remove(clientId);
		if (removedEmitter.get() != null) {
			try {
				removedEmitter.get().complete();
			}
			catch (Exception e) {
				logger.debug("Error completing emitter for client " + clientId, e);
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Client unregistered: " + clientId);
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
		this.unsubscribeFromAllEvents(clientId, event);
		this.subscriptionRegistry.subscribe(clientId, event);
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
	public void unsubscribeFromAllEvents(String clientId, String @Nullable ... keepEvents) {
		@Nullable Set<String> keepEventsSet = null;
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

			@Nullable String convertedValue = null;
			boolean converted = event.data() instanceof String;

			if (event.clientIds().isEmpty()) {
				Set<String> subscribers = this.subscriptionRegistry.getSubscribers(event.event());
				Set<String> excludes = event.excludeClientIds();
				for (String subscriberId : subscribers) {
					if (!excludes.isEmpty() && excludes.contains(subscriberId)) {
						continue;
					}
					@Nullable Client client = this.clients.get(subscriberId);
					if (client != null) {
						if (!converted) {
							convertedValue = this.convertObject(event);
							converted = true;
						}
						if (this.replayEnabled) {
							ReentrantLock lock = this.replayLocks.computeIfAbsent(subscriberId,
									k -> new ReentrantLock());
							lock.lock();
							try {
								storeReplayEvent(subscriberId, event, convertedValue);
								ClientEvent clientEvent = new ClientEvent(client, event, convertedValue);
								queueOrSend(clientEvent, true);
							}
							finally {
								lock.unlock();
							}
						}
						else {
							ClientEvent clientEvent = new ClientEvent(client, event, convertedValue);
							queueOrSend(clientEvent, true);
						}
					}
				}
			}
			else {
				for (String clientId : event.clientIds()) {
					@Nullable Client client = this.clients.get(clientId);
					if (client != null
							&& this.subscriptionRegistry.isClientSubscribedToEvent(clientId, event.event())) {
						if (!converted) {
							convertedValue = this.convertObject(event);
							converted = true;
						}
						if (this.replayEnabled) {
							ReentrantLock lock = this.replayLocks.computeIfAbsent(clientId, k -> new ReentrantLock());
							lock.lock();
							try {
								storeReplayEvent(clientId, event, convertedValue);
								ClientEvent clientEvent = new ClientEvent(client, event, convertedValue);
								queueOrSend(clientEvent, true);
							}
							finally {
								lock.unlock();
							}
						}
						else {
							ClientEvent clientEvent = new ClientEvent(client, event, convertedValue);
							queueOrSend(clientEvent, true);
						}
					}
				}
			}
		}
		catch (InterruptedException e) {
			logger.error("handleEvent failed", e);
			Thread.currentThread().interrupt();
		}
	}

	private void queueOrSend(ClientEvent clientEvent, boolean firstAttempt) throws InterruptedException {
		if (this.schedulerEnabled) {
			this.sendQueue.put(clientEvent);
			notifyAfterEventQueued(clientEvent, firstAttempt);
			return;
		}

		notifyAfterEventQueued(clientEvent, firstAttempt);
		@Nullable Exception exception = sendEventToClient(clientEvent);
		if (exception == null) {
			clientEvent.getClient().updateLastTransfer();
		}
		notifyAfterEventSent(clientEvent, exception);
		if (exception != null && logger.isDebugEnabled()) {
			logger.debug("Synchronous send failed for client " + clientEvent.getClient().getId(), exception);
		}
	}

	private void notifyAfterEventQueued(ClientEvent clientEvent, boolean firstAttempt) {
		try {
			this.listener.afterEventQueued(clientEvent, firstAttempt);
		}
		catch (Exception e) {
			logger.error("calling afterEventQueued hook failed", e);
		}
	}

	private void notifyAfterEventSent(ClientEvent clientEvent, @Nullable Exception exception) {
		try {
			this.listener.afterEventSent(clientEvent, exception);
		}
		catch (Exception e) {
			logger.error("calling afterEventSent hook failed", e);
		}
	}

	private void reScheduleFailedEvents() {
		try {
			List<ClientEvent> failedEvents = new ArrayList<>();
			this.errorQueue.drainTo(failedEvents);

			for (ClientEvent sseClientEvent : failedEvents) {
				String clientId = sseClientEvent.getClient().getId();

				// Skip events for clients that are no longer registered
				if (!this.clients.containsKey(clientId)) {
					continue;
				}

				// Respect exponential backoff — not ready yet, put back
				if (!sseClientEvent.isReadyForRetry()) {
					try {
						this.errorQueue.put(sseClientEvent);
					}
					catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
					continue;
				}

				if (this.subscriptionRegistry.isClientSubscribedToEvent(clientId,
						sseClientEvent.getSseEvent().event())) {
					try {
						this.sendQueue.put(sseClientEvent);
						notifyAfterEventQueued(sseClientEvent, false);
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
					@Nullable Exception e = sendEventToClient(clientEvent);
					if (e == null) {
						client.updateLastTransfer();
						notifyAfterEventSent(clientEvent, null);
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
						notifyAfterEventSent(clientEvent, e);
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

	private static @Nullable Exception sendEventToClient(ClientEvent clientEvent) {
		Client client = clientEvent.getClient();
		try {
			client.sseEmitter().send(clientEvent.createSseEventBuilder());
			if (client.isCompleteAfterMessage()) {
				client.sseEmitter().complete();
			}
			return null;
		}
		catch (java.io.IOException | RuntimeException e) {
			return e;
		}

	}

	private @Nullable String convertObject(SseEvent event) {
		for (DataObjectConverter converter : this.dataObjectConverters) {
			if (converter.supports(event)) {
				try {
					return converter.convert(event);
				}
				catch (Exception e) {
					logger.error("DataObjectConverter failed for event '" + event.event() + "'", e);
					return null;
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
			Set<String> actuallyRemoved = new HashSet<>();
			long recheckExpiration = System.currentTimeMillis() - this.clientExpiration.toMillis();
			for (String clientId : staleClients) {
				AtomicReference<SseEmitter> removedEmitter = new AtomicReference<>();
				this.clients.computeIfPresent(clientId, (id, client) -> {
					if (client.lastTransfer() < recheckExpiration) {
						removedEmitter.set(client.sseEmitter());
						this.subscriptionRegistry.unsubscribeAll(id);
						return null;
					}
					return client;
				});
				if (removedEmitter.get() != null) {
					actuallyRemoved.add(clientId);
					removePendingReplayableEvents(clientId);
					clearReplayEvents(clientId);
					this.replayLocks.remove(clientId);
					try {
						removedEmitter.get().complete();
					}
					catch (RuntimeException e) {
						logger.debug("Error completing emitter for client " + clientId, e);
					}
				}
			}
			if (!actuallyRemoved.isEmpty()) {
				this.listener.afterClientsUnregistered(actuallyRemoved);
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
	 * Check if a client is currently registered.
	 * @param clientId unique client identifier
	 * @return true if the client is registered
	 */
	public boolean isClientRegistered(String clientId) {
		return this.clients.containsKey(clientId);
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
		return this.subscriptionRegistry.hasSubscribers(event);
	}

	/**
	 * Get the number of currently connected clients.
	 * @return the number of registered clients
	 */
	public int getClientCount() {
		return this.clients.size();
	}

	/**
	 * Get the current size of the send queue.
	 * @return number of events waiting to be sent
	 */
	public int getSendQueueSize() {
		return this.sendQueue.size();
	}

	/**
	 * Get the current size of the error/retry queue.
	 * @return number of events waiting to be retried
	 */
	public int getErrorQueueSize() {
		return this.errorQueue.size();
	}

	/**
	 * Replays retained events after the provided last event id to a currently registered
	 * client.
	 * @param clientId unique client identifier
	 * @param lastEventId last event id received by the reconnecting client
	 */
	public void replayMissedEvents(String clientId, @Nullable String lastEventId) {
		if (!this.replayEnabled || lastEventId == null || lastEventId.isEmpty()) {
			return;
		}
		@Nullable ReplayStore replayStore = this.replayStore;
		if (replayStore == null) {
			return;
		}

		@Nullable Client client = this.clients.get(clientId);
		if (client == null) {
			return;
		}

		// Acquire the per-client lock so that store+queue in handleEvent cannot
		// interleave with the remove-pending + re-queue sequence here. Without this
		// lock a concurrent handleEvent could add an event to both the store (before
		// getEventsSince) and the send queue (after removePendingReplayableEvents),
		// resulting in duplicate or out-of-order delivery.
		ReentrantLock lock = this.replayLocks.computeIfAbsent(clientId, k -> new ReentrantLock());
		lock.lock();
		try {
			removePendingReplayableEvents(clientId);

			for (ReplayEvent replayEvent : replayStore.getEventsSince(clientId, lastEventId)) {
				if (!this.subscriptionRegistry.isClientSubscribedToEvent(clientId, replayEvent.sseEvent().event())) {
					continue;
				}
				queueOrSend(new ClientEvent(client, replayEvent.sseEvent(), replayEvent.convertedValue()), true);
			}
		}
		catch (InterruptedException e) {
			logger.error("replayMissedEvents failed", e);
			Thread.currentThread().interrupt();
		}
		finally {
			lock.unlock();
		}
	}

	private void sendHeartbeat() {
		for (Client client : this.clients.values()) {
			try {
				client.sseEmitter().send(SseEmitter.event().comment("heartbeat"));
				client.updateLastTransfer();
			}
			catch (java.io.IOException | RuntimeException e) {
				logger.debug("Heartbeat failed for client " + client.getId(), e);
			}
		}
	}

	private void storeReplayEvent(String clientId, SseEvent event, @Nullable String convertedValue) {
		if (!this.replayEnabled || event.id().isEmpty() || event.id().get().isEmpty()) {
			return;
		}
		@Nullable ReplayStore replayStore = this.replayStore;
		if (replayStore == null) {
			return;
		}
		replayStore.store(new ReplayEvent(clientId, event, resolveReplayValue(event, convertedValue),
				System.currentTimeMillis()));
	}

	private static @Nullable String resolveReplayValue(SseEvent event, @Nullable String convertedValue) {
		if (convertedValue != null) {
			return convertedValue;
		}
		if (event.data() instanceof String stringData) {
			return stringData;
		}
		return null;
	}

	private static void ignoreFuture(@SuppressWarnings("unused") Future<?> future) {
		// Intentionally fire-and-forget. Errors surface via the executor and logs.
	}

	private void removePendingReplayableEvents(String clientId) {
		this.sendQueue.removeIf(clientEvent -> clientEvent.getClient().getId().equals(clientId)
				&& clientEvent.getSseEvent().id().isPresent());
		this.errorQueue.removeIf(clientEvent -> clientEvent.getClient().getId().equals(clientId)
				&& clientEvent.getSseEvent().id().isPresent());
	}

	private void purgeExpiredReplayEvents() {
		if (!this.replayEnabled) {
			return;
		}
		@Nullable ReplayStore replayStore = this.replayStore;
		if (replayStore == null) {
			return;
		}
		replayStore.purgeExpired(System.currentTimeMillis() - this.replayRetention.toMillis());
	}

	private void clearReplayEvents(String clientId) {
		if (!this.replayEnabled) {
			return;
		}
		@Nullable ReplayStore replayStore = this.replayStore;
		if (replayStore == null) {
			return;
		}
		replayStore.clearClient(clientId);
	}

}
