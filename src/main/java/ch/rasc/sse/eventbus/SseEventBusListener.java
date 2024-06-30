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

import java.util.Set;

/**
 * Listen for internal events. All these methods are called synchronously.
 */
public interface SseEventBusListener {

	/**
	 * Called each time a message has been added to the internal sending queue
	 * @param clientEvent Data object comprises the client, event and value
	 * @param firstAttempt <code>true</code> if the message is queued the first time or
	 * <code>false</code> if this is a new attempt after a previous failed delivery
	 */
	default void afterEventQueued(ClientEvent clientEvent, boolean firstAttempt) {
		// no default implementation
	}

	/**
	 * Called each time a message has been sent either successfully or unsuccessfully
	 * @param clientEvent Data object comprises the client, event and value
	 * @param exception <code>null</code> message has been sent successfully, otherwise
	 * message delivery failed with this error
	 */
	default void afterEventSent(ClientEvent clientEvent, Exception exception) {
		// no default implementation
	}

	/**
	 * Called each time the library has unregistered one or more stale clients.
	 * @param clientIds collection of client identifications that have been removed from
	 * the registry
	 */
	default void afterClientsUnregistered(Set<String> clientIds) {
		// no default implementation
	}

}
