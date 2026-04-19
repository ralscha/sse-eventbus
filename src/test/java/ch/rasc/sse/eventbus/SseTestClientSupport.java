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

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.awaitility.Awaitility.await;
import org.jspecify.annotations.Nullable;

import com.launchdarkly.eventsource.ConnectStrategy;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.background.BackgroundEventHandler;
import com.launchdarkly.eventsource.background.BackgroundEventSource;

import static ch.rasc.sse.eventbus.TestUtils.sleep;
import okhttp3.OkHttpClient;
import okhttp3.Request;

final class SseTestClientSupport {

	private static final Duration REGISTRATION_WAIT = Duration.ofSeconds(5);

	private static final Duration SUBSCRIPTION_WAIT = Duration.ofSeconds(5);

	private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

	private static final Duration SETTLE_DELAY = Duration.ofMillis(500);

	private static final Duration SHORT_HTTP_TIMEOUT = Duration.ofMillis(500);

	private SseTestClientSupport() {
	}

	static <T> SseResponse<T> open(String url, int expectedNoOfData, @Nullable String lastEventId,
			BiFunction<String, MessageEvent, T> mapper) {
		CompletableFuture<List<T>> dataFuture = new CompletableFuture<>();
		List<T> responses = new ArrayList<>();

		EventSource.Builder builder = new EventSource.Builder(
				ConnectStrategy.http(URI.create(url)).connectTimeout(CONNECTION_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
		if (lastEventId != null) {
			builder.lastEventId(lastEventId);
		}

		BackgroundEventHandler handler = (DefaultEventHandler) (event, messageEvent) -> {
			responses.add(mapper.apply(event, messageEvent));
			if (responses.size() == expectedNoOfData) {
				dataFuture.complete(responses);
			}
		};

		BackgroundEventSource eventSource = new BackgroundEventSource.Builder(handler, builder).build();
		eventSource.start();
		return new SseResponse<>(eventSource, dataFuture);
	}

	static void awaitClientRegistered(SseEventBus eventBus, String clientId) {
		await().atMost(REGISTRATION_WAIT).until(() -> eventBus.getAllClientIds().contains(clientId));
	}

	static void awaitClientSubscribed(SseEventBus eventBus, String clientId, String eventName) {
		await().atMost(SUBSCRIPTION_WAIT)
			.until(() -> eventBus.getAllClientIds().contains(clientId)
					&& eventBus.getSubscribers(eventName).contains(clientId));
	}

	static void invokeGet(String url, boolean shortTimeout) throws IOException {
		OkHttpClient client = shortTimeout ? createHttpClient(SHORT_HTTP_TIMEOUT)
				: createHttpClient(CONNECTION_TIMEOUT);
		var response = client.newCall(new Request.Builder().get().url(url).build()).execute();
		response.close();
	}

	static void settleSubscription() {
		sleep(SETTLE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
	}

	private static OkHttpClient createHttpClient(Duration timeout) {
		return new OkHttpClient.Builder().connectTimeout(timeout).writeTimeout(timeout).readTimeout(timeout).build();
	}

	record SseResponse<T>(BackgroundEventSource eventSource, CompletableFuture<List<T>> dataFuture) {
	}

	record ResponseData(String event, String data, @Nullable String id) {

		ResponseData(String event, String data) {
			this(event, data, null);
		}

	}

}