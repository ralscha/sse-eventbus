/**
 * Copyright 2016-2022 the original author or authors.
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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;

import com.launchdarkly.eventsource.EventSource;

import ch.rasc.sse.eventbus.IntegrationTest.ResponseData;
import ch.rasc.sse.eventbus.IntegrationTest.SubscribeResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@SuppressWarnings("resource")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = TestDefaultConfiguration.class)
public class ListenerTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private SseEventBus eventBus;

	@Autowired
	private TestListener testListener;

	@BeforeEach
	public void cleanup() {
		for (String clientId : this.eventBus.getAllClientIds()) {
			this.eventBus.unregisterClient(clientId);
		}
		this.testListener.reset();
	}

	@Test
	public void testSimple() throws IOException, InterruptedException {
		var sseResponse = registerSubscribe("1", "eventName", 1);
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload"));
		TimeUnit.SECONDS.sleep(1);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload"));

		assertThat(this.testListener.getAfterEventQueuedFirst()).hasSize(1);
		ClientEvent ce1 = this.testListener.getAfterEventQueuedFirst().get(0);
		assertThat(ce1.getClient().getId()).isEqualTo("1");
		assertThat(ce1.getSseEvent().data()).isEqualTo("payload");
		assertThat(ce1.getErrorCounter()).isZero();

		assertThat(this.testListener.getAfterEventSentOk()).hasSize(1);
		ClientEvent ce2 = this.testListener.getAfterEventSentOk().get(0);
		assertThat(ce2.getClient().getId()).isEqualTo("1");
		assertThat(ce2.getSseEvent().data()).isEqualTo("payload");
		assertThat(ce2.getErrorCounter()).isZero();

		assertThat(this.testListener.getAfterEventQueued()).isEmpty();
		assertThat(this.testListener.getAfterEventSentFail()).isEmpty();
		assertThat(this.testListener.getAfterClientsUnregistered()).isEmpty();

		sseResponse.eventSource().close();
	}

	@Test
	public void testNoReceivers() {
		this.eventPublisher.publishEvent(SseEvent.of("otherEvent", "payload"));
		sleep(3, TimeUnit.SECONDS);

		assertThat(this.testListener.getAfterEventQueuedFirst()).isEmpty();
		assertThat(this.testListener.getAfterEventSentOk()).isEmpty();
		assertThat(this.testListener.getAfterEventQueued()).isEmpty();
		assertThat(this.testListener.getAfterEventSentFail()).isEmpty();
		assertThat(this.testListener.getAfterClientsUnregistered()).isEmpty();
	}

	@Test
	public void testClientExpiration() throws IOException {
		var response = registerSubscribe("1", "eventName", true, 1);
		sleep(21, TimeUnit.SECONDS);

		assertThat(this.testListener.getAfterEventQueuedFirst()).isEmpty();
		assertThat(this.testListener.getAfterEventSentOk()).isEmpty();
		assertThat(this.testListener.getAfterEventQueued()).isEmpty();
		assertThat(this.testListener.getAfterEventSentFail()).isEmpty();
		assertThat(this.testListener.getAfterClientsUnregistered()).hasSize(1);
		assertThat(this.testListener.getAfterClientsUnregistered().get(0)).isEqualTo("1");

		response.eventSource().close();
	}

	@Test
	@Disabled
	public void testReconnect() throws IOException {
		var sseResponse = registerSubscribe("1", "eventName", true, 3);
		sleep(2, TimeUnit.SECONDS);
		// assertSseResponseWithException(sseResponse);
		// sleep(2, TimeUnit.SECONDS);

		SseEvent sseEvent = SseEvent.builder().event("eventName").data("payload1").build();
		this.eventBus.handleEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload2").build();
		this.eventBus.handleEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload3").build();
		this.eventBus.handleEvent(sseEvent);

		sleep(5, TimeUnit.MILLISECONDS);

		sseResponse = registerSubscribe("1", "eventName", 3);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"), new ResponseData("eventName", "payload3"));

		assertThat(this.testListener.getAfterEventQueuedFirst()).hasSize(3);
		assertThat(this.testListener.getAfterEventSentOk()).hasSize(3);
		assertThat(this.testListener.getAfterEventQueued()).hasSize(3);
		assertThat(this.testListener.getAfterEventSentFail()).hasSize(3);
		assertThat(this.testListener.getAfterClientsUnregistered()).isEmpty();

		sseResponse.eventSource().close();
	}

	private String testUrl(String path) {
		return "http://localhost:" + this.port + path;
	}

	private static OkHttpClient createHttpClient() {
		return createHttpClient(10, TimeUnit.SECONDS);
	}

	private static OkHttpClient createHttpClient(long timeout, TimeUnit timeUnit) {
		return new OkHttpClient.Builder().connectTimeout(timeout, timeUnit)
			.writeTimeout(timeout, timeUnit)
			.readTimeout(timeout, timeUnit)
			.build();
	}

	private static void assertSseResponse(SubscribeResponse response, ResponseData... expected) {
		try {
			List<ResponseData> rds;
			try {
				rds = response.dataFuture().get(5, TimeUnit.SECONDS);
			}
			catch (TimeoutException e) {
				rds = List.of();
			}
			assertThat(rds).hasSize(expected.length);
			for (int i = 0; i < expected.length; i++) {
				assertThat(rds.get(i).event()).isEqualTo(expected[i].event());
				assertThat(rds.get(i).data()).isEqualTo(expected[i].data());
			}
			response.eventSource().close();
		}
		catch (InterruptedException | ExecutionException e) {
			fail(e);
		}
	}

	private static void assertSseResponseWithException(Response response) {
		assertThat(response.isSuccessful()).isTrue();
		try {
			ResponseBody body = response.body();
			if (body != null) {
				body.string();
				fail("request the body should fail");
			}
			else {
				fail("body should not be null");
			}
		}
		catch (IOException e) {
			System.out.println(e.getMessage());
		}
	}

	private SubscribeResponse registerSubscribe(String clientId, String eventName, int expectedNoOfData)
			throws IOException {
		return registerSubscribe(clientId, eventName, false, expectedNoOfData);
	}

	private SubscribeResponse registerSubscribe(String clientId, String eventName, boolean shortTimeout,
			int expectedNoOfData) throws IOException {

		CompletableFuture<List<ResponseData>> dataFuture = new CompletableFuture<>();
		List<ResponseData> responses = new ArrayList<>();
		EventSource.Builder builder = new EventSource.Builder((DefaultEventHandler) (event, messageEvent) -> {
			responses.add(new ResponseData(event, messageEvent.getData()));
			if (responses.size() == expectedNoOfData) {
				dataFuture.complete(responses);
			}
		}, URI.create(testUrl("/register/" + clientId)));
		EventSource eventSource = builder.build();
		eventSource.start();

		OkHttpClient client;
		if (shortTimeout) {
			client = createHttpClient(500, TimeUnit.MILLISECONDS);
		}
		else {
			client = createHttpClient();
		}
		client.newCall(new Request.Builder().get().url(testUrl("/subscribe/" + clientId + "/" + eventName)).build())
			.execute();
		sleep(333, TimeUnit.MILLISECONDS);
		return new SubscribeResponse(eventSource, dataFuture);
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
