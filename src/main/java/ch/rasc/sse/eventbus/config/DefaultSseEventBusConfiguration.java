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
package ch.rasc.sse.eventbus.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ch.rasc.sse.eventbus.SseEventBus;

/**
 * To enable the SSE EventBus library create a @Configuration class that either
 * <ul>
 * <li>extends this class</li>
 * <li>or implements the interface {@link SseEventBusConfigurer} and adds the
 * {@link EnableSseEventBus} annotation to any @Configuration class</li>
 * <li>or just adds the {@link EnableSseEventBus} annotation to any @Configuration
 * class</li>
 */
@Configuration
public class DefaultSseEventBusConfiguration {

	@Autowired(required = false)
	private SseEventBusConfigurer configurer;

	@Bean
	public SseEventBus eventBus() {
		SseEventBusConfigurer config = this.configurer;
		if (config == null) {
			config = new SseEventBusConfigurer() {
				/* nothing_here */ };
		}

		ScheduledExecutorService taskScheduler;
		if (config.taskScheduler() != null) {
			taskScheduler = config.taskScheduler();
		}
		else {
			taskScheduler = Executors.newSingleThreadScheduledExecutor();
		}

		return new SseEventBus(taskScheduler, config.clientExpirationInSeconds(),
				config.messageExpirationInSeconds(),
				config.schedulerDelayInMilliseconds(), config.noOfSendResponseTries());
	}

}
