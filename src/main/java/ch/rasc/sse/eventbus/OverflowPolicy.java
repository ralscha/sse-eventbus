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

/**
 * Policy applied when a client's send buffer is full.
 * <p>
 * Slow clients (poor network, slow consumers) cannot keep up with a high frequency
 * event stream, for example LLM token streaming. Once the per-client bounded send
 * buffer reaches its capacity this policy decides what happens next.
 */
public enum OverflowPolicy {

	/**
	 * Drop the new event and keep the connection. The client keeps receiving
	 * subsequent events but may miss some events while it is slow.
	 */
	DROP,

	/**
	 * Disconnect the slow client. The connection is closed and the client is
	 * unregistered; the application decides whether and when the client reconnects.
	 */
	DISCONNECT

}
