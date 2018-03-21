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

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

import ch.rasc.sse.eventbus.ClientEvent;

/**
 * Defines methods for configuring the SSE Event Bus library.
 *
 * <p>
 * Used together with {@link EnableSseEventBus}
 */
public interface SseEventBusConfigurer {

	/**
	 * Specifies the delay between runs of the internal error queue job. <br>
	 * This job tries to re-submits failed sent events.
	 * <p>
	 * Default: 500 milliseconds
	 */
	default Duration schedulerDelay() {
		return Duration.ofMillis(500);
	}

	/**
	 * Specifies the delay between runs of the internal job that checks for expired
	 * clients.<br>
	 * <p>
	 * Default: {@link #clientExpiration()} (1 day)
	 */
	default Duration clientExpirationJobDelay() {
		return clientExpiration();
	}

	/**
	 * Duration after the last successful data connection, a client is removed from the
	 * internal registry.
	 * <p>
	 * Default: 1 day
	 */
	default Duration clientExpiration() {
		return Duration.ofDays(1);
	}

	/**
	 * Number of tries to send an event. When the event cannot be send that many times it
	 * will be removed from the internal registry.
	 */
	default int noOfSendResponseTries() {
		return 40;
	}

	/**
	 * An executor that schedules and runs the internal jobs
	 * <p>
	 * By default this is an instance created with
	 * {@link Executors#newScheduledThreadPool(2)}
	 */
	default ScheduledExecutorService taskScheduler() {
		return Executors.newScheduledThreadPool(2);
	}

	default BlockingQueue<ClientEvent> errorQueue() {
		return new LinkedBlockingQueue<>();
	}

	default BlockingQueue<ClientEvent> sendQueue() {
		return new LinkedBlockingQueue<>();
	}

}
