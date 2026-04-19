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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.jspecify.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;

import ch.rasc.sse.eventbus.SseTestClientSupport.ResponseData;
import ch.rasc.sse.eventbus.SseTestClientSupport.SseResponse;
import static ch.rasc.sse.eventbus.TestUtils.sleep;

@SuppressWarnings("resource")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = TestDefaultConfiguration.class)
public class IntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private SseEventBus eventBus;

	@Autowired
	private ReplayStore replayStore;

	@BeforeEach
	public void cleanup() {
		for (String clientId : this.eventBus.getAllClientIds()) {
			this.eventBus.unregisterClient(clientId);
		}
		if (!this.eventBus.getAllClientIds().isEmpty()) {
			await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
				for (String clientId : this.eventBus.getAllClientIds()) {
					this.eventBus.unregisterClient(clientId);
				}
				assertThat(this.eventBus.getAllClientIds()).isEmpty();
			});
		}
	}

	@Test
	public void testOneClientOneEvent() throws IOException, InterruptedException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload"));
		TimeUnit.SECONDS.sleep(2);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload"));
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneDefaultEvent() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "message");
		this.eventPublisher.publishEvent(SseEvent.ofData("payload"));
		assertSseResponse(sseResponse, new ResponseData("message", "payload"));
		sseResponse.eventSource().close();
	}

	@Test
	public void testRegisterAndSubscribe() {
		SseResponse<ResponseData> sseResponse = registerAndSubscribe("1", "message", 1);
		sleep(3, TimeUnit.SECONDS);
		this.eventPublisher.publishEvent(SseEvent.ofData("regandsub"));
		assertSseResponse(sseResponse, new ResponseData("message", "regandsub"));
		sseResponse.eventSource().close();
	}

	@Test
	public void testRegisterAndSubscribeOnly() {
		SseResponse<ResponseData> sseResponse1 = registerAndSubscribeOnly("1", "event1", 2);
		sleep(3, TimeUnit.SECONDS);
		sseResponse1.eventSource().close();

		SseResponse<ResponseData> sseResponse2 = registerAndSubscribe("1", "event2", 2);
		sleep(3, TimeUnit.SECONDS);

		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("event1", "event2");
		assertThat(this.eventBus.hasSubscribers("event1")).isTrue();
		assertThat(this.eventBus.getSubscribers("event2")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("event1")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("event1", "event2");

		this.eventPublisher.publishEvent(SseEvent.of("event1", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("event2", "payload2"));

		assertSseResponse(sseResponse2, new ResponseData("event1", "payload1"), new ResponseData("event2", "payload2"));

		SseResponse<ResponseData> sseResponse = registerAndSubscribeOnly("1", "event3", 1);
		sleep(1, TimeUnit.SECONDS);
		this.eventPublisher.publishEvent(SseEvent.of("event1", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("event2", "payload2"));
		this.eventPublisher.publishEvent(SseEvent.of("event3", "payload3"));

		assertSseResponse(sseResponse, new ResponseData("event3", "payload3"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneEventEmptyData() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.ofEvent("eventName"));
		assertSseResponse(sseResponse, new ResponseData("eventName", ""));
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneEventAdditionalInfo() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName");
		SseEvent sseEvent = SseEvent.builder()
			.event("eventName")
			.data("the data line")
			.id("123")
			.retry(Duration.ofSeconds(1))
			.comment("the comment")
			.build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, new ResponseData("eventName", "the data line"));
		// "id:123", "retry:1000",
		// ":the comment", "data:the data line");
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneDirectEvent() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName");
		SseEvent sseEvent = SseEvent.builder().addClientId("1").event("eventName").data("payload").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload"));
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientNoEvent() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventNameSecond", "payload"));
		assertSseResponse(sseResponse);
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneDirectEventToSomebodyElse() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName");
		SseEvent sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse);
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientTwoEvents() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName", false, 2, true);
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload2"));
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));
		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientTwoDirectEvents() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName", false, 2, true);

		SseEvent sseEvent = SseEvent.builder().addClientId("1").event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addClientId("1").event("eventName").data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testOneClientOneDirectEventToHimAndOneToSomebodyElse() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName");

		SseEvent sseEvent = SseEvent.builder().addClientId("1").event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testTwoClientsOneAllEvent() throws IOException {
		SseResponse<ResponseData> sseResponse1 = registerSubscribe("1", "eventName");
		SseResponse<ResponseData> sseResponse2 = registerSubscribe("2", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload1"));
		assertSseResponse(sseResponse1, new ResponseData("eventName", "payload1"));
		assertSseResponse(sseResponse2, new ResponseData("eventName", "payload1"));

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
	}

	@Test
	public void testTwoClientsTwoAllEvent() throws IOException {
		SseResponse<ResponseData> sseResponse1 = registerSubscribe("1", "eventName", false, 2, true);
		SseResponse<ResponseData> sseResponse2 = registerSubscribe("2", "eventName", false, 2, true);
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
		SseResponse<ResponseData> sseResponse1 = registerSubscribe("1", "eventName");
		SseResponse<ResponseData> sseResponse2 = registerSubscribe("2", "eventName");

		SseEvent sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);

		sseEvent = SseEvent.builder().addClientId("2").event("eventName").data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);

		assertSseResponse(sseResponse1);
		assertSseResponse(sseResponse2, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"));

		sseResponse1.eventSource().close();
		sseResponse2.eventSource().close();
	}

	@Test
	public void testThreeClientsGroupEventToTwoOfThem() throws IOException {
		SseResponse<ResponseData> sseResponse1 = registerSubscribe("1", "eventName");
		SseResponse<ResponseData> sseResponse2 = registerSubscribe("2", "eventName");
		SseResponse<ResponseData> sseResponse3 = registerSubscribe("3", "eventName");

		SseEvent sseEvent = SseEvent.builder().addClientIds("2", "3").event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addClientIds("2", "3").event("eventName").data("payload2").build();
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
		SseResponse<ResponseData> sseResponse1 = registerSubscribe("1", "eventName");
		SseResponse<ResponseData> sseResponse2 = registerSubscribe("2", "eventName");
		SseResponse<ResponseData> sseResponse3 = registerSubscribe("3", "eventName");

		SseEvent sseEvent = SseEvent.builder()
			.addClientIds("2", "3")
			.addExcludeClientIds("2", "1")
			.event("eventName")
			.data("payload1")
			.build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder()
			.addClientIds("2", "3")
			.addExcludeClientIds("3", "2", "1")
			.event("eventName")
			.data("payload2")
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
		SseResponse<ResponseData> sseResponse1 = registerSubscribe("1", "eventName");
		SseResponse<ResponseData> sseResponse2 = registerSubscribe("2", "eventName");
		SseResponse<ResponseData> sseResponse3 = registerSubscribe("3", "eventName", 2);

		SseEvent sseEvent = SseEvent.builder().addExcludeClientId("2").event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addExcludeClientId("1").event("eventName").data("payload2").build();
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
		SseResponse<ResponseData> sseResponse1 = registerSubscribe("1", "eventName");
		SseResponse<ResponseData> sseResponse2 = registerSubscribe("2", "eventName");
		SseResponse<ResponseData> sseResponse3 = registerSubscribe("3", "eventName");

		SseEvent sseEvent = SseEvent.builder()
			.addExcludeClientIds("2", "3")
			.event("eventName")
			.data("payload1")
			.build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().addExcludeClientIds("1", "3").event("eventName").data("payload2").build();
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
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "event1");
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
	public void testReconnect() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName");
		sleep(200, TimeUnit.MILLISECONDS);
		sseResponse.eventSource().close();
		sleep(3, TimeUnit.SECONDS);

		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
		assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
		assertThat(this.eventBus.getSubscribers("eventName")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");

		SseEvent sseEvent = SseEvent.builder().event("eventName").data("payload1").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload2").build();
		this.eventPublisher.publishEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload3").build();
		this.eventPublisher.publishEvent(sseEvent);

		sleep(500, TimeUnit.MILLISECONDS);

		sseResponse = registerSubscribe("1", "eventName", 3);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"), new ResponseData("eventName", "payload3"));
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
	public void testReplayAfterReconnectWithLastEventId() {
		SseResponse<ResponseData> initialResponse = registerAndSubscribe("1", "eventName", 1);
		await().atMost(Duration.ofSeconds(5))
			.until(() -> this.eventBus.getAllClientIds().contains("1")
					&& this.eventBus.getSubscribers("eventName").contains("1"));

		this.eventPublisher.publishEvent(SseEvent.builder().event("eventName").id("1").data("payload1").build());
		assertSseResponse(initialResponse, new ResponseData("eventName", "payload1", "1"));

		sleep(200, TimeUnit.MILLISECONDS);
		this.eventPublisher.publishEvent(SseEvent.builder().event("eventName").id("2").data("payload2").build());
		this.eventPublisher.publishEvent(SseEvent.builder().event("eventName").id("3").data("payload3").build());

		sleep(500, TimeUnit.MILLISECONDS);
		initialResponse.eventSource().close();

		SseResponse<ResponseData> replayedResponse = registerAndSubscribe("1", "eventName", 2, "1");
		await().atMost(Duration.ofSeconds(5))
			.until(() -> this.eventBus.getAllClientIds().contains("1")
					&& this.eventBus.getSubscribers("eventName").contains("1"));
		assertSseResponse(replayedResponse, new ResponseData("eventName", "payload2", "2"),
				new ResponseData("eventName", "payload3", "3"));
	}

	@Test
	public void testExplicitUnregisterClearsRetainedReplayHistory() {
		SseResponse<ResponseData> initialResponse = registerAndSubscribe("1", "eventName", 1);
		await().atMost(Duration.ofSeconds(5))
			.until(() -> this.eventBus.getAllClientIds().contains("1")
					&& this.eventBus.getSubscribers("eventName").contains("1"));

		this.eventPublisher.publishEvent(SseEvent.builder().event("eventName").id("1").data("payload1").build());
		assertSseResponse(initialResponse, new ResponseData("eventName", "payload1", "1"));
		assertThat(this.replayStore.getEventsSince("1", null)).hasSize(1);

		this.eventBus.unregisterClient("1");
		assertThat(this.replayStore.getEventsSince("1", null)).isEmpty();

		SseResponse<ResponseData> replayedResponse = registerAndSubscribe("1", "eventName", 1, "0");
		assertSseResponse(replayedResponse);
	}

	@Test
	public void testExpiredReplayHistoryIsNotReplayed() {
		SseResponse<ResponseData> initialResponse = registerAndSubscribe("1", "eventName", 1);
		await().atMost(Duration.ofSeconds(5))
			.until(() -> this.eventBus.getAllClientIds().contains("1")
					&& this.eventBus.getSubscribers("eventName").contains("1"));

		this.eventPublisher.publishEvent(SseEvent.builder().event("eventName").id("1").data("payload1").build());
		assertSseResponse(initialResponse, new ResponseData("eventName", "payload1", "1"));
		assertThat(this.replayStore.getEventsSince("1", null)).hasSize(1);

		this.replayStore.purgeExpired(System.currentTimeMillis() + 1);
		assertThat(this.replayStore.getEventsSince("1", null)).isEmpty();

		SseResponse<ResponseData> replayedResponse = registerAndSubscribe("1", "eventName", 1, "0");
		assertSseResponse(replayedResponse);
	}

	@Test
	public void testClientExpiration() throws IOException {
		var response = registerSubscribe("1", "eventName", true, 1, true);
		assertThat(this.eventBus.getAllClientIds()).hasSize(1);
		assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
		assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
		assertThat(this.eventBus.getSubscribers("eventName")).containsOnly("1");
		assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(1);
		assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");
		response.eventSource().close();

		await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
			assertThat(this.eventBus.getAllClientIds()).hasSize(0);
			assertThat(this.eventBus.getAllEvents()).isEmpty();
			assertThat(this.eventBus.hasSubscribers("eventName")).isFalse();
			assertThat(this.eventBus.getSubscribers("eventName")).isEmpty();
			assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(0);
			assertThat(this.eventBus.getAllSubscriptions()).isEmpty();
		});

		response.eventSource().close();
	}

	@Test
	public void testMany() throws IOException {
		List<SseResponse<ResponseData>> responses = new ArrayList<>();
		for (int i = 0; i < 120; i++) {
			responses.add(registerAndSubscribe(String.valueOf(i), "eventName", 1));
		}

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			assertThat(this.eventBus.getAllClientIds()).hasSize(120);
			assertThat(this.eventBus.getAllEvents()).containsOnly("eventName");
			assertThat(this.eventBus.hasSubscribers("eventName")).isTrue();
			assertThat(this.eventBus.getSubscribers("eventName")).hasSize(120);
			assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(120);
			assertThat(this.eventBus.getAllSubscriptions()).containsOnlyKeys("eventName");
		});

		this.eventPublisher.publishEvent(SseEvent.of("eventName", "payload"));
		for (int i = 0; i < 100; i++) {
			assertSseResponse(responses.get(i), new ResponseData("eventName", "payload"));
		}

		for (SseResponse<ResponseData> response : responses) {
			response.eventSource().close();
		}

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			for (String clientId : this.eventBus.getAllClientIds()) {
				this.eventBus.unregisterClient(clientId);
			}
			assertThat(this.eventBus.getAllClientIds()).hasSize(0);
			assertThat(this.eventBus.getAllEvents()).isEmpty();
			assertThat(this.eventBus.hasSubscribers("eventName")).isFalse();
			assertThat(this.eventBus.getSubscribers("eventName")).isEmpty();
			assertThat(this.eventBus.countSubscribers("eventName")).isEqualTo(0);
			assertThat(this.eventBus.getAllSubscriptions()).isEmpty();
		});
	}

	@Test
	public void testJsonConverter() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "to1");
		TestObject1 to1 = new TestObject1(101L, "john doe");

		this.eventPublisher.publishEvent(SseEvent.of("to1", to1));
		assertSseResponse(sseResponse, new ResponseData("to1", "{\"id\":101,\"name\":\"john doe\"}"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testCustomConverter() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("2", "message");
		TestObject2 to2 = new TestObject2(999L, "sample inc.");

		this.eventPublisher.publishEvent(SseEvent.ofData(to2));
		assertSseResponse(sseResponse, new ResponseData("message", "999,sample inc."));

		sseResponse.eventSource().close();
	}

	@Test
	public void testJsonViewNoView() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "jsonView1");
		TestObject3 to3 = new TestObject3();
		to3.setPrivateData(23);
		to3.setPublicInfo("this is public");
		to3.setUuid("abc");

		this.eventPublisher.publishEvent(SseEvent.of("jsonView1", to3));
		assertSseResponse(sseResponse, new ResponseData("jsonView1",
				"{\"privateData\":23,\"publicInfo\":\"this is public\",\"uuid\":\"abc\"}"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testJsonViewPublicView() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "jsonView1");
		TestObject3 to3 = new TestObject3();
		to3.setPrivateData(23);
		to3.setPublicInfo("this is public");
		to3.setUuid("abc");

		this.eventPublisher
			.publishEvent(SseEvent.builder().event("jsonView1").data(to3).jsonView(JsonViews.PUBLIC.class).build());

		assertSseResponse(sseResponse,
				new ResponseData("jsonView1", "{\"publicInfo\":\"this is public\",\"uuid\":\"abc\"}"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testJsonViewPrivateView() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "jsonView1");
		TestObject3 to3 = new TestObject3();
		to3.setPrivateData(23);
		to3.setPublicInfo("this is public");
		to3.setUuid("abc");

		this.eventPublisher
			.publishEvent(SseEvent.builder().event("jsonView1").data(to3).jsonView(JsonViews.PRIVATE.class).build());
		assertSseResponse(sseResponse, new ResponseData("jsonView1",
				"{\"privateData\":23,\"publicInfo\":\"this is public\",\"uuid\":\"abc\"}"));

		sseResponse.eventSource().close();
	}

	@Test
	public void testMultiline() throws IOException {
		SseResponse<ResponseData> sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.of("eventName", "1. line\n2. line"));
		assertSseResponse(sseResponse, new ResponseData("eventName", "1. line\n2. line"));

		sseResponse.eventSource().close();
	}

	private String testUrl(String path) {
		return "http://localhost:" + this.port + path;
	}

	private static void assertSseResponse(SseResponse<ResponseData> response, ResponseData... expected) {
		try {
			List<ResponseData> rds;
			try {
				rds = response.dataFuture().get(10, TimeUnit.SECONDS);
			}
			catch (TimeoutException e) {
				rds = List.of();
			}
			assertThat(rds).hasSize(expected.length);
			for (int i = 0; i < expected.length; i++) {
				assertThat(rds.get(i).event()).isEqualTo(expected[i].event());
				assertThat(rds.get(i).data()).isEqualTo(expected[i].data());
				if (expected[i].id() != null) {
					assertThat(rds.get(i).id()).isEqualTo(expected[i].id());
				}
			}
			response.eventSource().close();
		}
		catch (InterruptedException | java.util.concurrent.ExecutionException e) {
			fail(e);
		}
	}

	private SseResponse<ResponseData> registerSubscribe(String clientId, String eventName) throws IOException {
		return registerSubscribe(clientId, eventName, false, 1, true);
	}

	private SseResponse<ResponseData> registerSubscribe(String clientId, String eventName, int expectedNoOfData)
			throws IOException {
		return registerSubscribe(clientId, eventName, false, expectedNoOfData, true);
	}

	private SseResponse<ResponseData> registerSubscribe(String clientId, String eventName, boolean shortTimeout,
			int expectedNoOfData, boolean sleep) throws IOException {

		SseResponse<ResponseData> response = SseTestClientSupport.open(testUrl("/register/" + clientId),
				expectedNoOfData, null, (event, messageEvent) -> new ResponseData(event, messageEvent.getData(),
						messageEvent.getLastEventId()));
		SseTestClientSupport.awaitClientRegistered(this.eventBus, clientId);
		SseTestClientSupport.invokeGet(testUrl("/subscribe/" + clientId + "/" + eventName), shortTimeout);
		SseTestClientSupport.awaitClientSubscribed(this.eventBus, clientId, eventName);

		if (sleep) {
			SseTestClientSupport.settleSubscription();
		}

		return response;
	}

	private void subscribe(String clientId, String eventName) throws IOException {
		SseTestClientSupport.invokeGet(testUrl("/subscribe/" + clientId + "/" + eventName), false);
	}

	private SseResponse<ResponseData> registerAndSubscribe(String clientId, String eventName, int expectedNoOfData) {
		return registerAndSubscribe(clientId, eventName, expectedNoOfData, null);
	}

	private SseResponse<ResponseData> registerAndSubscribe(String clientId, String eventName, int expectedNoOfData,
			@Nullable String lastEventId) {
		return SseTestClientSupport.open(testUrl("/register/" + clientId + "/" + eventName), expectedNoOfData,
				lastEventId, (event, messageEvent) -> new ResponseData(event, messageEvent.getData(),
						messageEvent.getLastEventId()));
	}

	private SseResponse<ResponseData> registerAndSubscribeOnly(String clientId, String eventName,
			int expectedNoOfData) {
		SseResponse<ResponseData> response = SseTestClientSupport
			.open(testUrl("/registerOnly/" + clientId + "/" + eventName), expectedNoOfData, null, (event,
					messageEvent) -> new ResponseData(event, messageEvent.getData(), messageEvent.getLastEventId()));
		SseTestClientSupport.awaitClientSubscribed(this.eventBus, clientId, eventName);
		return response;
	}

}
