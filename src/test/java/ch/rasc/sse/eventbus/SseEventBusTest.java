/**
 * Copyright 2016-2017 Ralph Schaer <ralphschaer@gmail.com>
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

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
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
		public Duration clientExpiration() {
			return Duration.ofSeconds(5);
		}

		@Override
		public int noOfSendResponseTries() {
			return 1;
		}
	}

	@Autowired
	private SseEventBus eventBus;

	@Before
	public void cleanup() {
		this.eventBus.unregisterClient("1");
		this.eventBus.unregisterClient("2");
		this.eventBus.unregisterClient("3");
	}

	@Test
	public void testClientRegistration() {
		SseEmitter emitter = this.eventBus.createSseEmitter("1");
		assertThat(emitter).isNotNull();
		assertThat(emitter.getTimeout()).isEqualTo(180_000L);
	}

	@Test
	public void testClientRegistrationWithTimeout() {
		SseEmitter emitter = this.eventBus.createSseEmitter("1", 90_000L);
		assertThat(emitter).isNotNull();
		assertThat(emitter.getTimeout()).isEqualTo(90_000L);
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
		assertThat(clients().get("1").sseEmitter()).isEqualTo(se1);
		assertThat(clients().get("2").sseEmitter()).isEqualTo(se2);
		assertThat(eventSubscribers()).isEmpty();

		sleep(1, TimeUnit.SECONDS);
		assertThat(clients()).containsOnlyKeys("1", "2");

		sleep(11, TimeUnit.SECONDS);
		assertThat(clients()).isEmpty();
	}

	@Test
	public void testClientRegisterAndSubscribe() {
		assertThat(clients()).isEmpty();

		SseEmitter se1 = this.eventBus.createSseEmitter("1", "one");
		SseEmitter se2 = this.eventBus.createSseEmitter("2", "two", "two2");
		SseEmitter se3 = this.eventBus.createSseEmitter("3", "one", "three");

		assertThat(clients()).containsOnlyKeys("1", "2", "3");
		assertThat(clients().get("1").sseEmitter()).isEqualTo(se1);
		assertThat(clients().get("2").sseEmitter()).isEqualTo(se2);
		assertThat(clients().get("3").sseEmitter()).isEqualTo(se3);

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
	}

	@Test
	public void testClientRegisterAndSubscribeTimeout() {
		this.eventBus.createSseEmitter("1", "one");
		this.eventBus.createSseEmitter("2", "two", "two2");
		this.eventBus.createSseEmitter("3", "one", "three");
		assertThat(eventSubscribers()).containsOnlyKeys("one", "two", "two2", "three");
		sleep(11, TimeUnit.SECONDS);
		assertThat(clients()).isEmpty();
		assertThat(eventSubscribers()).isEmpty();
	}

	private Map<String, Client> clients() {
		return (Map<String, Client>) ReflectionTestUtils.getField(this.eventBus,
				"clients");
	}

	private Map<String, Set<String>> eventSubscribers() {
		return (Map<String, Set<String>>) ReflectionTestUtils.getField(this.eventBus,
				"eventSubscribers");
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
