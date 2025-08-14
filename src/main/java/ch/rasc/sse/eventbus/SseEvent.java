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
import java.util.Optional;
import java.util.Set;

import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

/**
 * Represents an event that is sent to the client.
 * <p>
 * This is an immutable class. Use the {@link #builder()} to create a new instance.
 */
@Value.Style(depluralize = true, visibility = ImplementationVisibility.PACKAGE,
		overshadowImplementation = true)
@Value.Immutable
public interface SseEvent {

	/**
	 * The default event name.
	 */
	String DEFAULT_EVENT = "message";

	/**
	 * A set of client IDs to which this event should be sent. If empty, the event is
	 * sent to all subscribed clients.
	 * @return a set of client IDs
	 */
	Set<String> clientIds();

	/**
	 * A set of client IDs to which this event should not be sent. This is ignored if
	 * {@link #clientIds()} is not empty.
	 * @return a set of client IDs to exclude
	 */
	Set<String> excludeClientIds();

	/**
	 * The JSON view class to use for serialization.
	 * @return the JSON view class
	 */
	Optional<Class<?>> jsonView();

	/**
	 * The name of the event. Defaults to {@link #DEFAULT_EVENT}.
	 * @return the event name
	 */
	@Value.Default
	default String event() {
		return DEFAULT_EVENT;
	}

	/**
	 * The data of the event. Can be any object. The
	 * {@link ch.rasc.sse.eventbus.DataObjectConverter} is responsible for converting
	 * the object to a String.
	 * @return the event data
	 */
	Object data();

	/**
	 * The retry time in milliseconds.
	 * @return the retry time
	 */
	Optional<Duration> retry();

	/**
	 * The event ID.
	 * @return the event ID
	 */
	Optional<String> id();

	/**
	 * A comment for the event.
	 * @return the comment
	 */
	Optional<String> comment();

	/**
	 * Creates a SseEvent that just contains the data. The data will be converted when
	 * it's not a String instance. The event name will be the default 'message'.
	 * @param data the data to send
	 * @return a new SseEvent instance
	 */
	static SseEvent ofData(Object data) {
		return SseEvent.builder().data(data).build();
	}

	/**
	 * Creates a SseEvent that contains an event and an empty string as data.
	 * @param event the event name
	 * @return a new SseEvent instance
	 */
	static SseEvent ofEvent(String event) {
		return SseEvent.builder().event(event).data("").build();
	}

	/**
	 * Creates a SseEvent that just contains an event and data. The data will be converted
	 * when it's not a String instance.
	 * @param event the event name
	 * @param data the data to send
	 * @return a new SseEvent instance
	 */
	static SseEvent of(String event, Object data) {
		return SseEvent.builder().event(event).data(data).build();
	}

	/**
	 * Creates a new builder for this class.
	 * @return a new builder
	 */
	static Builder builder() {
		return new Builder();
	}

	/**
	 * The builder for {@link SseEvent}.
	 */
	public static final class Builder extends ImmutableSseEvent.Builder {
		// nothing here
	}

}
