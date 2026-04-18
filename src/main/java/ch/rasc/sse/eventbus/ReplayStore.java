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

import java.util.List;

/**
 * Stores previously published events so reconnecting clients can resume from a known
 * event id.
 */
public interface ReplayStore {

	/**
	 * Stores a replayable event for later delivery to a reconnecting client.
	 * <p>
	 * The caller guarantees that {@link ReplayEvent#eventId()} returns a non-empty string;
	 * implementations may rely on this invariant.
	 * @param replayEvent the event to store
	 */
	void store(ReplayEvent replayEvent);

	/**
	 * Returns the events for {@code clientId} that were published after
	 * {@code lastEventId}.
	 * <p>
	 * <strong>Fallback behaviour:</strong> if {@code lastEventId} is {@code null} or
	 * empty, <em>all</em> retained events are returned. If {@code lastEventId} is
	 * non-empty but cannot be found in the store (e.g. because it has been purged by
	 * retention), implementations should return all retained events rather than an empty
	 * list, so that the client receives as much history as possible after a long absence.
	 * @param clientId the client whose history is requested
	 * @param lastEventId the last event id acknowledged by the client, or {@code null} /
	 * empty to request all retained events
	 * @return an ordered list of events to replay; never {@code null}
	 */
	List<ReplayEvent> getEventsSince(String clientId, String lastEventId);

	/**
	 * Removes all retained events for {@code clientId}.
	 * @param clientId the client whose history should be cleared
	 */
	void clearClient(String clientId);

	/**
	 * Removes all events whose {@link ReplayEvent#storedAt()} timestamp is strictly less
	 * than {@code expirationTimestamp}.
	 * @param expirationTimestamp cutoff epoch-millis; events older than this are removed
	 */
	void purgeExpired(long expirationTimestamp);

}