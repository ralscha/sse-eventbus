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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InMemoryReplayStoreTest {

	@Test
	void resumesAfterLastMatchingIdAndFallsBackForMissingId() {
		InMemoryReplayStore store = new InMemoryReplayStore();
		store.store(event("1", 1));
		store.store(event("2", 2));
		store.store(event("1", 3));
		store.store(event("3", 4));
		assertThat(store.getEventsSince("client", "1")).extracting(ReplayEvent::eventId).containsExactly("3");
		assertThat(store.getEventsSince("client", "missing")).hasSize(4);
		assertThat(store.getEventsSince("client", null)).hasSize(4);
		assertThat(store.getEventsSince("client", "")).hasSize(4);
		assertThat(store.getEventsSince("other", null)).isEmpty();
	}

	@Test
	void purgesExpiredEventsEvenWhenTimestampsAreOutOfOrder() {
		InMemoryReplayStore store = new InMemoryReplayStore();
		store.store(event("newer", 30));
		store.store(event("older", 10));
		store.store(event("boundary", 20));
		store.purgeExpired(20);
		assertThat(store.getEventsSince("client", null)).extracting(ReplayEvent::eventId)
			.containsExactly("newer", "boundary");
	}

	@Test
	void boundsHistoryPerClientAndReturnsImmutableSnapshots() {
		InMemoryReplayStore store = new InMemoryReplayStore(2);
		store.store(event("1", 1));
		List<ReplayEvent> snapshot = store.getEventsSince("client", null);
		store.store(event("2", 2));
		store.store(event("3", 3));
		assertThat(snapshot).extracting(ReplayEvent::eventId).containsExactly("1");
		assertThat(store.getEventsSince("client", "1")).extracting(ReplayEvent::eventId).containsExactly("2", "3");
		store.clearClient("client");
		assertThat(store.getEventsSince("client", null)).isEmpty();
		assertThatIllegalArgumentException().isThrownBy(() -> new InMemoryReplayStore(0));
		assertThatIllegalArgumentException().isThrownBy(() -> new InMemoryReplayStore(-1));
	}

	@Test
	void concurrentCleanupDoesNotLoseUnexpiredAppends() throws Exception {
		InMemoryReplayStore store = new InMemoryReplayStore(5_000);
		var executor = Executors.newFixedThreadPool(3);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> tasks = new ArrayList<>();
		try {
			for (int writer = 0; writer < 2; writer++) {
				int prefix = writer;
				tasks.add(executor.submit(() -> {
					start.await();
					for (int index = 0; index < 1_000; index++) {
						store.store(event(prefix + "-" + index, 100));
					}
					return null;
				}));
			}
			tasks.add(executor.submit(() -> {
				start.await();
				for (int index = 0; index < 1_000; index++) {
					store.purgeExpired(100);
					store.getEventsSince("client", null);
				}
				return null;
			}));
			start.countDown();
			for (Future<?> task : tasks) {
				task.get(10, TimeUnit.SECONDS);
			}
			assertThat(store.getEventsSince("client", null)).hasSize(2_000);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static ReplayEvent event(String id, long timestamp) {
		return new ReplayEvent("client", SseEvent.builder().id(id).data(id).build(), id, timestamp);
	}

}
