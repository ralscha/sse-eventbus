/**
 * Copyright 2016-2016 Ralph Schaer <ralphschaer@gmail.com>
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ch.rasc.sse.eventbus.config.EnableSseEventBus;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;

@RunWith(SpringRunner.class)
@ContextConfiguration
@SuppressWarnings("unchecked")
@DirtiesContext
public class SseEventBusTest {

	@Configuration
	@EnableSseEventBus
	static class Config implements SseEventBusConfigurer {
		@Override
		public int clientExpirationInSeconds() {
			return 5;
		}

		@Override
		public int messageExpirationInSeconds() {
			return 5;
		}

		@Override
		public int noOfSendResponseTries() {
			return 1;
		}
	}

	@Autowired
	private SseEventBus eventBus;

	@Autowired
	private ApplicationEventPublisher publisher;

	@Test
	public void testClientRegistration() {
		SseEmitter emitter = this.eventBus.createSseEmitter("1");
		assertThat(emitter).isNotNull();
		assertThat(emitter.getTimeout()).isEqualTo(180_000L);
		this.eventBus.unregisterClient("1");
	}

	@Test
	public void testClientRegistrationWithTimeout() {
		SseEmitter emitter = this.eventBus.createSseEmitter("1", 90_000L);
		assertThat(emitter).isNotNull();
		assertThat(emitter.getTimeout()).isEqualTo(90_000L);
		this.eventBus.unregisterClient("1");
	}

	@Test
	public void testClientRegisterUnregister() {
		this.eventBus.createSseEmitter("1");
		this.eventBus.createSseEmitter("2");
		assertThat(clients()).containsOnlyKeys("1", "2");

		this.eventBus.unregisterClient("1");
		assertThat(clients()).containsOnlyKeys("2");

		this.eventBus.unregisterClient("2");
		assertThat(clients()).isEmpty();
	}

	@Test
	public void testClientRegistrationExpiration() {
		SseEmitter se1 = this.eventBus.createSseEmitter("1");
		SseEmitter se2 = this.eventBus.createSseEmitter("2", 10_000L);
		assertThat(clients()).containsOnlyKeys("1", "2");
		assertThat(clients().get("1").emitter()).isEqualTo(se1);
		assertThat(clients().get("2").emitter()).isEqualTo(se2);
		assertThat(eventSubscribers()).isEmpty();

		sleep(1, TimeUnit.SECONDS);
		assertThat(clients()).containsOnlyKeys("1", "2");

		sleep(5, TimeUnit.SECONDS);
		assertThat(clients()).isEmpty();
	}

	@Test
	public void testClientRegisterAndSubscribe() {
		assertThat(clients()).isEmpty();

		SseEmitter se1 = this.eventBus.createSseEmitter("1", "one");
		SseEmitter se2 = this.eventBus.createSseEmitter("2", "two", "two2");
		SseEmitter se3 = this.eventBus.createSseEmitter("3", "one", "three");

		assertThat(clients()).containsOnlyKeys("1", "2", "3");
		assertThat(clients().get("1").emitter()).isEqualTo(se1);
		assertThat(clients().get("2").emitter()).isEqualTo(se2);
		assertThat(clients().get("3").emitter()).isEqualTo(se3);

		assertThat(eventSubscribers()).containsOnlyKeys("one", "two", "two2", "three");
		assertThat(eventSubscribers().get("one")).containsExactly("1", "3");
		assertThat(eventSubscribers().get("two")).containsExactly("2");
		assertThat(eventSubscribers().get("two2")).containsExactly("2");
		assertThat(eventSubscribers().get("three")).containsExactly("3");

		this.eventBus.unsubscribe("1", "x");
		assertThat(eventSubscribers()).containsOnlyKeys("one", "two", "two2", "three");
		assertThat(eventSubscribers().get("one")).containsExactly("1", "3");
		assertThat(eventSubscribers().get("two")).containsExactly("2");
		assertThat(eventSubscribers().get("two2")).containsExactly("2");
		assertThat(eventSubscribers().get("three")).containsExactly("3");

		this.eventBus.unsubscribe("2", "two2");
		assertThat(eventSubscribers()).containsOnlyKeys("one", "two", "three");
		assertThat(eventSubscribers().get("one")).containsExactly("1", "3");
		assertThat(eventSubscribers().get("two")).containsExactly("2");
		assertThat(eventSubscribers().get("three")).containsExactly("3");

		this.eventBus.unsubscribe("2", "two");
		assertThat(eventSubscribers()).containsOnlyKeys("one", "three");
		assertThat(eventSubscribers().get("one")).containsExactly("1", "3");
		assertThat(eventSubscribers().get("three")).containsExactly("3");

		this.eventBus.unregisterClient("3");
		assertThat(eventSubscribers()).containsOnlyKeys("one");
		assertThat(eventSubscribers().get("one")).containsExactly("1");

		this.eventBus.unregisterClient("1");
		this.eventBus.unregisterClient("2");
		this.eventBus.unregisterClient("3");

	}

	@Test
	public void testClientRegisterAndSubscribeTimeout() {
		this.eventBus.createSseEmitter("1", "one");
		this.eventBus.createSseEmitter("2", "two", "two2");
		this.eventBus.createSseEmitter("3", "one", "three");
		assertThat(eventSubscribers()).containsOnlyKeys("one", "two", "two2", "three");
		sleep(6, TimeUnit.SECONDS);
		assertThat(clients()).isEmpty();
		assertThat(eventSubscribers()).isEmpty();
	}

	@Test
	public void testSendMessageToAll() {
		this.eventBus.createSseEmitter("1", "one");
		assertThat(eventSubscribers()).containsOnlyKeys("one");

		SseEvent ebe = SseEvent.all("one", "payload");
		this.eventBus.handleEvent(ebe);
		assertThat(pendingAllEvents()).containsOnlyKeys("one");
		assertThat(pendingAllEvents().get("one")).containsExactly(ebe);
		sleep(250, TimeUnit.MILLISECONDS);
		assertThat(pendingAllEvents()).isEmpty();
		assertThat(pendingClientEvents()).isEmpty();
	}

	@Test
	public void testSendMessageToOne() {
		this.eventBus.createSseEmitter("1", "one");
		assertThat(eventSubscribers()).containsOnlyKeys("one");

		SseEvent ebe = SseEvent.one("1", "one", "payload");
		this.eventBus.handleEvent(ebe);
		assertThat(pendingAllEvents()).isEmpty();
		assertThat(pendingClientEvents()).containsOnlyKeys("1");
		assertThat(pendingClientEvents().get("1")).containsExactly(ebe);

		sleep(250, TimeUnit.MILLISECONDS);
		assertThat(pendingAllEvents()).isEmpty();
		assertThat(pendingClientEvents()).isEmpty();
		this.eventBus.unregisterClient("1");
	}

	@Test
	public void testSendMessageToOneWithPublisher() {
		this.eventBus.createSseEmitter("1", "one");
		assertThat(eventSubscribers()).containsOnlyKeys("one");

		SseEvent ebe = SseEvent.one("1", "one", "payload");
		this.publisher.publishEvent(ebe);

		assertThat(pendingAllEvents()).isEmpty();
		assertThat(pendingClientEvents()).containsOnlyKeys("1");
		assertThat(pendingClientEvents().get("1")).containsExactly(ebe);

		sleep(250, TimeUnit.MILLISECONDS);
		assertThat(pendingAllEvents()).isEmpty();
		assertThat(pendingClientEvents()).isEmpty();
		this.eventBus.unregisterClient("1");
	}

	@Test
	public void testSendMessageToOneUnknown() {
		this.eventBus.createSseEmitter("1", "one");
		assertThat(eventSubscribers()).containsOnlyKeys("one");

		SseEvent ebe = SseEvent.one("2", "one", "payload");
		this.eventBus.handleEvent(ebe);
		assertThat(pendingAllEvents()).isEmpty();
		assertThat(pendingClientEvents()).containsOnlyKeys("2");
		assertThat(pendingClientEvents().get("2")).containsExactly(ebe);

		sleep(250, TimeUnit.MILLISECONDS);
		assertThat(pendingAllEvents()).isEmpty();
		assertThat(pendingClientEvents()).isEmpty();
		this.eventBus.unregisterClient("1");
		this.eventBus.unregisterClient("2");
	}

	@Test
	public void testSendMessageToGroup() {
		this.eventBus.createSseEmitter("1", "one");
		this.eventBus.createSseEmitter("2", "one");
		assertThat(eventSubscribers()).containsOnlyKeys("one");

		Set<String> clients = new HashSet<>();
		clients.add("1");
		clients.add("2");
		clients.add("3");
		SseEvent ebe = SseEvent.group(clients, "one", "payload");
		this.eventBus.handleEvent(ebe);
		assertThat(pendingAllEvents()).isEmpty();
		assertThat(pendingClientEvents()).containsOnlyKeys("1", "2", "3");
		assertThat(pendingClientEvents().get("1")).containsExactly(ebe);
		assertThat(pendingClientEvents().get("2")).containsExactly(ebe);
		assertThat(pendingClientEvents().get("3")).containsExactly(ebe);

		sleep(250, TimeUnit.MILLISECONDS);
		assertThat(pendingAllEvents()).isEmpty();
		assertThat(pendingClientEvents()).isEmpty();

		this.eventBus.unregisterClient("1");
		this.eventBus.unregisterClient("2");
	}

	private Map<String, SseClient> clients() {
		return (Map<String, SseClient>) ReflectionTestUtils.getField(this.eventBus,
				"clients");
	}

	private Map<String, Set<String>> eventSubscribers() {
		return (Map<String, Set<String>>) ReflectionTestUtils.getField(this.eventBus,
				"eventSubscribers");
	}

	private Map<String, List<SseEvent>> pendingAllEvents() {
		return (Map<String, List<SseEvent>>) ReflectionTestUtils.getField(this.eventBus,
				"pendingAllEvents");
	}

	private Map<String, List<SseEvent>> pendingClientEvents() {
		return (Map<String, List<SseEvent>>) ReflectionTestUtils.getField(this.eventBus,
				"pendingClientEvents");
	}

	private static void sleep(long value, TimeUnit timeUnit) {
		try {
			timeUnit.sleep(value);
		}
		catch (InterruptedException e) {
			// nothing here
		}
	}

}
