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
package ch.rasc.sse.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@SuppressWarnings("resource")
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		classes = TestDefaultConfiguration.class)
public class IntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private SseEventBus eventBus;

	@Before
	public void cleanup() {
		this.eventBus.unregisterClient("1");
		this.eventBus.unregisterClient("2");
		this.eventBus.unregisterClient("3");
	}

	@Test
	public void testOneClientOneEvent() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload"));
		assertSseResponse(sseResponse, "event:eventName", "data:payload");
	}

	@Test
	public void testOneClientOneEventEmptyData() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.ofEvent("eventName"));
		assertSseResponse(sseResponse, "event:eventName", "data:");
	}

	@Test
	public void testOneClientOneEventAdditionalInfo() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		ImmutableSseEvent sseEvent = SseEvent.builder().event("eventName")
				.data("the data line").id("123").retry(1000L).comment("the comment")
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, "event:eventName", "id:123", "retry:1000",
				":the comment", "data:the data line");
	}

	@Test
	public void testOneClientOneDirectEvent() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("1")
				.event("eventName").data("payload").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, "event:eventName", "data:payload");
	}

	@Test
	public void testOneClientNoEvent() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventNameSecond", "payload"));
		assertSseResponse(sseResponse, "");
	}

	@Test
	public void testOneClientOneDirectEventToSomebodyElse() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("2")
				.event("eventName").data("payload").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, "");
	}

	@Test
	public void testOneClientTwoEvents() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload2"));
		assertSseResponse(sseResponse, "event:eventName", "data:payload2");
	}

	@Test
	public void testOneClientTwoDirectEvents() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("1")
				.event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addClientId("1").event("eventName").data("payload2")
				.build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse, "event:eventName", "data:payload2");
	}

	@Test
	public void testOneClientOneDirectEventToHimAndOneToSomebodyElse()
			throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("1")
				.event("eventName").data("payload1").combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload2")
				.combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, "event:eventName", "data:payload1");
	}

	@Test
	public void testOneClientTwoEventsCombine() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().event("eventName")
				.data("payload1").combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().event("eventName").data("payload2").combine(true)
				.build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse, "event:eventName", "data:payload1",
				"data:payload2");
	}

	@Test
	public void testOneClientTwoDirectEventsCombine() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("1")
				.event("eventName").data("payload1").combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("1").event("eventName").data("payload2")
				.combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse, "event:eventName", "data:payload1",
				"data:payload2");
	}

	@Test
	public void testTwoClientsOneAllEvent() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload1"));
		assertSseResponse(sseResponse1, "event:eventName", "data:payload1");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload1");
	}

	@Test
	public void testTwoClientsTwoAllEvent() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload2"));
		assertSseResponse(sseResponse1, "event:eventName", "data:payload2");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload2");
	}

	@Test
	public void testTwoClientsTwoAllEventCombine() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().event("eventName")
				.data("payload1").combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().event("eventName").data("payload2").combine(true)
				.build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse1, "event:eventName", "data:payload1",
				"data:payload2");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload1",
				"data:payload2");
	}

	@Test
	public void testTwoClientsTwoDirectEventToOneOfThem() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("2")
				.event("eventName").data("payload1").combine(false).build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload2")
				.combine(false).build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse1, "");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload2");
	}

	@Test
	public void testTwoClientsTwoDirectEventCombineToOneOfThem() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("2")
				.event("eventName").data("payload1").combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload2")
				.combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse1, "");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload1",
				"data:payload2");
	}

	@Test
	public void testThreeClientsGroupEventToTwoOfThem() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");
		Response sseResponse3 = registerSubscribe("3", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("2", "3")
				.event("eventName").data("payload1").combine(false).build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addClientId("2", "3").event("eventName")
				.data("payload2").combine(false).build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse1, "");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload2");
		assertSseResponse(sseResponse3, "event:eventName", "data:payload2");
	}

	@Test
	public void testThreeClientsGroupEventCombineToTwoOfThem() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");
		Response sseResponse3 = registerSubscribe("3", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("2", "3")
				.event("eventName").data("payload1").combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2", "3").event("eventName")
				.data("payload2").combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse1, "");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload1",
				"data:payload2");
		assertSseResponse(sseResponse3, "event:eventName", "data:payload1",
				"data:payload2");
	}

	@Test
	public void testReconnect() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName", true);
		sleep(2, TimeUnit.SECONDS);
		assertSseResponseWithException(sseResponse);
		sleep(2, TimeUnit.SECONDS);
		assertThat(clients()).hasSize(1);

		ImmutableSseEvent sseEvent = SseEvent.builder().event("eventName")
				.data("payload1").combine(true).build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload2").combine(true)
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload3").combine(true)
				.build();
		this.eventPublisher.publishEvent(sseEvent);

		sseResponse = registerSubscribe("1", "eventName");
		assertSseResponse(sseResponse, "event:eventName", "data:payload1",
				"data:payload2", "data:payload3");
		assertThat(clients()).hasSize(1);

		sseEvent = SseEvent.builder().event("eventName").data("payload4").combine(false)
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload5").combine(false)
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload6").combine(false)
				.build();
		this.eventPublisher.publishEvent(sseEvent);

		sseResponse = registerSubscribe("1", "eventName");
		assertSseResponse(sseResponse, "event:eventName", "data:payload6");
		assertThat(clients()).hasSize(1);

		this.eventBus.unregisterClient("1");
		assertThat(clients()).hasSize(0);
	}

	@Test
	public void testClientExpiration() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName", true);
		assertThat(clients()).hasSize(1);
		sleep(21, TimeUnit.SECONDS);
		assertThat(clients()).hasSize(0);
	}

	@Test
	public void testMany() throws IOException {
		List<Response> responses = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			responses.add(registerSubscribe(String.valueOf(i), "eventName"));
		}
		for (int i = 100; i < 120; i++) {
			responses.add(registerSubscribe(String.valueOf(i), "eventName", true));
		}
		sleep(1, TimeUnit.SECONDS);
		assertThat(clients()).hasSize(120);

		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload"));
		for (int i = 0; i < 100; i++) {
			assertSseResponse(responses.get(i), "event:eventName", "data:payload");
		}

		sleep(21, TimeUnit.SECONDS);
		assertThat(clients()).hasSize(0);
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

	private static void assertSseResponseWithException(Response response) {
		assertThat(response.isSuccessful()).isTrue();
		try {
			response.body().string();
			fail("request the body should fail");
		}
		catch (IOException e) {
			System.out.println(e.getMessage());
		}
	}

	private static void assertSseResponse(Response response, String... lines) {
		assertThat(response.isSuccessful()).isTrue();
		String sse;
		try {
			sse = response.body().string();
			String[] splittedSse = sse.split("\n");
			assertThat(splittedSse).containsExactly(lines);
		}
		catch (IOException e) {
			fail(e.getMessage());
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

	private Response registerSubscribeOne(String clientId, String eventName)
			throws IOException {
		OkHttpClient client = createHttpClient();
		Request request = new Request.Builder().get()
				.url(testUrl("/register/" + clientId + "/" + eventName)).build();
		Response longPollResponse = client.newCall(request).execute();
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

	@SuppressWarnings("unchecked")
	private Map<String, SseEmitter> clients() {
		return (Map<String, SseEmitter>) ReflectionTestUtils.getField(this.eventBus,
				"clients");
	}

}
