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

import org.jspecify.annotations.Nullable;

/**
 * Merges two consecutive events of the same client into a single event before it is
 * written to the connection.
 * <p>
 * This is useful for slow clients that receive a high-frequency stream of small events,
 * for example LLM token streaming. Coalescing reduces the number of writes and network
 * frames and keeps the per-client send buffer from filling up, so fewer events are
 * dropped or trigger a disconnect.
 * <p>
 * Coalescing is opt-in: return {@code null} from {@link #coalesce(ClientEvent, ClientEvent)}
 * for pairs that must be sent separately. The default implementation is
 * {@link DefaultEventCoalescer}, which merges plain data events by joining their data
 * with a line break.
 */
@FunctionalInterface
public interface EventCoalescer {

	/**
	 * Merges two consecutive buffered events of the same client.
	 * @param first the first (older) event
	 * @param second the second (newer) event
	 * @return the merged event, or {@code null} when the two events cannot be merged and
	 * must be written separately
	 */
	@Nullable
	ClientEvent coalesce(ClientEvent first, ClientEvent second);

}
