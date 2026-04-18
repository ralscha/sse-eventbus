[![Test Status](https://github.com/ralscha/sse-eventbus/actions/workflows/maven.yml/badge.svg)](https://github.com/ralscha/sse-eventbus/actions/workflows/maven.yml)


sse-eventbus is a Java library that sits on top of [Spring's Sever-Sent Event support](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#mvc-ann-async-sse).   
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
* Events without `id(...)` are delivered live only and are never replayed.
* Retained events older than `replayRetention()` are removed by the replay cleanup job.

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


## Maven
The library is hosted on the Central Maven Repository
```
  <dependency>
    <groupId>ch.rasc</groupId>
    <artifactId>sse-eventbus</artifactId>
    <version>3.1.0</version>
  </dependency>  
```

## Demo
Simple demo application:    
https://github.com/ralscha/sse-eventbus-demo

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
