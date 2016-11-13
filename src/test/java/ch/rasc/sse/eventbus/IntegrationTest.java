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

import java.io.IOException;
import java.util.concurrent.TimeUnit;

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

@SuppressWarnings("resource")
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		classes = TestDefaultConfiguration.class)
public class IntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Test
	public void testOneClientOneEvent() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.all("eventName", "payload"));
		assertSseResponse(sseResponse, "event:eventName", "data:payload");
	}

	@Test
	public void testOneClientNoEvent() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.all("eventNameSecond", "payload"));
		assertSseResponse(sseResponse, "");
	}

	@Test
	public void testOneClientTwoEvents() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.all("eventName", "payload1"));
		this.eventPublisher.publishEvent(SseEvent.all("eventName", "payload2"));
		assertSseResponse(sseResponse, "event:eventName", "data:payload2");
	}

	@Test
	public void testOneClientTwoEventsCombine() throws IOException {
		Response sseResponse = registerSubscribe("1", "eventName");
		this.eventPublisher.publishEvent(SseEvent.all("eventName", "payload1", true));
		this.eventPublisher.publishEvent(SseEvent.all("eventName", "payload2", true));
		assertSseResponse(sseResponse, "event:eventName", "data:payload1",
				"data:payload2");
	}

	private String testUrl(String path) {
		return "http://localhost:" + this.port + path;
	}

	private static OkHttpClient createHttpClient() {
		return new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
				.writeTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
				.build();
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
			throw new RuntimeException(e);
		}
	}

	private Response registerSubscribe(String clientId, String eventName)
			throws IOException {
		OkHttpClient client = createHttpClient();
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

}
