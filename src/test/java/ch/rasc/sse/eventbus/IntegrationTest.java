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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = TestDefaultConfiguration.class)
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
	public void testOneClientOneDefaultEvent() throws IOException {
		Response sseResponse = registerSubscribe("1", "message");
		this.eventPublisher.publishEvent(SseEvent.ofData("payload"));
		assertSseResponse(sseResponse, "data:payload");
	}

	@Test
	public void testRegisterAndSubscribe() throws IOException {
		Response sseResponse = registerAndSubscribe("1", "message");
		this.eventPublisher.publishEvent(SseEvent.ofData("regandsub"));
		assertSseResponse(sseResponse, "data:regandsub");
	}

	@Test
	public void testRegisterAndSubscribeOnly() throws IOException {
		Response sseResponse = registerAndSubscribeOnly("1", "event1");
		sseResponse = registerAndSubscribe("1", "event2");

		this.eventPublisher.publishEvent(SseEvent.of("event1", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("event2", "payload2"));

		assertSseResponse(sseResponse, "event:event1", "data:payload1", "",
				"event:event2", "data:payload2");

		sseResponse = registerAndSubscribeOnly("1", "event3");
		this.eventPublisher.publishEvent(SseEvent.of("event1", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("event2", "payload2"));
		this.eventPublisher.publishEvent(SseEvent.of("event3", "payload3"));

		assertSseResponse(sseResponse, "event:event3", "data:payload3");
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
				.data("the data line").id("123").retry(Duration.ofMillis(1000L))
				.comment("the comment").build();
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
		assertSseResponse(sseResponse, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
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

		assertSseResponse(sseResponse, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
	}

	@Test
	public void testOneClientOneDirectEventToHimAndOneToSomebodyElse()
			throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("1")
				.event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload2")
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, "event:eventName", "data:payload1");
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
		assertSseResponse(sseResponse1, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
	}

	@Test
	public void testTwoClientsTwoDirectEventToOneOfThem() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientId("2")
				.event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload2")
				.build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse1, "");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
	}

	@Test
	public void testThreeClientsGroupEventToTwoOfThem() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");
		Response sseResponse3 = registerSubscribe("3", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientIds("2", "3")
				.event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addClientIds("2", "3").event("eventName")
				.data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse1, "");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
		assertSseResponse(sseResponse3, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
	}

	@Test
	public void testThreeClientsGroupEventToTwoOfThemIgnoreExclude() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");
		Response sseResponse3 = registerSubscribe("3", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addClientIds("2", "3")
				.addExcludeClientIds("2", "1").event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addClientIds("2", "3")
				.addExcludeClientIds("3", "2", "1").event("eventName").data("payload2")
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse1, "");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
		assertSseResponse(sseResponse3, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
	}

	@Test
	public void testThreeClientsSendExcludeOne() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");
		Response sseResponse3 = registerSubscribe("3", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addExcludeClientId("2")
				.event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addExcludeClientId("1").event("eventName")
				.data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse1, "event:eventName", "data:payload1");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload2");
		assertSseResponse(sseResponse3, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2");
	}

	@Test
	public void testThreeClientsSendExcludeMultiple() throws IOException {
		Response sseResponse1 = registerSubscribe("1", "eventName");
		Response sseResponse2 = registerSubscribe("2", "eventName");
		Response sseResponse3 = registerSubscribe("3", "eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().addExcludeClientIds("2", "3")
				.event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addExcludeClientIds("1", "3").event("eventName")
				.data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse1, "event:eventName", "data:payload1");
		assertSseResponse(sseResponse2, "event:eventName", "data:payload2");
		assertSseResponse(sseResponse3, "");
	}

	@Test
	public void testMultipleSubscriptions() throws IOException {
		Response sseResponse = registerSubscribe("1", "event1");
		subscribe("1", "event1");
		subscribe("1", "event1");
		subscribe("1", "event1");
		subscribe("1", "event1");
		subscribe("1", "event2");

		ImmutableSseEvent sseEvent = SseEvent.builder().event("event1").data("payload")
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, "event:event1", "data:payload");
	}

	@Test
	public void testReconnect() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName", true);
		sleep(2, TimeUnit.SECONDS);
		assertSseResponseWithException(sseResponse);
		sleep(2, TimeUnit.SECONDS);
		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
		assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
		assertThat(this.eventBus.getSubscribers("eventName")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");

		ImmutableSseEvent sseEvent = SseEvent.builder().event("eventName")
				.data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload3").build();
		this.eventPublisher.publishEvent(sseEvent);

		sseResponse = registerSubscribe("1", "eventName");
		assertSseResponse(sseResponse, "event:eventName", "data:payload1", "",
				"event:eventName", "data:payload2", "", "event:eventName",
				"data:payload3");
		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
		assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
		assertThat(this.eventBus.getSubscribers("eventName")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");

		sseEvent = SseEvent.builder().event("eventName").data("payload4").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload5").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload6").build();
		this.eventPublisher.publishEvent(sseEvent);

		sseResponse = registerSubscribe("1", "eventName");
		assertSseResponse(sseResponse, "event:eventName", "data:payload4", "",
				"event:eventName", "data:payload5", "", "event:eventName",
				"data:payload6");
		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
		assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
		assertThat(this.eventBus.getSubscribers("eventName")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");

		this.eventBus.unregisterClient("1");
		assertThat(this.eventBus.getAllClientIds()).hasSize(0);
		assertThat(this.eventBus.getAllEvents()).isEmpty();
		assertThat(this.eventBus.hasSubscribers("eventName")).isFalse();
		assertThat(this.eventBus.getSubscribers("eventName")).isEmpty();
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(0);
		assertThat(this.eventBus.getAllSubscriptions()).isEmpty();
	}

	@Test
	public void testClientExpiration() throws IOException {
		registerSubscribe("1", "eventName", true);
		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
		assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
		assertThat(this.eventBus.getSubscribers("eventName")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");

		sleep(21, TimeUnit.SECONDS);

		assertThat(this.eventBus.getAllClientIds()).hasSize(0);
		assertThat(this.eventBus.getAllEvents()).isEmpty();
		assertThat(this.eventBus.hasSubscribers("eventName")).isFalse();
		assertThat(this.eventBus.getSubscribers("eventName")).isEmpty();
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(0);
		assertThat(this.eventBus.getAllSubscriptions()).isEmpty();
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

		assertThat(this.eventBus.getAllClientIds()).hasSize(120);
		assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
		assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
		assertThat(this.eventBus.getSubscribers("eventName")).hasSize(120);
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(120);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");

		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload"));
		for (int i = 0; i < 100; i++) {
			assertSseResponse(responses.get(i), "event:eventName", "data:payload");
		}

		sleep(21, TimeUnit.SECONDS);
		assertThat(this.eventBus.getAllClientIds()).hasSize(0);
		assertThat(this.eventBus.getAllEvents()).isEmpty();
		assertThat(this.eventBus.hasSubscribers("eventName")).isFalse();
		assertThat(this.eventBus.getSubscribers("eventName")).isEmpty();
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(0);
		assertThat(this.eventBus.getAllSubscriptions()).isEmpty();
	}

	@Test
	public void testJsonConverter() throws IOException {
		Response sseResponse = registerSubscribe("1", "to1");
		TestObject1 to1 = new TestObject1(101L, "john doe");

		this.eventPublisher.publishEvent(SseEvent.of("to1", to1));
		assertSseResponse(sseResponse, "event:to1",
				"data:{\"id\":101,\"name\":\"john doe\"}");
	}

	@Test
	public void testCustomConverter() throws IOException {
		Response sseResponse = registerSubscribe("2", "message");
		TestObject2 to2 = new TestObject2(999L, "sample inc.");

		this.eventPublisher.publishEvent(SseEvent.ofData(to2));
		assertSseResponse(sseResponse, "data:999,sample inc.");
	}

	@Test
	public void testJsonViewNoView() throws IOException {
		Response sseResponse = registerSubscribe("1", "jsonView1");
		TestObject3 to3 = new TestObject3();
		to3.setPrivateData(23);
		to3.setPublicInfo("this is public");
		to3.setUuid("abc");

		this.eventPublisher.publishEvent(SseEvent.of("jsonView1", to3));
		assertSseResponse(sseResponse, "event:jsonView1",
				"data:{\"uuid\":\"abc\",\"publicInfo\":\"this is public\",\"privateData\":23}");
	}

	@Test
	public void testJsonViewPublicView() throws IOException {
		Response sseResponse = registerSubscribe("1", "jsonView1");
		TestObject3 to3 = new TestObject3();
		to3.setPrivateData(23);
		to3.setPublicInfo("this is public");
		to3.setUuid("abc");

		this.eventPublisher.publishEvent(SseEvent.builder().event("jsonView1").data(to3)
				.jsonView(JsonViews.PUBLIC.class).build());
		assertSseResponse(sseResponse, "event:jsonView1",
				"data:{\"uuid\":\"abc\",\"publicInfo\":\"this is public\"}");
	}

	@Test
	public void testJsonViewPrivateView() throws IOException {
		Response sseResponse = registerSubscribe("1", "jsonView1");
		TestObject3 to3 = new TestObject3();
		to3.setPrivateData(23);
		to3.setPublicInfo("this is public");
		to3.setUuid("abc");

		this.eventPublisher.publishEvent(SseEvent.builder().event("jsonView1").data(to3)
				.jsonView(JsonViews.PRIVATE.class).build());
		assertSseResponse(sseResponse, "event:jsonView1",
				"data:{\"uuid\":\"abc\",\"publicInfo\":\"this is public\",\"privateData\":23}");
	}

	@Test
	public void testMultiline() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "1. line\n2. line"));
		assertSseResponse(sseResponse, "event:eventName", "data:1. line", "data:2. line");
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

	private void subscribe(String clientId, String eventName) throws IOException {
		OkHttpClient client = createHttpClient();
		client.newCall(new Request.Builder().get()
				.url(testUrl("/subscribe/" + clientId + "/" + eventName)).build())
				.execute();
	}

	private Response registerAndSubscribe(String clientId, String eventName)
			throws IOException {
		OkHttpClient client = createHttpClient();
		Request request = new Request.Builder().get()
				.url(testUrl("/register/" + clientId + "/" + eventName)).build();
		return client.newCall(request).execute();
	}

	private Response registerAndSubscribeOnly(String clientId, String eventName)
			throws IOException {
		OkHttpClient client = createHttpClient();
		Request request = new Request.Builder().get()
				.url(testUrl("/registerOnly/" + clientId + "/" + eventName)).build();
		return client.newCall(request).execute();
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
