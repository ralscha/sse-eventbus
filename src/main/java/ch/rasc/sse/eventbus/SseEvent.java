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

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

@Value.Style(depluralize = true, visibility = ImplementationVisibility.PACKAGE)
@Value.Immutable
public interface SseEvent {

	public static String DEFAULT_EVENT = "message";

	Set<String> clientIds();

	/**
	 * Is ignored when clientIds is not empty
	 */
	Set<String> excludeClientIds();

	Optional<Class<?>> jsonView();

	@Value.Default
	default String event() {
		return DEFAULT_EVENT;
	}

	Object data();

	Optional<Duration> retry();

	Optional<String> id();

	Optional<String> comment();

	/**
	 * Creates a SseEvent that just contains the data. The data will be converted when
	 * it's not a String instance.
	 */
	public static SseEvent ofData(Object data) {
		return SseEvent.builder().data(data).build();
	}

	/**
	 * Creates a SseEvent that contains an event and an empty string
	 */
	public static SseEvent ofEvent(String event) {
		return SseEvent.builder().event(event).data("").build();
	}

	/**
	 * Creates a SseEvent that just contains an event and data. The data will be converted
	 * when it's not a String instance
	 */
	public static SseEvent of(String event, Object data) {
		return SseEvent.builder().event(event).data(data).build();
	}

	public static Builder builder() {
		return ImmutableSseEvent.builder();
	}

	public interface Builder {

	    Builder addClientId(String element);

	    Builder addClientIds(String... elements);

	    Builder clientIds(Iterable<String> elements);

	    Builder addAllClientIds(Iterable<String> elements);

	    Builder addExcludeClientId(String element);

	    Builder addExcludeClientIds(String... elements);

	    Builder excludeClientIds(Iterable<String> elements);

	    Builder addAllExcludeClientIds(Iterable<String> elements);

	    Builder jsonView(Class<?> jsonView);

		Builder event(String event);

		Builder data(Object data);

		Builder retry(Duration retry);

		Builder id(String id);

		Builder comment(String comment);

		SseEvent build();
	}

}
