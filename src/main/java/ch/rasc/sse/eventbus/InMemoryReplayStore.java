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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jspecify.annotations.Nullable;

/**
 * Default in-memory implementation for replay storage.
 */
public class InMemoryReplayStore implements ReplayStore {

	private final ConcurrentMap<String, Deque<ReplayEvent>> replayEvents = new ConcurrentHashMap<>();

	private final int maxEventsPerClient;

	/**
	 * Creates a store retaining at most 10,000 events per client.
	 */
	public InMemoryReplayStore() {
		this(10_000);
	}

	/**
	 * Creates a store with a per-client capacity. Oldest events are evicted first.
	 * @param maxEventsPerClient maximum retained events per client; must be positive
	 */
	public InMemoryReplayStore(int maxEventsPerClient) {
		if (maxEventsPerClient <= 0) {
			throw new IllegalArgumentException("maxEventsPerClient must be positive");
		}
		this.maxEventsPerClient = maxEventsPerClient;
	}

	@Override
	public void store(ReplayEvent replayEvent) {
		this.replayEvents.compute(replayEvent.clientId(), (key, events) -> {
			Deque<ReplayEvent> retained = events != null ? events : new ArrayDeque<>();
			if (retained.size() == this.maxEventsPerClient) {
				retained.removeFirst();
			}
			retained.addLast(replayEvent);
			return retained;
		});
	}

	/**
	 * Returns the events for {@code clientId} that were published after
	 * {@code lastEventId}.
	 * <p>
	 * If {@code lastEventId} is {@code null} or empty all retained events are returned.
	 * If {@code lastEventId} is non-empty but is not present in the store (e.g. because
	 * it was purged by the retention job), <em>all</em> retained events are returned as a
	 * best-effort fallback so the client receives as much history as possible after a
	 * long absence. Callers should be aware that this may result in some duplicate events
	 * being delivered if the client re-connects after the event has already been received
	 * but before its ID was purged from the store.
	 */
	@Override
	public List<ReplayEvent> getEventsSince(String clientId, @Nullable String lastEventId) {
		List<ReplayEvent> result = new ArrayList<>();
		// All deque access uses compute, so purge/clear/store cannot detach or mutate
		// a history while it is being read.
		this.replayEvents.computeIfPresent(clientId, (key, events) -> {
			result.addAll(events);
			return events;
		});
		if (lastEventId != null && !lastEventId.isEmpty()) {
			for (int index = result.size() - 1; index >= 0; index--) {
				if (lastEventId.equals(result.get(index).eventId())) {
					return List.copyOf(result.subList(index + 1, result.size()));
				}
			}
		}

		return List.copyOf(result);
	}

	@Override
	public void clearClient(String clientId) {
		this.replayEvents.remove(clientId);
	}

	@Override
	public void purgeExpired(long expirationTimestamp) {
		this.replayEvents.keySet().forEach(clientId -> this.replayEvents.computeIfPresent(clientId, (key, events) -> {
			events.removeIf(event -> event.storedAt() < expirationTimestamp);
			return events.isEmpty() ? null : events;
		}));
	}

}
