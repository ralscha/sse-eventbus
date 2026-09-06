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
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ClientEventTest {

	@Test
	void encodesAllSseLineEndingsInStringData() {
		String wire = wire(SseEvent.ofData("first\r\nsecond\rthird\n"), null);
		assertThat(wire).isEqualTo("data:first\ndata:second\ndata:third\ndata:\n\n");
	}

	@Test
	void encodesConvertedDataAndMultilineCommentsWithoutInjectingFields() {
		SseEvent event = SseEvent.builder().data(123).comment("first\r\nid:injected\rdata:injected\n").build();
		assertThat(wire(event, "converted\rvalue"))
			.isEqualTo(":first\n:id:injected\n:data:injected\n:\ndata:converted\ndata:value\n\n");
	}

	@Test
	void rejectsInvalidMetadataAndAllowsEmptyIdAndZeroRetry() {
		for (String invalid : new String[] { "bad\rid", "bad\nid", "bad\0id" }) {
			assertThatIllegalArgumentException().isThrownBy(() -> SseEvent.builder().id(invalid).build());
		}
		assertThatIllegalArgumentException().isThrownBy(() -> SseEvent.ofEvent("orders\nother"));
		assertThatIllegalArgumentException().isThrownBy(() -> SseEvent.ofEvent("orders\rother"));
		assertThatIllegalArgumentException().isThrownBy(() -> SseEvent.builder().retry(Duration.ofNanos(-1)).build());
		assertThatIllegalArgumentException()
			.isThrownBy(() -> SseEvent.builder().retry(Duration.ofSeconds(Long.MAX_VALUE)).build());
		assertThat(wire(SseEvent.builder().id("").retry(Duration.ZERO).data("").build(), null))
			.isEqualTo("id:\nretry:0\ndata:\n\n");
	}

	private static String wire(SseEvent event, @Nullable String converted) {
		return new ClientEvent(new Client("client", new SseEmitter(), false), event, converted).createSseEventBuilder()
			.build()
			.stream()
			.map(data -> data.getData().toString())
			.collect(Collectors.joining());
	}

}
