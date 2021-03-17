/**
 * Copyright 2016-2021 the original author or authors.
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
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class TestListener implements SseEventBusListener {

	private List<ClientEvent> afterEventQueuedFirst = new ArrayList<>();
	private List<ClientEvent> afterEventQueued = new ArrayList<>();
	private List<ClientEvent> afterEventSentOk = new ArrayList<>();
	private List<ClientEvent> afterEventSentFail = new ArrayList<>();
	private List<String> afterClientsUnregistered = new ArrayList<>();

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
		this.afterEventQueuedFirst = new ArrayList<>();
		this.afterEventQueued = new ArrayList<>();
		this.afterEventSentOk = new ArrayList<>();
		this.afterEventSentFail = new ArrayList<>();
		this.afterClientsUnregistered = new ArrayList<>();
	}
}
