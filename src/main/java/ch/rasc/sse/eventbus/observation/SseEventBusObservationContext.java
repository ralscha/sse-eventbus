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
package ch.rasc.sse.eventbus.observation;

import io.micrometer.observation.Observation;
import org.jspecify.annotations.Nullable;

/**
 * Observation context emitted by {@code SseEventBus} operations.
 */
public class SseEventBusObservationContext extends Observation.Context {

	/**
	 * The observed SSE event bus operation.
	 */
	public enum Operation {

		REGISTER_CLIENT("register"),

		UNREGISTER_CLIENT("unregister"),

		HANDLE_EVENT("handle"),

		SEND_EVENT("send"),

		REPLAY_EVENTS("replay");

		private final String value;

		Operation(String value) {
			this.value = value;
		}

		public String value() {
			return this.value;
		}

	}

	private final Operation operation;

	private @Nullable String clientId;

	private @Nullable String eventName;

	private boolean directEvent;

	private boolean replay;

	private boolean completeAfterMessage;

	private boolean lastEventIdPresent;

	private int deliveryCount;

	private int attempt = 1;

	private @Nullable String outcome;

	public SseEventBusObservationContext(Operation operation) {
		this.operation = operation;
	}

	public Operation getOperation() {
		return this.operation;
	}

	public @Nullable String getClientId() {
		return this.clientId;
	}

	public void setClientId(@Nullable String clientId) {
		this.clientId = clientId;
	}

	public @Nullable String getEventName() {
		return this.eventName;
	}

	public void setEventName(@Nullable String eventName) {
		this.eventName = eventName;
	}

	public boolean isDirectEvent() {
		return this.directEvent;
	}

	public void setDirectEvent(boolean directEvent) {
		this.directEvent = directEvent;
	}

	public boolean isReplay() {
		return this.replay;
	}

	public void setReplay(boolean replay) {
		this.replay = replay;
	}

	public boolean isCompleteAfterMessage() {
		return this.completeAfterMessage;
	}

	public void setCompleteAfterMessage(boolean completeAfterMessage) {
		this.completeAfterMessage = completeAfterMessage;
	}

	public boolean isLastEventIdPresent() {
		return this.lastEventIdPresent;
	}

	public void setLastEventIdPresent(boolean lastEventIdPresent) {
		this.lastEventIdPresent = lastEventIdPresent;
	}

	public int getDeliveryCount() {
		return this.deliveryCount;
	}

	public void setDeliveryCount(int deliveryCount) {
		this.deliveryCount = deliveryCount;
	}

	public int getAttempt() {
		return this.attempt;
	}

	public void setAttempt(int attempt) {
		this.attempt = attempt;
	}

	public String getOutcome() {
		return this.outcome != null ? this.outcome : "unknown";
	}

	public void setOutcome(@Nullable String outcome) {
		this.outcome = outcome;
	}

}