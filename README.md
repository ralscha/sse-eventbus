[![Test Status](https://github.com/ralscha/sse-eventbus/actions/workflows/maven.yml/badge.svg)](https://github.com/ralscha/sse-eventbus/actions/workflows/maven.yml)


sse-eventbus is a Java library that sits on top of [Spring's Server-Sent Event support](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#mvc-ann-async-sse).
It keeps track of connected clients and broadcasts events to them.

## Usage


### Setup server

Enable support by adding ```@EnableSseEventBus``` to a Spring application.
```
@SpringBootApplication
@EnableSseEventBus
public class Application {
  ...
}
```

Create a controller that handles the SSE requests and returns a [SseEmitter](http://docs.spring.io/spring/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html).
Each client has to provide an id that identifies this client. 
The controller then registers the client in the eventBus with the method ```registerClient``` and
subscribes it to events with the ```subscribe``` method.
The SseEventBus class contains a convenient method ```createSseEmitter``` that does all of this. 

```
@Controller
public class SseController {
  private final SseEventBus eventBus;
  public SseController(SseEventBus eventBus) {
    this.eventBus = eventBus;
  }

  @GetMapping("/register/{id}")
  public SseEmitter register(@PathVariable("id") String id) {
    SseEmitter emitter = new SseEmitter(180_000L);
    emitter.onTimeout(emitter::complete);
    this.eventBus.registerClient(id, emitter);
    this.eventBus.subscribe(id, SseEvent.DEFAULT_EVENT);
    return emitter;

    //OR
    //return this.eventBus.createSseEmitter(id, SseEvent.DEFAULT_EVENT)
  }
}
```

### Replay and resume

Replay is optional and is only enabled when a `ReplayStore` is configured through `SseEventBusConfigurer.replayStore()`.
Only events with an explicit SSE id are retained and eligible for replay.

```
@SpringBootApplication
@EnableSseEventBus
public class Application implements SseEventBusConfigurer {

  @Bean
  public ReplayStore replayStoreBean() {
    return new InMemoryReplayStore();
  }

  @Override
  public ReplayStore replayStore() {
    return replayStoreBean();
  }

  @Override
  public Duration replayRetention() {
    return Duration.ofMinutes(10);
  }
}
```

When replay is enabled, a controller can read the `Last-Event-ID` header and pass it to the replay-aware registration API.

```
@Controller
public class SseController {
  private final SseEventBus eventBus;

  public SseController(SseEventBus eventBus) {
    this.eventBus = eventBus;
  }

  @GetMapping("/register/{id}/{event}")
  public SseEmitter register(@PathVariable("id") String id,
      @PathVariable("event") String event,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

    if (lastEventId != null && !lastEventId.isEmpty()) {
      return this.eventBus.createReplayableSseEmitter(id, 180_000L, false, false,
          lastEventId, event.split(","));
    }

    return this.eventBus.createSseEmitter(id, 180_000L, event.split(","));
  }
}
```

Published events must carry ids to be replayable.

```
this.eventBus.handleEvent(SseEvent.builder()
    .event("orders")
    .id("order-4711")
    .data(orderPayload)
    .build());
```

Notes:
* Replay is in-memory only when using `InMemoryReplayStore`; retained events are lost on restart.
* `unregisterClient` clears retained replay history for that client.
* Events without `id(...)`, or with an empty id, are delivered live only and are never replayed.
* Retained events older than `replayRetention()` are removed by the replay cleanup job.
* `InMemoryReplayStore` retains at most 10,000 events per client. Use `new InMemoryReplayStore(2_000)` to choose another positive limit. The oldest events are evicted first, independently of time-based retention.
* If the last event id has expired or been evicted, all remaining history is replayed. Use unique ids and make client handlers tolerate duplicates; if an id occurs more than once, replay resumes after its last occurrence.

### Heartbeats

Heartbeat comments are disabled by default. To keep idle SSE connections alive through proxies or load balancers, override `heartbeatInterval()` and optionally customize the emitted comment text with `heartbeatComment()`.

```
@SpringBootApplication
@EnableSseEventBus
public class Application implements SseEventBusConfigurer {

  @Override
  public Duration heartbeatInterval() {
    return Duration.ofSeconds(30);
  }

  @Override
  public String heartbeatComment() {
    return "keep-alive";
  }
}
```

### Setup client

On the client side an application interacts with the [EventSource](https://developer.mozilla.org/en/docs/Web/API/EventSource) object.
This object is responsible for sending the SSE request to the server and calling listeners
the application registered on this object. 
As mentioned before the client has to send an id that should be unique among all the clients. 
A simple way is to use the browser's built-in Web Crypto API and call `crypto.randomUUID()`.

```
const uuid = crypto.randomUUID();
const eventSource = new EventSource(`/register/${uuid}`);
eventSource.addEventListener('message', response => {
  //handle the response from the server
  //response.data contains the data line 
}, false);
```


### Broadcasting events

To broadcast an event to all connected clients a Spring application can either inject the SseEventBus 
singleton and call the ```handleEvent``` method 

```
@Service
public class DataEmitterService {
  private final SseEventBus eventBus;
  public DataEmitterService(SseEventBus eventBus) {
    this.eventBus = eventBus;
  }

  public void broadcastEvent() {
    this.eventBus.handleEvent(SseEvent.ofData("some useful data"));
  }

}

```

or use Spring's event infrastructure and publish a SseEvent

```
@Service
public class DataEmitterService {
  private final ApplicationEventPublisher eventPublisher;
  // OR: private final ApplicationContext ctx;
  // ApplicationContext implements the ApplicationEventPublisher interface
  public DataEmitterService(ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  public void broadcastEvent() {
    this.eventPublisher.publishEvent(SseEvent.ofData("some useful data"));
  }
}
```


### Delivery queues and retries

The default send queue holds 10,000 events and blocks publishers when full. Failed sends use a separate bounded retry queue with exponential backoff. When that queue is full, the failed event is dropped from retry delivery and logged, and `SseEventBusListener.afterEventDropped(ClientEvent)` is called. Replay history remains available when configured.

Override `sendWorkerCount()` to send to multiple clients concurrently. The default scheduler reserves two additional threads for retries, heartbeats, and cleanup. A custom scheduler must also leave threads available for these jobs. Multiple workers and retries can change delivery order; keep one worker when normal queue order matters.

With `taskScheduler()` returning `null`, sends are synchronous and retries, heartbeats, client expiration, and replay cleanup jobs are disabled.

`createSseEmitter(..., unsubscribe = true, ...)` replaces subscriptions, including clearing all subscriptions when no events are supplied. Explicit unregister removes subscriptions, pending sends, retries, and replay history.

### Event metadata

Event names cannot contain CR or LF. Event ids cannot contain CR, LF, or NUL; an empty id is allowed to reset the client's last event id. Retry durations must be nonnegative and fit in milliseconds. Invalid metadata is rejected when building an event, before it enters delivery queues. String data and comments support LF, CRLF, and CR line endings according to the [SSE stream format](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream).

## Maven
The library is hosted on the Central Maven Repository
```
  <dependency>
    <groupId>ch.rasc</groupId>
    <artifactId>sse-eventbus</artifactId>
    <version>3.1.0</version>
  </dependency>  
```

## Null Safety

The public API uses JSpecify annotations with package-level `@NullMarked` defaults.
Unless an API element is annotated with `@Nullable`, values should be treated as non-null.

Build-time nullness checking is enforced with Error Prone and the NullAway plugin.

Enable the stricter Error Prone and NullAway checks with `-Perror-prone` when Maven
runs on JDK 21 or newer. Builds running on JDK 17 compile and test without this
profile because recent Error Prone releases require a newer runtime than Java 17.
CI runs this profile on JDK 21 and newer for both pushes and pull requests.

Run `./mvnw -B -ntp verify` to compile, run unit and HTTP integration tests, build the
jar, and check license headers. On Windows, use `./mvnw.cmd` instead. Use
`./mvnw -B -ntp -Perror-prone verify` for static analysis as well.

Nullable contracts are declared explicitly for cases such as:

* `SseEvent.data()` and factory methods that allow events without data
* `DataObjectConverter.convert(...)` implementations that may return `null`
* replay registration methods that accept a missing `Last-Event-ID`
* optional configuration hooks such as `SseEventBusConfigurer.taskScheduler()` and `replayStore()`

## Observability

The library emits Micrometer observations that align with the Spring 7 / Spring Boot 4 observability model.
If your application provides an `ObservationRegistry` bean, `SseEventBus` automatically publishes observations for:

* client registration and unregister
* event publication and fan-out
* per-client send attempts
* replay delivery

The default observation name is `sse.eventbus`.
Low-cardinality tags include:

* `operation`
* `outcome`
* `mode`
* `replay`

High-cardinality tags include event and client identifiers, plus delivery metadata such as retry attempt.

To customize names or tags, register your own `SseEventBusObservationConvention` bean.

```
@Configuration
class ObservabilityConfiguration {

  @Bean
  SseEventBusObservationConvention sseEventBusObservationConvention() {
    return new DefaultSseEventBusObservationConvention() {
      @Override
      public KeyValues getLowCardinalityKeyValues(SseEventBusObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and("component", "orders-sse");
      }
    };
  }
}
```

## Demo
Simple demo application:    
https://github.com/ralscha/sse-eventbus-demo

Distributed demo application:    
https://github.com/ralscha/sse-eventbus-demo-distributed

Ionic Demo Chat application:    
https://github.com/ralscha/sse-eventbus-demo-chat

Kotlin with CoroutineScope example:    
[KOTLIN_COROUTINES_EXAMPLE.md](KOTLIN_COROUTINES_EXAMPLE.md)


## More information
Articles about Server-Sent Events    
* https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events
* https://hpbn.co/server-sent-events-sse/   
* https://web.dev/articles/eventsource-basics


## Changelog
See [CHANGELOG.md](CHANGELOG.md) for release history.


## License
Code released under [the Apache license](http://www.apache.org/licenses/).
