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
package ch.rasc.sse.eventbus.distributed;

import java.io.Serial;

import ch.rasc.sse.eventbus.SseEvent;

/**
 * Spring application event envelope that carries a {@link SseEvent} together with the
 * identifier of the node that published it.
 * <p>
 * Used by {@link SpringApplicationEventDistributedEventBus} to broadcast events across
 * all application context listeners in the same JVM and to identify envelopes that
 * originated on the local node (so they can be silently dropped to prevent loops).
 * <p>
 * Implements {@link java.io.Serializable} so that the envelope can be relayed across JVM
 * boundaries via Spring Cloud Stream, Spring Integration, or any other
 * serialization-based transport. The {@link SseEvent#data()} field must itself be
 * serializable at runtime.
 *
 * @param originNodeId the identifier of the node that published this envelope
 * @param event the SSE event being distributed
 */
public record RemoteSseEventEnvelope(String originNodeId, SseEvent event) implements java.io.Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

}
