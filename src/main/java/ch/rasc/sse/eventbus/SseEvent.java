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

import java.util.Set;

import javax.annotation.Nullable;

import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

@Value.Style(depluralize = true, visibility = ImplementationVisibility.PACKAGE)
@Value.Immutable
public interface SseEvent {

	public static String DEFAULT_EVENT = "message";

	Set<String> clientIds();

	@Value.Default
	default String event() {
		return DEFAULT_EVENT;
	}

	@Value.Default
	default String data() {
		return "";
	}

	@Nullable
	Object dataObject();

	@Nullable
	Long retry();

	@Nullable
	String id();

	@Nullable
	String comment();

	/**
	 * Creates a SseEvent that just contains the data
	 */
	public static SseEvent ofData(String data) {
		return SseEvent.builder().data(data).build();
	}

	/**
	 * Creates a SseEvent that just contains the data. The object will be converted into a
	 * string by a converter
	 */
	public static SseEvent ofDataObject(Object dataObject) {
		return SseEvent.builder().dataObject(dataObject).build();
	}

	/**
	 * Creates a SseEvent that contains an event and an empty data
	 */
	public static SseEvent ofEvent(String event) {
		return SseEvent.builder().event(event).build();
	}

	/**
	 * Creates a SseEvent that just contains an event and data
	 */
	public static SseEvent of(String event, String data) {
		return SseEvent.builder().event(event).data(data).build();
	}

	/**
	 * Creates a SseEvent that just contains an event and a object. The object will be
	 * converted into a string by a converter
	 */
	public static SseEvent of(String event, Object dataObject) {
		return SseEvent.builder().event(event).dataObject(dataObject).build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder extends ImmutableSseEvent.Builder {
		// nothing here
	}

}
