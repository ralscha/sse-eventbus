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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
		if (!this.eventBus.getAllClientIds().isEmpty()) {
			await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
				for (String clientId : this.eventBus.getAllClientIds()) {
					this.eventBus.unregisterClient(clientId);
				}
				assertThat(this.eventBus.getAllClientIds()).isEmpty();
			});
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
		sleep(1, TimeUnit.SECONDS);

		assertThat(this.testListener.getAfterEventQueuedFirst()).isEmpty();
		assertThat(this.testListener.getAfterEventSentOk()).isEmpty();
		assertThat(this.testListener.getAfterEventQueued()).isEmpty();
		assertThat(this.testListener.getAfterEventSentFail()).isEmpty();
		assertThat(this.testListener.getAfterClientsUnregistered()).isEmpty();
	}

	@Test
	public void testClientExpiration() throws IOException {
		var response = registerSubscribe("1", "eventName", true, 1);
		response.eventSource().close();

		await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
			assertThat(this.testListener.getAfterEventQueuedFirst()).isEmpty();
			assertThat(this.testListener.getAfterEventSentOk()).isEmpty();
			assertThat(this.testListener.getAfterEventQueued()).isEmpty();
			assertThat(this.testListener.getAfterEventSentFail()).isEmpty();
			assertThat(this.testListener.getAfterClientsUnregistered()).hasSize(1);
			assertThat(this.testListener.getAfterClientsUnregistered().get(0)).isEqualTo("1");
		});
	}

	@Test
	public void testReconnect() throws IOException {
		var sseResponse = registerSubscribe("1", "eventName", 3);
		sleep(200, TimeUnit.MILLISECONDS);
		sseResponse.eventSource().close();
		sleep(3, TimeUnit.SECONDS);

		SseEvent sseEvent = SseEvent.builder().event("eventName").data("payload1").build();
		this.eventBus.handleEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload2").build();
		this.eventBus.handleEvent(sseEvent);
		sseEvent = SseEvent.builder().event("eventName").data("payload3").build();
		this.eventBus.handleEvent(sseEvent);

		await().atMost(Duration.ofSeconds(5))
			.until(() -> this.testListener.getAfterEventSentFail().size() >= 3);

		sseResponse = registerSubscribe("1", "eventName", 3);
		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.testListener.getAfterEventSentOk().size() >= 3);
		assertSseResponse(sseResponse, new ResponseData("eventName", "payload1"),
				new ResponseData("eventName", "payload2"), new ResponseData("eventName", "payload3"));

		assertThat(this.testListener.getAfterEventQueuedFirst()).hasSize(3);
		assertThat(this.testListener.getAfterEventSentOk()).hasSize(3);
		assertThat(this.testListener.getAfterEventQueued()).hasSizeGreaterThanOrEqualTo(3);
		assertThat(this.testListener.getAfterEventSentFail()).hasSizeGreaterThanOrEqualTo(3);
		assertThat(this.testListener.getAfterClientsUnregistered()).isEmpty();

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
			}
			response.eventSource().close();
		}
		catch (InterruptedException | ExecutionException e) {
			fail(e);
		}
	}

	private SseResponse<ResponseData> registerSubscribe(String clientId, String eventName, int expectedNoOfData)
			throws IOException {
		return registerSubscribe(clientId, eventName, false, expectedNoOfData);
	}

	private SseResponse<ResponseData> registerSubscribe(String clientId, String eventName, boolean shortTimeout,
			int expectedNoOfData) throws IOException {

		SseResponse<ResponseData> response = SseTestClientSupport.open(testUrl("/register/" + clientId),
				expectedNoOfData, null, (event, messageEvent) -> new ResponseData(event, messageEvent.getData()));
		SseTestClientSupport.awaitClientRegistered(this.eventBus, clientId);
		SseTestClientSupport.invokeGet(testUrl("/subscribe/" + clientId + "/" + eventName), shortTimeout);
		SseTestClientSupport.awaitClientSubscribed(this.eventBus, clientId, eventName);
		SseTestClientSupport.settleSubscription();
		return response;
	}

}
