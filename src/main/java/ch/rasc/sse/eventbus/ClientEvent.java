/**
 * Copyright 2016-2018 the original author or authors.
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

import java.time.Duration;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

public class ClientEvent {

	private final Client client;

	private final SseEvent event;

	private final String convertedValue;

	private int errorCounter;

	public ClientEvent(Client client, SseEvent event, String convertedValue) {
		this.client = client;
		this.event = event;
		this.convertedValue = convertedValue;
		this.errorCounter = 0;
	}

	public Client getClient() {
		return this.client;
	}

	public SseEvent getSseEvent() {
		return this.event;
	}

	public SseEventBuilder createSseEventBuilder() {

		SseEventBuilder sseBuilder = SseEmitter.event();

		if (!this.event.event().equals(SseEvent.DEFAULT_EVENT)) {
			sseBuilder.name(this.event.event());
		}

		this.event.id().ifPresent(sseBuilder::id);
		this.event.retry().map(Duration::toMillis).ifPresent(sseBuilder::reconnectTime);
		this.event.comment().ifPresent(sseBuilder::comment);

		if (this.convertedValue != null) {
			for (String line : this.convertedValue.split("\n")) {
				sseBuilder.data(line);
			}
		}
		else if (this.event.data() instanceof String) {
			for (String line : ((String) this.event.data()).split("\n")) {
				sseBuilder.data(line);
			}
		}
		else {
			sseBuilder.data(this.event.data());
		}

		return sseBuilder;

	}

	public void incErrorCounter() {
		this.errorCounter++;
	}

	public int getErrorCounter() {
		return this.errorCounter;
	}

}
