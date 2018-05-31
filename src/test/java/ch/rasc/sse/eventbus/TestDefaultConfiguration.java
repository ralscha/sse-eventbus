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

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import ch.rasc.sse.eventbus.config.EnableSseEventBus;
import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;

@SpringBootApplication
@EnableSseEventBus
public class TestDefaultConfiguration implements SseEventBusConfigurer {

	@Override
	public Duration clientExpiration() {
		return Duration.ofSeconds(10);
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
				TestObject2 to = (TestObject2) event.data();
				return to.getId() + "," + to.getCustomer();
			}
		};
	}

}
