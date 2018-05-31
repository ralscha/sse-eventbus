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

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class Client {

	private final String id;

	private SseEmitter sseEmitter;

	private long lastTransfer;

	private final boolean completeAfterMessage;

	Client(String id, SseEmitter sseEmitter, boolean completeAfterMessage) {
		this.id = id;
		this.sseEmitter = sseEmitter;
		this.lastTransfer = System.currentTimeMillis();
		this.completeAfterMessage = completeAfterMessage;
	}

	public String getId() {
		return this.id;
	}

	long lastTransfer() {
		return this.lastTransfer;
	}

	void updateLastTransfer() {
		this.lastTransfer = System.currentTimeMillis();
	}

	SseEmitter sseEmitter() {
		return this.sseEmitter;
	}

	void updateEmitter(SseEmitter emitter) {
		this.sseEmitter = emitter;
	}

	boolean isCompleteAfterMessage() {
		return this.completeAfterMessage;
	}

}
