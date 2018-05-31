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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultSubscriptionRegistry implements SubscriptionRegistry {

	private final ConcurrentMap<String, Set<String>> eventSubscribers;

	public DefaultSubscriptionRegistry() {
		this.eventSubscribers = new ConcurrentHashMap<>();
	}

	protected ConcurrentMap<String, Set<String>> getEventSubscribers() {
		return this.eventSubscribers;
	}

	/*
	 * @see ch.rasc.sse.eventbus.SubscriptionRegistry#subscribe(java.lang.String,
	 * java.lang.String)
	 */
	@Override
	public void subscribe(String clientId, String event) {
		this.eventSubscribers.computeIfAbsent(event, k -> new HashSet<>()).add(clientId);
	}

	/*
	 * @see ch.rasc.sse.eventbus.SubscriptionRegistry#unsubscribe(java.lang.String,
	 * java.lang.String)
	 */
	@Override
	public void unsubscribe(String clientId, String event) {
		this.eventSubscribers.computeIfPresent(event,
				(k, set) -> set.remove(clientId) && set.isEmpty() ? null : set);
	}

	/*
	 * @see ch.rasc.sse.eventbus.SubscriptionRegistry#isClientSubscribedToEvent(java.lang.
	 * String, java.lang.String)
	 */
	@Override
	public boolean isClientSubscribedToEvent(String clientId, String eventName) {
		Set<String> subscribedClients = this.eventSubscribers.get(eventName);
		if (subscribedClients != null) {
			return subscribedClients.contains(clientId);
		}
		return false;
	}

	/*
	 * @see ch.rasc.sse.eventbus.SubscriptionRegistry#getAllEvents()
	 */
	@Override
	public Set<String> getAllEvents() {
		return Collections.unmodifiableSet(this.eventSubscribers.keySet());
	}

	/*
	 * @see ch.rasc.sse.eventbus.SubscriptionRegistry#getAllSubscriptions()
	 */
	@Override
	public Map<String, Set<String>> getAllSubscriptions() {
		Map<String, Set<String>> result = new HashMap<>();
		this.eventSubscribers.forEach((k, v) -> {
			result.put(k, Collections.unmodifiableSet(v));
		});
		return Collections.unmodifiableMap(result);
	}

	/*
	 * @see ch.rasc.sse.eventbus.SubscriptionRegistry#getSubscribers(java.lang.String)
	 */
	@Override
	public Set<String> getSubscribers(String event) {
		Set<String> clientIds = this.eventSubscribers.get(event);
		if (clientIds != null) {
			return Collections.unmodifiableSet(clientIds);
		}
		return Collections.emptySet();
	}

	/*
	 * @see ch.rasc.sse.eventbus.SubscriptionRegistry#countSubscribers(java.lang.String)
	 */
	@Override
	public int countSubscribers(String event) {
		Set<String> clientIds = this.eventSubscribers.get(event);
		if (clientIds != null) {
			return clientIds.size();
		}
		return 0;
	}

	/*
	 * @see ch.rasc.sse.eventbus.SubscriptionRegistry#hasSubscribers(java.lang.String)
	 */
	@Override
	public boolean hasSubscribers(String event) {
		return countSubscribers(event) != 0;
	}

}
