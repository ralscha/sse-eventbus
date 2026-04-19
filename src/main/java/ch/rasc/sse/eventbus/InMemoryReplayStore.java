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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

import org.jspecify.annotations.Nullable;

/**
 * Default in-memory implementation for replay storage.
 */
public class InMemoryReplayStore implements ReplayStore {

	private final ConcurrentMap<String, ConcurrentLinkedDeque<ReplayEvent>> replayEvents = new ConcurrentHashMap<>();

	@Override
	public void store(ReplayEvent replayEvent) {
		this.replayEvents.computeIfAbsent(replayEvent.clientId(), key -> new ConcurrentLinkedDeque<>())
			.addLast(replayEvent);
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
		@Nullable ConcurrentLinkedDeque<ReplayEvent> events = this.replayEvents.get(clientId);
		if (events == null || events.isEmpty()) {
			return List.of();
		}

		List<ReplayEvent> result = new ArrayList<>();
		@Nullable String requestedLastEventId = lastEventId;
		boolean replayAll = requestedLastEventId == null || requestedLastEventId.isEmpty();
		boolean seenLastEvent = false;

		for (ReplayEvent replayEvent : events) {
			if (replayAll) {
				result.add(replayEvent);
				continue;
			}
			if (seenLastEvent) {
				result.add(replayEvent);
				continue;
			}
			if (requestedLastEventId != null && requestedLastEventId.equals(replayEvent.eventId())) {
				seenLastEvent = true;
			}
		}

		if (!replayAll && !seenLastEvent) {
			result.clear();
			result.addAll(events);
		}

		return List.copyOf(result);
	}

	@Override
	public void clearClient(String clientId) {
		this.replayEvents.remove(clientId);
	}

	@Override
	public void purgeExpired(long expirationTimestamp) {
		this.replayEvents.forEach((clientId, events) -> {
			while (true) {
				@Nullable ReplayEvent first = events.peekFirst();
				if (first == null || first.storedAt() >= expirationTimestamp) {
					break;
				}
				events.pollFirst();
			}
			if (events.isEmpty()) {
				this.replayEvents.remove(clientId, events);
			}
		});
	}

}