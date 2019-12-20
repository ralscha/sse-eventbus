/**
 * Copyright 2016-2019 the original author or authors.
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
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.embedded.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.junit4.SpringRunner;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@SuppressWarnings("resource")
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		classes = TestDefaultConfiguration.class)
public class ListenerTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private SseEventBus eventBus;

	@Autowired
	private TestListener testListener;

	@Before
	public void cleanup() {
		this.eventBus.unregisterClient("1");
		this.testListener.reset();
	}

	@Test
	public void testSimple() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload"));
		assertSseResponse(sseResponse, "event:eventName", "data:payload");

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
		registerSubscribe("1", "eventName", true);
		sleep(21, TimeUnit.SECONDS);

		assertThat(this.testListener.getAfterEventQueuedFirst()).isEmpty();
		assertThat(this.testListener.getAfterEventSentOk()).isEmpty();
		assertThat(this.testListener.getAfterEventQueued()).isEmpty();
		assertThat(this.testListener.getAfterEventSentFail()).isEmpty();
		assertThat(this.testListener.getAfterClientsUnregistered()).hasSize(1);
		assertThat(this.testListener.getAfterClientsUnregistered().get(0)).isEqualTo("1");
	}

	@Test
	public void testReconnect() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName", true);
		sleep(2, TimeUnit.SECONDS);
		assertSseResponseWithException(sseResponse);
		sleep(2, TimeUnit.SECONDS);

		SseEvent sseEvent = SseEvent.builder().event("eventName")
				.data("payload1").build();
		this.eventBus.handleEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload2").build();
		this.eventBus.handleEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload3").build();
		this.eventBus.handleEvent(sseEvent);

		sleep(50, TimeUnit.MILLISECONDS);

		sseResponse = registerSubscribe("1", "eventName");
		assertSseResponse(sseResponse, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2", "", "event:eventName",
				"data:payload3");

		assertThat(this.testListener.getAfterEventQueuedFirst()).hasSize(3);
		assertThat(this.testListener.getAfterEventSentOk()).hasSize(3);
		assertThat(this.testListener.getAfterEventQueued()).hasSize(3);
		assertThat(this.testListener.getAfterEventSentFail()).hasSize(3);
		assertThat(this.testListener.getAfterClientsUnregistered()).isEmpty();
	}

	private String testUrl(String path) {
		return "http://localhost:" + this.port + path;
	}

	private static OkHttpClient createHttpClient() {
		return createHttpClient(10, TimeUnit.SECONDS);
	}

	private static OkHttpClient createHttpClient(long timeout, TimeUnit timeUnit) {
		return new OkHttpClient.Builder().connectTimeout(timeout, timeUnit)
				.writeTimeout(timeout, timeUnit).readTimeout(timeout, timeUnit).build();
	}

	private static void assertSseResponse(Response response, String... lines) {
		assertThat(response.isSuccessful()).isTrue();
		String sse;
		try {
			ResponseBody body = response.body();
			if (body != null) {
				sse = body.string();
				String[] splittedSse = sse.split("\n");
				assertThat(splittedSse).containsExactly(lines);
			}
			else {
				fail("body should not be null");
			}
		}
		catch (IOException e) {
			fail(e.getMessage());
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

	private Response registerSubscribe(String clientId, String eventName)
			throws IOException {
		return registerSubscribe(clientId, eventName, false);
	}

	private Response registerSubscribe(String clientId, String eventName,
			boolean shortTimeout) throws IOException {

		OkHttpClient client;
		if (shortTimeout) {
			client = createHttpClient(500, TimeUnit.MILLISECONDS);
		}
		else {
			client = createHttpClient();
		}
		Request request = new Request.Builder().get()
				.url(testUrl("/register/" + clientId)).build();
		Response longPollResponse = client.newCall(request).execute();
		client.newCall(new Request.Builder().get()
				.url(testUrl("/subscribe/" + clientId + "/" + eventName)).build())
				.execute();
		return longPollResponse;
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
