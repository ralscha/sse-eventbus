/**
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

import ch.rasc.sse.eventbus.config.EnableSseEventBus;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static ch.rasc.sse.eventbus.TestUtils.sleep;
import static org.assertj.core.api.Assertions.assertThat;

@ContextConfiguration
@DirtiesContext
@SpringJUnitConfig
public class SseEventBusSchedulerTest {

	private static final ConcurrentMap<String, Client> CLIENTS_MAP = new ConcurrentHashMap<>();

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

		@Override
		public ConcurrentMap<String, Client> clients() {
			return CLIENTS_MAP;
		}

		@Override
		public ScheduledExecutorService taskScheduler() {
			return null;
		}

	}

	@Autowired
	private SseEventBus eventBus;

	@BeforeEach
	public void cleanup() {
		this.eventBus.unregisterClient("1");
		this.eventBus.unregisterClient("2");
		this.eventBus.unregisterClient("3");
	}

	@Test
	public void testClientRegistrationShouldNotExpireIfSchedulerIsNull() {
		SseEmitter se1 = this.eventBus.createSseEmitter("1");
		SseEmitter se2 = this.eventBus.createSseEmitter("2", 10_000L);
		assertThat(this.eventBus.getAllClientIds()).containsOnly("1", "2");
		assertThat(CLIENTS_MAP.get("1").sseEmitter()).isEqualTo(se1);
		assertThat(CLIENTS_MAP.get("2").sseEmitter()).isEqualTo(se2);
		assertThat(this.eventBus.getAllEvents()).isEmpty();
		assertThat(this.eventBus.getAllSubscriptions()).isEmpty();

		sleep(1, TimeUnit.SECONDS);
		assertThat(this.eventBus.getAllClientIds()).containsOnly("1", "2");
		assertThat(CLIENTS_MAP.get("1").sseEmitter()).isEqualTo(se1);
		assertThat(CLIENTS_MAP.get("2").sseEmitter()).isEqualTo(se2);
		assertThat(this.eventBus.getAllEvents()).isEmpty();
		assertThat(this.eventBus.getAllSubscriptions()).isEmpty();

		sleep(11, TimeUnit.SECONDS);
		assertThat(this.eventBus.getAllClientIds()).containsOnly("1", "2");
		assertThat(CLIENTS_MAP.get("1").sseEmitter()).isEqualTo(se1);
		assertThat(CLIENTS_MAP.get("2").sseEmitter()).isEqualTo(se2);
		assertThat(this.eventBus.getAllEvents()).isEmpty();
		assertThat(this.eventBus.getAllSubscriptions()).isEmpty();
	}

}
