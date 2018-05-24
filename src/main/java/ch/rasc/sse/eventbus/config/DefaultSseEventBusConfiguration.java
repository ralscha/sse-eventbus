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
package ch.rasc.sse.eventbus.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.rasc.sse.eventbus.DataObjectConverter;
import ch.rasc.sse.eventbus.DefaultDataObjectConverter;
import ch.rasc.sse.eventbus.DefaultSubscriptionRegistry;
import ch.rasc.sse.eventbus.JacksonDataObjectConverter;
import ch.rasc.sse.eventbus.SseEventBus;
import ch.rasc.sse.eventbus.SubscriptionRegistry;

/**
 * To enable the SSE EventBus library create a @Configuration class that either
 * <ul>
 * <li>extends this class</li>
 * <li>or implement the interface {@link SseEventBusConfigurer} and add the
 * {@link EnableSseEventBus} annotation to any @Configuration class</li>
 * <li>or just add the {@link EnableSseEventBus} annotation to any @Configuration
 * class</li>
 */
@Configuration
public class DefaultSseEventBusConfiguration {

	@Autowired(required = false)
	protected SseEventBusConfigurer configurer;

	@Autowired(required = false)
	protected ObjectMapper objectMapper;

	@Autowired(required = false)
	protected List<DataObjectConverter> dataObjectConverters;

	@Autowired(required = false)
	protected SubscriptionRegistry subscriptionRegistry;

	@Bean
	public SseEventBus eventBus() {
		SseEventBusConfigurer config = this.configurer;
		if (config == null) {
			config = new SseEventBusConfigurer() {
				/* nothing_here */ };
		}

		SubscriptionRegistry registry = this.subscriptionRegistry;
		if (registry == null) {
			registry = new DefaultSubscriptionRegistry();
		}

		SseEventBus sseEventBus = new SseEventBus(config, registry);

		List<DataObjectConverter> converters = this.dataObjectConverters;
		if (converters == null) {
			converters = new ArrayList<>();
		}

		if (this.objectMapper != null) {
			converters.add(new JacksonDataObjectConverter(this.objectMapper));
		}
		else {
			converters.add(new DefaultDataObjectConverter());
		}

		sseEventBus.setDataObjectConverters(converters);

		return sseEventBus;
	}

}
