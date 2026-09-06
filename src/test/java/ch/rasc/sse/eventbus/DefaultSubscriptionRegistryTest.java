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

import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSubscriptionRegistryTest {

	@Test
	void eventQueriesReturnStableSnapshots() {
		DefaultSubscriptionRegistry registry = new DefaultSubscriptionRegistry();
		registry.subscribe("client", "orders");
		Set<String> events = registry.getAllEvents();
		registry.unsubscribeAll("client");
		assertThat(events).containsExactly("orders");
		assertThat(registry.getAllEvents()).isEmpty();
	}

	@Test
	void concurrentChangesKeepBothSubscriptionIndexesConsistent() throws Exception {
		DefaultSubscriptionRegistry registry = new DefaultSubscriptionRegistry();
		var executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			Future<?> subscriber = executor.submit(() -> {
				for (int index = 0; index < 2_000; index++) {
					barrier.await(5, TimeUnit.SECONDS);
					registry.subscribe("client", "orders");
					barrier.await(5, TimeUnit.SECONDS);
				}
				return null;
			});
			Future<?> unsubscriber = executor.submit(() -> {
				for (int index = 0; index < 2_000; index++) {
					barrier.await(5, TimeUnit.SECONDS);
					registry.unsubscribeAll("client");
					barrier.await(5, TimeUnit.SECONDS);
					registry.unsubscribeAll("client");
					assertThat(registry.getAllSubscriptions()).isEmpty();
				}
				return null;
			});
			subscriber.get(20, TimeUnit.SECONDS);
			unsubscriber.get(20, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}
	}

}
