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
package ch.rasc.sse.eventbus.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/**
 * Add this annotation to any {@code @Configuration} class to enable the SseEventBus.
 *
 * <pre class="code">
 * &#064;Configuration
 * &#064;EnableSseEventBus
 * public class MyAppConfig {
 * }
 * </pre>
 * <p>
 * To customise the library a configuration class can implement the interface
 * {@link SseEventBusConfigurer} and override certain methods.
 *
 * <pre class="code">
 * &#064;Configuration
 * &#064;EnableSseEventBus
 * public class Config implements SseEventBusConfigurer {
 * 	&#064;Override
 * 	public int clientExpirationInSeconds() {
 * 		return 180;
 * 	}
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import(DefaultSseEventBusConfiguration.class)
public @interface EnableSseEventBus {

	// nothing here

}