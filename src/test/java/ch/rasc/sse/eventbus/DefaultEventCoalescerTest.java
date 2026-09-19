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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultEventCoalescerTest {

	private final Client client = new Client("client", new SseEmitter(), false);

	private final EventCoalescer coalescer = new DefaultEventCoalescer();

	@ParameterizedTest
	@ValueSource(strings = { "\r", "\r\n", "\n" })
	void preservesTrailingLineBreakWhenJoiningPayloads(String lineEnding) {
		ClientEvent first = new ClientEvent(this.client, SseEvent.ofData("one" + lineEnding), null);
		ClientEvent second = new ClientEvent(this.client, SseEvent.ofData("two"), null);
		ClientEvent merged = Objects.requireNonNull(this.coalescer.coalesce(first, second));
		assertThat(wire(merged)).isEqualTo("data:one\ndata:\ndata:two\n\n");
	}

	@Test
	void usesConvertedValuesAndPreservesClientAndEventName() {
		ClientEvent first = new ClientEvent(this.client, SseEvent.of("tokens", 1), "converted\r");
		ClientEvent second = new ClientEvent(this.client, SseEvent.of("tokens", 2), "two\r\nthree");
		ClientEvent merged = Objects.requireNonNull(this.coalescer.coalesce(first, second));
		assertThat(merged.getClient()).isSameAs(this.client);
		assertThat(wire(merged)).isEqualTo("event:tokens\ndata:converted\ndata:\ndata:two\ndata:three\n\n");
	}

	@Test
	void refusesMetadataDifferentNamesAndUnconvertedObjects() {
		ClientEvent plain = new ClientEvent(this.client, SseEvent.ofData("plain"), null);
		List<SseEvent> separate = List.of(SseEvent.builder().id("1").data("id").build(),
				SseEvent.builder().id("").data("reset").build(),
				SseEvent.builder().retry(Duration.ZERO).data("retry").build(),
				SseEvent.builder().comment("").data("comment").build(),
				SseEvent.builder().jsonView(String.class).data("view").build(), SseEvent.of("other", "data"),
				SseEvent.ofData(123), SseEvent.ofData(null));
		for (SseEvent event : separate) {
			ClientEvent other = new ClientEvent(this.client, event, null);
			assertThat(this.coalescer.coalesce(plain, other)).isNull();
			assertThat(this.coalescer.coalesce(other, plain)).isNull();
		}
	}

	@Test
	void preservesEmptyPayloads() {
		ClientEvent empty = new ClientEvent(this.client, SseEvent.ofData(""), null);
		ClientEvent merged = Objects.requireNonNull(this.coalescer.coalesce(empty, empty));
		assertThat(wire(merged)).isEqualTo("data:\ndata:\n\n");
	}

	private static String wire(ClientEvent event) {
		return event.createSseEventBuilder()
			.build()
			.stream()
			.map(data -> data.getData().toString())
			.collect(Collectors.joining());
	}

}
