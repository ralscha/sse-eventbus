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

import java.time.Instant;

/**
 * Describes a slow client event. Instances are passed to
 * {@link SlowClientListener#onSlowClient} when a client's send buffer overflows.
 *
 * @param clientId the client identifier
 * @param policy the overflow policy that was applied
 * @param queueSize the number of events queued in the client's send buffer when the
 * overflow happened
 * @param capacity the configured capacity of the client's send buffer
 * @param detail a human readable description
 * @param timestamp the time the event was created
 */
public record SlowClientEvent(String clientId, OverflowPolicy policy, int queueSize, int capacity, String detail,
		Instant timestamp) {

	public static SlowClientEvent of(String clientId, OverflowPolicy policy, int queueSize, int capacity,
			String detail) {
		return new SlowClientEvent(clientId, policy, queueSize, capacity, detail, Instant.now());
	}

}
