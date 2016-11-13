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

import java.util.Collections;
import java.util.Set;

import javax.annotation.Nullable;

import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

@Value.Style(visibility = ImplementationVisibility.PACKAGE)
@Value.Immutable(copy = false, builder = false)
public interface SseEvent {

	@Value.Parameter
	@Nullable
	Set<String> clientIds();

	@Value.Parameter
	String name();

	@Value.Parameter
	@Nullable
	String data();

	/**
	 * true: combine data with previous unsent messages false: overwrite previous unsent
	 * messages
	 */
	@Value.Parameter
	boolean combine();

	public static SseEvent all(String name) {
		return ImmutableSseEvent.of(Collections.emptySet(), name, "", false);
	}

	public static SseEvent all(String name, String data) {
		return ImmutableSseEvent.of(Collections.emptySet(), name, nullToEmpty(data),
				false);
	}

	public static SseEvent all(String name, String data, boolean combine) {
		return ImmutableSseEvent.of(Collections.emptySet(), name, nullToEmpty(data),
				combine);
	}

	public static SseEvent one(String clientId, String name) {
		return ImmutableSseEvent.of(Collections.singleton(clientId), name, "", false);
	}

	public static SseEvent one(String clientId, String name, String data) {
		return ImmutableSseEvent.of(Collections.singleton(clientId), name,
				nullToEmpty(data), false);
	}

	public static SseEvent one(String clientId, String name, String data,
			boolean combine) {
		return ImmutableSseEvent.of(Collections.singleton(clientId), name,
				nullToEmpty(data), combine);
	}

	public static SseEvent group(Set<String> clientIds, String name) {
		return ImmutableSseEvent.of(clientIds, name, "", false);
	}

	public static SseEvent group(Set<String> clientIds, String name, String data) {
		return ImmutableSseEvent.of(clientIds, name, nullToEmpty(data), false);
	}

	public static SseEvent group(Set<String> clientIds, String name, String data,
			boolean combine) {
		return ImmutableSseEvent.of(clientIds, name, nullToEmpty(data), combine);
	}

	static String nullToEmpty(String data) {
		if (data == null) {
			return "";
		}
		return data;
	}
}
