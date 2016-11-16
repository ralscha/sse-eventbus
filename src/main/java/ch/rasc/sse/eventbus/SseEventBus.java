/**
 * Copyright 2016-2016 Ralph Schaer <ralphschaer@gmail.com>
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.PreDestroy;

import org.apache.commons.logging.LogFactory;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

public class SseEventBus {

	/**
	 * Client Id -> SseEmitter
	 */
	private final Map<String, SseClient> clients;

	/**
	 * Client Id -> Number of failed connection tries
	 */
	private final Map<String, Integer> failedClients;

	/**
	 * EventName -> Collection of Client Ids
	 */
	private final Map<String, Set<String>> eventSubscribers;

	/**
	 * Client Id -> List of EventBusEvents
	 */
	private final Map<String, List<SseEvent>> pendingClientEvents;

	private final ScheduledExecutorService taskScheduler;

	private int noOfSendResponseTries;

	private int clientExpirationInSeconds;

	private List<DataObjectConverter> dataObjectConverters;

	public SseEventBus(ScheduledExecutorService taskScheduler,
			int clientExpirationInSeconds, int schedulerDelayInMilliseconds,
			int noOfSendResponseTries) {

		this.taskScheduler = taskScheduler;
		this.noOfSendResponseTries = noOfSendResponseTries;
		this.clientExpirationInSeconds = clientExpirationInSeconds;

		this.clients = new ConcurrentHashMap<>();
		this.failedClients = new ConcurrentHashMap<>();
		this.eventSubscribers = new ConcurrentHashMap<>();
		this.pendingClientEvents = new ConcurrentHashMap<>();

		taskScheduler.scheduleWithFixedDelay(this::eventLoop, 0,
				schedulerDelayInMilliseconds, TimeUnit.MILLISECONDS);
		taskScheduler.scheduleAtFixedRate(this::cleanUpClients, 0,
				clientExpirationInSeconds, TimeUnit.SECONDS);
	}

	@PreDestroy
	public void cleanUp() {
		this.taskScheduler.shutdown();
	}

	public SseEmitter createSseEmitter(String clientId) {
		return createSseEmitter(clientId, 180_000L);
	}

	public SseEmitter createSseEmitter(String clientId, String... events) {
		return createSseEmitter(clientId, 180_000L, events);
	}

	/**
	 * Creates a {@link SseEmitter} and registers the client in the internal database.
	 * Clients will be subscribed to the provided events if specified.
	 *
	 * @param clientId unique client identifier
	 * @param timeout timeout value in milliseconds
	 * @param events names of the events a client want to subscribes
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createSseEmitter(String clientId, Long timeout, String... events) {
		SseEmitter emitter = new SseEmitter(timeout);
		emitter.onTimeout(emitter::complete);
		registerClient(clientId, emitter);

		if (events != null && events.length > 0) {
			for (String event : events) {
				subscribe(clientId, event);
			}
		}

		return emitter;
	}

	public void registerClient(String clientId, SseEmitter emitter) {
		this.clients.put(clientId, new SseClient(emitter));
		this.failedClients.remove(clientId);
	}

	public void unregisterClient(String clientId) {
		Set<String> emptyEvents = new HashSet<>();
		for (Map.Entry<String, Set<String>> entry : this.eventSubscribers.entrySet()) {
			Set<String> clientIds = entry.getValue();
			clientIds.remove(clientId);
			if (clientIds.isEmpty()) {
				emptyEvents.add(entry.getKey());
			}
		}
		emptyEvents.forEach(this.eventSubscribers::remove);
		this.failedClients.remove(clientId);
		this.pendingClientEvents.remove(clientId);
		this.clients.remove(clientId);
	}

	/**
	 * Subscribe to the default event (message)
	 */
	public void subscribe(String clientId) {
		subscribe(clientId, SseEvent.DEFAULT_EVENT);
	}

	public void subscribe(String clientId, String event) {
		this.eventSubscribers.computeIfAbsent(event, k -> new HashSet<>()).add(clientId);
	}

	public void unsubscribe(String clientId, String event) {
		Set<String> clientIds = this.eventSubscribers.get(event);
		if (clientIds != null) {
			clientIds.remove(clientId);
			if (clientIds.isEmpty()) {
				this.eventSubscribers.remove(event);
			}
		}

		List<SseEvent> clientEvents = this.pendingClientEvents.get(clientId);
		if (clientEvents != null) {
			Iterator<SseEvent> it = clientEvents.iterator();
			while (it.hasNext()) {
				SseEvent ebe = it.next();
				if (ebe.event().equals(event)) {
					it.remove();
				}
			}
			if (clientEvents.isEmpty()) {
				this.pendingClientEvents.remove(clientId);
			}
		}
	}

	@EventListener
	public void handleEvent(SseEvent event) {
		if (event.clientIds().isEmpty()) {
			for (String clientId : this.clients.keySet()) {
				if (isUserSubscribed(clientId, event)) {
					this.pendingClientEvents
							.computeIfAbsent(clientId, k -> new ArrayList<>()).add(event);
				}
			}
		}
		else {
			for (String clientId : event.clientIds()) {
				if (isUserSubscribed(clientId, event)) {
					this.pendingClientEvents
							.computeIfAbsent(clientId, k -> new ArrayList<>()).add(event);
				}
			}
		}
	}

	private boolean isUserSubscribed(String clientId, SseEvent event) {
		Set<String> subscribedClients = this.eventSubscribers.get(event.event());
		if (subscribedClients != null) {
			return subscribedClients.contains(clientId);
		}
		return false;
	}

	private void eventLoop() {
		try {
			if (this.eventSubscribers.isEmpty()) {
				return;
			}

			Iterator<Entry<String, List<SseEvent>>> it = this.pendingClientEvents
					.entrySet().iterator();
			Map<String, List<SseEvent>> failedMessages = new HashMap<>();
			while (it.hasNext()) {
				Map.Entry<String, List<SseEvent>> entry = it.next();
				it.remove();
				if (!sendMessagesToClient(entry.getKey(), entry.getValue())) {
					failedMessages.put(entry.getKey(), entry.getValue());
				}
			}
			this.pendingClientEvents.putAll(failedMessages);

			this.failedClients.entrySet().stream()
					.filter(e -> e.getValue() >= this.noOfSendResponseTries)
					.forEach(e -> {
						unregisterClient(e.getKey());
					});
		}
		catch (Exception e) {
			LogFactory.getLog(getClass()).error("exception in the sse eventbus loop", e);
		}
	}

	private boolean sendMessagesToClient(String clientId, List<SseEvent> events) {
		SseClient client = this.clients.get(clientId);
		if (client != null) {
			Map<String, List<SseEvent>> eventNameEvents = events.stream()
					.collect(Collectors.groupingBy(SseEvent::event));

			SseEventBuilder sseBuilder = SseEmitter.event();
			for (Entry<String, List<SseEvent>> ene : eventNameEvents.entrySet()) {
				List<SseEvent> datas = new ArrayList<>();
				for (SseEvent evt : ene.getValue()) {
					if (!evt.combine()) {
						datas.clear();
					}
					datas.add(evt);
				}

				if (!ene.getKey().equals(SseEvent.DEFAULT_EVENT)) {
					sseBuilder.name(ene.getKey());
				}

				for (SseEvent evt : datas) {
					if (evt.id() != null) {
						sseBuilder.id(evt.id());
					}

					if (evt.retry() != null) {
						sseBuilder.reconnectTime(evt.retry());
					}

					if (evt.comment() != null) {
						sseBuilder.comment(evt.comment());
					}

					if (evt.dataObject() != null) {
						String convertedValue = convertObject(evt.dataObject());
						if (convertedValue != null) {
							sseBuilder.data(convertedValue);
						}
						else {
							sseBuilder.data(evt.data());
						}
					}
					else {
						sseBuilder.data(evt.data());
					}
				}
			}

			try {
				client.sseEmitter().send(sseBuilder);
				client.updateLastTransfer();
				return true;
			}
			catch (Exception e) {
				client.sseEmitter().completeWithError(e);
				this.failedClients.merge(clientId, 1, (v, vv) -> v + 1);
				return false;
			}

		}
		return true;
	}

	private String convertObject(Object dataObject) {
		if (this.dataObjectConverters != null) {
			for (DataObjectConverter converter : this.dataObjectConverters) {
				if (converter.supports(dataObject)) {
					return converter.convert(dataObject);
				}
			}
		}
		return null;
	}

	private void cleanUpClients() {
		if (!this.clients.isEmpty()) {
			long expirationTime = System.currentTimeMillis()
					- this.clientExpirationInSeconds * 1000L;
			Iterator<Entry<String, SseClient>> it = this.clients.entrySet().iterator();
			Set<String> staleClients = new HashSet<>();
			while (it.hasNext()) {
				Entry<String, SseClient> entry = it.next();
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

	public int getNoOfSendResponseTries() {
		return this.noOfSendResponseTries;
	}

	public void setNoOfSendResponseTries(int noOfSendResponseTries) {
		this.noOfSendResponseTries = noOfSendResponseTries;
	}

	public int getClientExpirationInSeconds() {
		return this.clientExpirationInSeconds;
	}

	public void setClientExpirationInSeconds(int clientExpirationInSeconds) {
		this.clientExpirationInSeconds = clientExpirationInSeconds;
	}

}
