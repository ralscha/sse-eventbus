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

/**
 * Callback for slow client events. Invoked when a client's bounded send buffer is
 * full, either because events were dropped or the client was disconnected.
 * <p>
 * Applications can use this to degrade event frequency for the affected client, send
 * alerts, or throttle publishing.
 */
@FunctionalInterface
public interface SlowClientListener {

	/**
	 * Called when a client has been detected as slow.
	 * @param event details about the slow client and the applied policy
	 */
	void onSlowClient(SlowClientEvent event);

}
