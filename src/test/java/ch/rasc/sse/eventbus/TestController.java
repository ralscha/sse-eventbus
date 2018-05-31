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

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
public class TestController {

	private final SseEventBus eventBus;

	public TestController(SseEventBus eventBus) {
		this.eventBus = eventBus;
	}

	@GetMapping("/register/{id}")
	public SseEmitter eventbus(@PathVariable("id") String id) {
		return this.eventBus.createSseEmitter(id, 3_000L);
	}

	@GetMapping("/register/{id}/{event}")
	public SseEmitter eventbus(@PathVariable("id") String id,
			@PathVariable("event") String event) {
		return this.eventBus.createSseEmitter(id, 3_000L, event.split(","));
	}

	@GetMapping("/registerOnly/{id}/{event}")
	public SseEmitter eventbusOnly(@PathVariable("id") String id,
			@PathVariable("event") String event) {
		return this.eventBus.createSseEmitter(id, 3_000L, true, event.split(","));
	}

	@ResponseBody
	@GetMapping("/unregister/{id}")
	public void unregister(@PathVariable("id") String id) {
		this.eventBus.unregisterClient(id);
	}

	@ResponseBody
	@GetMapping("/subscribe/{id}/{event}")
	public void subscribe(@PathVariable("id") String id,
			@PathVariable("event") String event) {
		String[] splittedEvents = event.split(",");
		for (String e : splittedEvents) {
			this.eventBus.subscribe(id, e);
		}
	}

	@ResponseBody
	@GetMapping("/unsubscribe/{id}/{event}")
	public void unsubscribe(@PathVariable("id") String id,
			@PathVariable("event") String event) {
		String[] splittedEvents = event.split(",");
		for (String e : splittedEvents) {
			this.eventBus.unsubscribe(id, e);
		}
	}

}
