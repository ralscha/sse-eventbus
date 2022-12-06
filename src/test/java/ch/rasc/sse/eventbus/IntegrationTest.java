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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@SuppressWarnings("resource")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		classes = TestDefaultConfiguration.class)
public class IntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private SseEventBus eventBus;

	@BeforeEach
	public void cleanup() {
		for (String clientId : this.eventBus.getAllClientIds()) {
			this.eventBus.unregisterClient(clientId);
		}
	}

	@Test
	public void testOneClientOneEvent() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload"));
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload"));
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneDefaultEvent() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "message");
		this.eventPublisher.publishEvent(SseEvent.ofData("payload"));
		assertSseResponse(sseResponse, new ResponseData("message", "payload"));
		sseResponse.eventSource().close();
	}

	@Test
	public void testRegisterAndSubscribe() throws IOException, InterruptedException {
		SubscribeResponse sseResponse = registerAndSubscribe("1", "message", 1);
		TimeUnit.SECONDS.sleep(3);
		this.eventPublisher.publishEvent(SseEvent.ofData("regandsub"));
		assertSseResponse(sseResponse, new ResponseData("message", "regandsub"));
		sseResponse.eventSource().close();
	}

	@Test
	@Disabled
	public void testRegisterAndSubscribeOnly() throws IOException, InterruptedException {
		SubscribeResponse sseResponse1 = registerAndSubscribeOnly("1", "event1", 2);
		TimeUnit.SECONDS.sleep(3);
		SubscribeResponse sseResponse2 = registerAndSubscribe("1", "event2", 2);
		TimeUnit.SECONDS.sleep(3);

		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("event1", "event2");
		assertThat(this.eventBus.hasSubscribers("event1")).isTrue();
		assertThat(this.eventBus.getSubscribers("event2")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("event1")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("event1",
				"event2");

		this.eventPublisher.publishEvent(SseEvent.of("event1", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("event2", "payload2"));

		assertSseResponse(sseResponse2, new ResponseData("event1", "payload1"),
				new ResponseData("event2", "payload2"));

		SubscribeResponse sseResponse = registerAndSubscribeOnly("1", "event3", 1);
		this.eventPublisher.publishEvent(SseEvent.of("event1", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("event2", "payload2"));
		this.eventPublisher.publishEvent(SseEvent.of("event3", "payload3"));

		assertSseResponse(sseResponse, new ResponseData("event3", "payload3"));

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
	}

	@Test
	public void testOneClientOneEventEmptyData() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.ofEvent("eventName"));
		assertSseResponse(sseResponse, new ResponseData("eventName", ""));
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneEventAdditionalInfo() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName");
		SseEvent sseEvent = SseEvent.builder().event("eventName").data("the data line")
				.id("123").retry(Duration.ofMillis(1000L)).comment("the comment").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, new ResponseData("eventName", "the data line"));
		// "id:123", "retry:1000",
		// ":the comment", "data:the data line");
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneDirectEvent() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName");
		SseEvent sseEvent = SseEvent.builder().addClientId("1").event("eventName")
				.data("payload").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload"));
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientNoEvent() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventNameSecond", "payload"));
		assertSseResponse(sseResponse);
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneDirectEventToSomebodyElse() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName");
		SseEvent sseEvent = SseEvent.builder().addClientId("2").event("eventName")
				.data("payload").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse);
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientTwoEvents() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName", false, 2);
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload2"));
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientTwoDirectEvents() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName", false, 2);

		SseEvent sseEvent = SseEvent.builder().addClientId("1").event("eventName")
				.data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addClientId("1").event("eventName").data("payload2")
				.build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneDirectEventToHimAndOneToSomebodyElse()
			throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName");

		SseEvent sseEvent = SseEvent.builder().addClientId("1").event("eventName")
				.data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload2")
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testTwoClientsOneAllEvent() throws IOException {
		SubscribeResponse sseResponse1 = registerSubscribe("1", "eventName");
		SubscribeResponse sseResponse2 = registerSubscribe("2", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload1"));
		assertSseResponse(sseResponse1, new ResponseData("eventName", "payload1"));
		assertSseResponse(sseResponse2, new ResponseData("eventName", "payload1"));

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
	}

	@Test
	public void testTwoClientsTwoAllEvent() throws IOException {
		SubscribeResponse sseResponse1 = registerSubscribe("1", "eventName", false, 2);
		SubscribeResponse sseResponse2 = registerSubscribe("2", "eventName", false, 2);
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload2"));
		assertSseResponse(sseResponse1, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));
		assertSseResponse(sseResponse2, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
	}

	@Test
	public void testTwoClientsTwoDirectEventToOneOfThem() throws IOException {
		SubscribeResponse sseResponse1 = registerSubscribe("1", "eventName");
		SubscribeResponse sseResponse2 = registerSubscribe("2", "eventName");

		SseEvent sseEvent = SseEvent.builder().addClientId("2").event("eventName")
				.data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload2")
				.build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse1);
		assertSseResponse(sseResponse2, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
	}

	@Test
	public void testThreeClientsGroupEventToTwoOfThem() throws IOException {
		SubscribeResponse sseResponse1 = registerSubscribe("1", "eventName");
		SubscribeResponse sseResponse2 = registerSubscribe("2", "eventName");
		SubscribeResponse sseResponse3 = registerSubscribe("3", "eventName");

		SseEvent sseEvent = SseEvent.builder().addClientIds("2", "3").event("eventName")
				.data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addClientIds("2", "3").event("eventName")
				.data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse1);
		assertSseResponse(sseResponse2, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));
		assertSseResponse(sseResponse3, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
		sseResponse3.eventSource().close();
	}

	@Test
	public void testThreeClientsGroupEventToTwoOfThemIgnoreExclude() throws IOException {
		SubscribeResponse sseResponse1 = registerSubscribe("1", "eventName");
		SubscribeResponse sseResponse2 = registerSubscribe("2", "eventName");
		SubscribeResponse sseResponse3 = registerSubscribe("3", "eventName");

		SseEvent sseEvent = SseEvent.builder().addClientIds("2", "3")
				.addExcludeClientIds("2", "1").event("eventName").data("payload1")
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addClientIds("2", "3")
				.addExcludeClientIds("3", "2", "1").event("eventName").data("payload2")
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse1);
		assertSseResponse(sseResponse2, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));
		assertSseResponse(sseResponse3, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
		sseResponse3.eventSource().close();
	}

	@Test
	public void testThreeClientsSendExcludeOne() throws IOException {
		SubscribeResponse sseResponse1 = registerSubscribe("1", "eventName");
		SubscribeResponse sseResponse2 = registerSubscribe("2", "eventName");
		SubscribeResponse sseResponse3 = registerSubscribe("3", "eventName", 2);

		SseEvent sseEvent = SseEvent.builder().addExcludeClientId("2").event("eventName")
				.data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addExcludeClientId("1").event("eventName")
				.data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse1, new ResponseData("eventName", "payload1"));
		assertSseResponse(sseResponse2, new ResponseData("eventName", "payload2"));
		assertSseResponse(sseResponse3, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
		sseResponse3.eventSource().close();
	}

	@Test
	public void testThreeClientsSendExcludeMultiple() throws IOException {
		SubscribeResponse sseResponse1 = registerSubscribe("1", "eventName");
		SubscribeResponse sseResponse2 = registerSubscribe("2", "eventName");
		SubscribeResponse sseResponse3 = registerSubscribe("3", "eventName");

		SseEvent sseEvent = SseEvent.builder().addExcludeClientIds("2", "3")
				.event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addExcludeClientIds("1", "3").event("eventName")
				.data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse1, new ResponseData("eventName", "payload1"));
		assertSseResponse(sseResponse2, new ResponseData("eventName", "payload2"));
		assertSseResponse(sseResponse3);

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
		sseResponse3.eventSource().close();
	}

	@Test
	public void testMultipleSubscriptions() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "event1");
		subscribe("1", "event1");
		subscribe("1", "event1");
		subscribe("1", "event1");
		subscribe("1", "event1");
		subscribe("1", "event2");

		SseEvent sseEvent = SseEvent.builder().event("event1").data("payload").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, new ResponseData("event1", "payload"));

		sseResponse.eventSource().close();
	}

	@Test
	@Disabled
	public void testReconnect() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName", true, 1);
		sleep(2, TimeUnit.SECONDS);
		// assertSseResponseWithException(sseResponse);
		sleep(2, TimeUnit.SECONDS);
		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
		assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
		assertThat(this.eventBus.getSubscribers("eventName")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");

		SseEvent sseEvent = SseEvent.builder().event("eventName").data("payload1")
				.build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload3").build();
		this.eventPublisher.publishEvent(sseEvent);

		sseResponse = registerSubscribe("1", "eventName", 3);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"),
				new ResponseData("eventName", "payload3"));
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

		sseResponse = registerSubscribe("1", "eventName", 3);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload4"),
				new ResponseData("eventName", "payload5"),
				new ResponseData("eventName", "payload6"));
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

		sseResponse.eventSource().close();
	}

	@Test
	public void testClientExpiration() throws IOException {
		var response = registerSubscribe("1", "eventName", true, 1);
		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
		assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
		assertThat(this.eventBus.getSubscribers("eventName")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");
		response.eventSource().close();

		sleep(21, TimeUnit.SECONDS);

		assertThat(this.eventBus.getAllClientIds()).hasSize(0);
		assertThat(this.eventBus.getAllEvents()).isEmpty();
		assertThat(this.eventBus.hasSubscribers("eventName")).isFalse();
		assertThat(this.eventBus.getSubscribers("eventName")).isEmpty();
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(0);
		assertThat(this.eventBus.getAllSubscriptions()).isEmpty();

		response.eventSource().close();
	}

	@Test
	public void testMany() throws IOException {
		List<SubscribeResponse> responses = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			responses.add(registerSubscribe(String.valueOf(i), "eventName"));
		}
		for (int i = 100; i < 120; i++) {
			responses.add(registerSubscribe(String.valueOf(i), "eventName", true, 1));
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
			assertSseResponse(responses.get(i), new ResponseData("eventName", "payload"));
		}

		for (SubscribeResponse response : responses) {
			response.eventSource().close();
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
		SubscribeResponse sseResponse = registerSubscribe("1", "to1");
		TestObject1 to1 = new TestObject1(101L, "john doe");

		this.eventPublisher.publishEvent(SseEvent.of("to1", to1));
		assertSseResponse(sseResponse,
				new ResponseData("to1", "{\"id\":101,\"name\":\"john doe\"}"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testCustomConverter() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("2", "message");
		TestObject2 to2 = new TestObject2(999L, "sample inc.");

		this.eventPublisher.publishEvent(SseEvent.ofData(to2));
		assertSseResponse(sseResponse, new ResponseData("message", "999,sample inc."));

		sseResponse.eventSource().close();
	}

	@Test
	public void testJsonViewNoView() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "jsonView1");
		TestObject3 to3 = new TestObject3();
		to3.setPrivateData(23);
		to3.setPublicInfo("this is public");
		to3.setUuid("abc");

		this.eventPublisher.publishEvent(SseEvent.of("jsonView1", to3));
		assertSseResponse(sseResponse, new ResponseData("jsonView1",
				"{\"uuid\":\"abc\",\"publicInfo\":\"this is public\",\"privateData\":23}"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testJsonViewPublicView()
			throws IOException, InterruptedException, ExecutionException {
		SubscribeResponse sseResponse = registerSubscribe("1", "jsonView1");
		TestObject3 to3 = new TestObject3();
		to3.setPrivateData(23);
		to3.setPublicInfo("this is public");
		to3.setUuid("abc");

		this.eventPublisher.publishEvent(SseEvent.builder().event("jsonView1").data(to3)
				.jsonView(JsonViews.PUBLIC.class).build());

		assertSseResponse(sseResponse, new ResponseData("jsonView1",
				"{\"uuid\":\"abc\",\"publicInfo\":\"this is public\"}"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testJsonViewPrivateView() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "jsonView1");
		TestObject3 to3 = new TestObject3();
		to3.setPrivateData(23);
		to3.setPublicInfo("this is public");
		to3.setUuid("abc");

		this.eventPublisher.publishEvent(SseEvent.builder().event("jsonView1").data(to3)
				.jsonView(JsonViews.PRIVATE.class).build());
		assertSseResponse(sseResponse, new ResponseData("jsonView1",
				"{\"uuid\":\"abc\",\"publicInfo\":\"this is public\",\"privateData\":23}"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testMultiline() throws IOException {
		SubscribeResponse sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "1. line\n2. line"));
		assertSseResponse(sseResponse, new ResponseData("eventName", "1. line\n2. line"));

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

	private static void assertSseResponse(SubscribeResponse response,
			ResponseData... expected) {
		try {
			List<ResponseData> rds;
			try {
				rds = response.dataFuture.get(5, TimeUnit.SECONDS);
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

	private SubscribeResponse registerSubscribe(String clientId, String eventName)
			throws IOException {
		return registerSubscribe(clientId, eventName, false, 1);
	}

	private SubscribeResponse registerSubscribe(String clientId, String eventName,
			int expectedNoOfData) throws IOException {
		return registerSubscribe(clientId, eventName, false, expectedNoOfData);
	}

	private SubscribeResponse registerSubscribe(String clientId, String eventName,
			boolean shortTimeout, int expectedNoOfData) throws IOException {

		CompletableFuture<List<ResponseData>> dataFuture = new CompletableFuture<>();
		List<ResponseData> responses = new ArrayList<>();
		EventSource.Builder builder = new EventSource.Builder(
				(DefaultEventHandler) (event, messageEvent) -> {
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
		client.newCall(new Request.Builder().get()
				.url(testUrl("/subscribe/" + clientId + "/" + eventName)).build())
				.execute();
		return new SubscribeResponse(eventSource, dataFuture);
	}

	private void subscribe(String clientId, String eventName) throws IOException {
		OkHttpClient client = createHttpClient();
		client.newCall(new Request.Builder().get()
				.url(testUrl("/subscribe/" + clientId + "/" + eventName)).build())
				.execute();
	}

	private SubscribeResponse registerAndSubscribe(String clientId, String eventName,
			int expectedNoOfData) {
		CompletableFuture<List<ResponseData>> dataFuture = new CompletableFuture<>();
		List<ResponseData> responses = new ArrayList<>();
		EventSource.Builder builder = new EventSource.Builder(
				(DefaultEventHandler) (event, messageEvent) -> {
					System.out.println(event);
					responses.add(new ResponseData(event, messageEvent.getData()));
					if (responses.size() == expectedNoOfData) {
						dataFuture.complete(responses);
					}
				}, URI.create(testUrl("/register/" + clientId + "/" + eventName)));
		EventSource eventSource = builder.build();
		eventSource.start();

		return new SubscribeResponse(eventSource, dataFuture);
	}

	private SubscribeResponse registerAndSubscribeOnly(String clientId, String eventName,
			int expectedNoOfData) {
		CompletableFuture<List<ResponseData>> dataFuture = new CompletableFuture<>();
		List<ResponseData> responses = new ArrayList<>();
		EventSource.Builder builder = new EventSource.Builder(
				(DefaultEventHandler) (event, messageEvent) -> {
					responses.add(new ResponseData(event, messageEvent.getData()));
					if (responses.size() == expectedNoOfData) {
						dataFuture.complete(responses);
					}
				}, URI.create(testUrl("/registerOnly/" + clientId + "/" + eventName)));
		EventSource eventSource = builder.connectTimeout(10, TimeUnit.SECONDS).build();
		eventSource.start();

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

	static record SubscribeResponse(EventSource eventSource,
			CompletableFuture<List<ResponseData>> dataFuture) {
	}

	static record ResponseData(String event, String data) {
	}
}
