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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class TestListener implements SseEventBusListener {

	private final List<ClientEvent> afterEventQueuedFirst = Collections.synchronizedList(new ArrayList<>());

	private final List<ClientEvent> afterEventQueued = Collections.synchronizedList(new ArrayList<>());

	private final List<ClientEvent> afterEventSentOk = Collections.synchronizedList(new ArrayList<>());

	private final List<ClientEvent> afterEventSentFail = Collections.synchronizedList(new ArrayList<>());

	private final List<String> afterClientsUnregistered = Collections.synchronizedList(new ArrayList<>());

	@Override
	public void afterEventQueued(ClientEvent clientEvent, boolean firstAttempt) {
		if (firstAttempt) {
			this.afterEventQueuedFirst.add(clientEvent);
		}
		else {
			this.afterEventQueued.add(clientEvent);
		}
	}

	@Override
	public void afterEventSent(ClientEvent clientEvent, Exception exception) {
		if (exception == null) {
			this.afterEventSentOk.add(clientEvent);
		}
		else {
			this.afterEventSentFail.add(clientEvent);
		}
	}

	@Override
	public void afterClientsUnregistered(Set<String> clientIds) {
		this.afterClientsUnregistered.addAll(clientIds);
	}

	public List<ClientEvent> getAfterEventQueuedFirst() {
		return this.afterEventQueuedFirst;
	}

	public List<ClientEvent> getAfterEventQueued() {
		return this.afterEventQueued;
	}

	public List<ClientEvent> getAfterEventSentOk() {
		return this.afterEventSentOk;
	}

	public List<ClientEvent> getAfterEventSentFail() {
		return this.afterEventSentFail;
	}

	public List<String> getAfterClientsUnregistered() {
		return this.afterClientsUnregistered;
	}

	public void reset() {
		this.afterEventQueuedFirst.clear();
		this.afterEventQueued.clear();
		this.afterEventSentOk.clear();
		this.afterEventSentFail.clear();
		this.afterClientsUnregistered.clear();
	}

}
