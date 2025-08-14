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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default implementation of {@link SubscriptionRegistry}. This implementation is
 * thread-safe.
 */
public class DefaultSubscriptionRegistry implements SubscriptionRegistry {

	private final ConcurrentMap<String, Set<String>> eventSubscribers;

	/**
	 * Creates a new instance of the DefaultSubscriptionRegistry.
	 */
	public DefaultSubscriptionRegistry() {
		this.eventSubscribers = new ConcurrentHashMap<>();
	}

	protected ConcurrentMap<String, Set<String>> getEventSubscribers() {
		return this.eventSubscribers;
	}

	@Override
	public void subscribe(String clientId, String event) {
		this.eventSubscribers.computeIfAbsent(event, k -> new HashSet<>()).add(clientId);
	}

	@Override
	public void unsubscribe(String clientId, String event) {
		this.eventSubscribers.computeIfPresent(event,
				(k, set) -> set.remove(clientId) && set.isEmpty() ? null : set);
	}

	@Override
	public boolean isClientSubscribedToEvent(String clientId, String eventName) {
		Set<String> subscribedClients = this.eventSubscribers.get(eventName);
		if (subscribedClients != null) {
			return subscribedClients.contains(clientId);
		}
		return false;
	}

	@Override
	public Set<String> getAllEvents() {
		return Collections.unmodifiableSet(this.eventSubscribers.keySet());
	}

	@Override
	public Map<String, Set<String>> getAllSubscriptions() {
		Map<String, Set<String>> result = new HashMap<>();
		this.eventSubscribers.forEach((k, v) -> {
			result.put(k, Collections.unmodifiableSet(v));
		});
		return Collections.unmodifiableMap(result);
	}

	@Override
	public Set<String> getSubscribers(String event) {
		Set<String> clientIds = this.eventSubscribers.get(event);
		if (clientIds != null) {
			return Collections.unmodifiableSet(clientIds);
		}
		return Collections.emptySet();
	}

	@Override
	public int countSubscribers(String event) {
		Set<String> clientIds = this.eventSubscribers.get(event);
		if (clientIds != null) {
			return clientIds.size();
		}
		return 0;
	}

	@Override
	public boolean hasSubscribers(String event) {
		return countSubscribers(event) != 0;
	}

}
