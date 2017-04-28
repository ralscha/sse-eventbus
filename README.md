
[![Build Status](https://api.travis-ci.org/ralscha/sse-eventbus.png)](https://travis-ci.org/ralscha/sse-eventbus)

sse-eventbus is a Java library that sits on top of [Spring's Sever-Sent Event support](http://docs.spring.io/spring/docs/current/spring-framework-reference/htmlsingle/#mvc-ann-async-sse).   
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
		this.eventBus.registerClient(clientId, emitter);
		this.eventBus.subscribe(id, SseEvent.DEFAULT_EVENT);
		return emitter;

		//OR
		//return this.eventBus.createSseEmitter(id, SseEvent.DEFAULT_EVENT)
	}
}
```

### Setup client

On the client side an application interacts with the [EventSource](https://developer.mozilla.org/en/docs/Web/API/EventSource) object.
This object is responsible for sending the SSE request to the server and calling listeners
the application registered on this object. 
As mentioned before the client has to send an id that should be unique among all the clients. 
A simple way is to use libraries like [node-uuid](https://github.com/kelektiv/node-uuid) that generates UUIDs.

```
const uuid = uuid();
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
	// this class implements the ApplicationEventPublisher interface
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
		<version>1.1.0</version>
	</dependency>	
```

## Demo
Simple demo application:    
https://github.com/ralscha/sse-eventbus-demo


## More information
Articles about Server-Sent Events    
* https://hpbn.co/server-sent-events-sse/   
* https://www.html5rocks.com/en/tutorials/eventsource/basics/


## Browser Support
SSE is supported in most browsers. The notable exceptions are the browsers from Microsoft IE and Edge.   
http://caniuse.com/#feat=eventsource

Fortunately it is possible to polyfill the SSE support where it's missing. 

* **[EventSource](https://github.com/remy/polyfills/blob/master/EventSource.js)** by Remy Sharp
* **[jQuery.EventSource](http://github.com/rwldrn/jquery.eventsource)** by Rick Waldron
* **[EventSource](https://github.com/Yaffle/EventSource)** by Yaffle
* **[EventSource](https://github.com/amvtek/EventSource)** by AmvTek


## Changelog

### 1.1.0 - April 28, 2017
  * Add support for Jackson JSON View. 
    ```SseEvent.builder().event("eventName").data(dataObj).jsonView(JsonViews.PUBLIC.class).build()```   
  To support that the interface ```ch.rasc.sse.eventbus.DataObjectConverter``` changed. 
  Instead of the ```data``` object the two methods receive the ```SseEvent``` object.    
  ```1.0.x:  boolean supports(Object object);  String convert(Object object);```    
  ```1.1.x:  boolean supports(SseEvent event); String convert(SseEvent event);```    
  To get the data object your code can call ```event.data()```.
 

### 1.0.1 - March 31, 2017
  * Add support for excluding clients with the ```addExcludeClientId``` method.
    ``` 
    SseEvent.builder().addExcludeClientId("2")
		      .event("eventName")
		      .data("payload")
		      .build();
    ```
    

### 1.0.0 - November 19, 2016
  * Initial release


## License
Code released under [the Apache license](http://www.apache.org/licenses/).
