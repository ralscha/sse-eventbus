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

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import ch.rasc.sse.eventbus.config.EnableSseEventBus;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;

@SpringBootApplication
@EnableSseEventBus
public class TestDefaultConfiguration implements SseEventBusConfigurer {

	@Autowired
	private TestListener testListener;

	@Override
	public Duration clientExpiration() {
		return Duration.ofSeconds(5);
	}

	@Override
	public Duration clientExpirationJobDelay() {
		return Duration.ofSeconds(1);
	}

	@Override
	public SseEventBusListener listener() {
		return this.testListener;
	}

	@Bean
	public ReplayStore replayStoreBean() {
		return new InMemoryReplayStore();
	}

	@Override
	public ReplayStore replayStore() {
		return replayStoreBean();
	}

	@Override
	public Duration replayRetention() {
		return Duration.ofMinutes(1);
	}

	@Bean
	public DataObjectConverter testObject2Converter() {
		return new DataObjectConverter() {

			@Override
			public boolean supports(SseEvent event) {
				return event.data() instanceof TestObject2;
			}

			@Override
			public String convert(SseEvent event) {
				@Nullable Object data = event.data();
				if (!(data instanceof TestObject2 to)) {
					throw new IllegalStateException("Expected TestObject2 data");
				}
				return to.getId() + "," + to.getCustomer();
			}
		};
	}

}
