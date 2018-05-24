
[![Build Status](https://api.travis-ci.org/ralscha/sse-eventbus.png)](https://travis-ci.org/ralscha/sse-eventbus)

sse-eventbus is a Java library that sits on top of [Spring's Sever-Sent Event support](https://docs.spring.io/spring/docs/current/spring-framework-reference/web.html#mvc-ann-async-sse).   
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
		<version>1.1.7</version>
	</dependency>	
```

## Demo
Simple demo application:    
https://github.com/ralscha/sse-eventbus-demo

Ionic Demo Chat application:    
https://github.com/ralscha/sse-eventbus-demo-chat


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

### 1.1.7 - May 24, 2018
  * Resolves [Issue #8](https://github.com/ralscha/sse-eventbus/issues/8): Fix handling messages containing a new line character \n
  
  * Resolves [Issue #6](https://github.com/ralscha/sse-eventbus/issues/6): Make members of DefaultSseEventBusConfiguration protected for easier subclassing 
  

### 1.1.6 - March 21, 2018
  * Change client expiration job to fixed delay and add separate configuration for this delay. By default it is 1 day, you change this value by implementing
    `SseEventBusConfigurer.clientExpirationJobDelay` 


### 1.1.5 - January 7, 2018
  * Extract subscription registry code out of the SseEventBus class into the interface SubscriptionRegistry and the class DefaultSubscriptionRegistry. 
    This allows a project to customize the existing implementation or write their own implementation. To
	override the default implementation add a Spring managed bean of type SubscriptionRegistry to your project.   

	Example:
	```
	@Component
	public class CustomSubscriptionRegistry extends DefaultSubscriptionRegistry {

		@Override
		public boolean isClientSubscribedToEvent(String clientId, String eventName) {
			return super.isClientSubscribedToEvent(clientId, eventName)
					|| super.isClientSubscribedToEvent(clientId, "*");
		}
	}	
	```
  

### 1.1.4 - December 15, 2017
  * Resolves [Issue #2](https://github.com/ralscha/sse-eventbus/issues/2). Make sure that your project depends on Spring 4.3.13 or newer.


### 1.1.3 - September 12, 2017
  * Add the following public methods to the SseEventBus class to query events and subscribers.
     * Set<String> getAllClientIds()
     * Set<String> getAllEvents()
     * Map<String, Set<String>> getAllSubscriptions()
     * Set<String> getSubscribers(String event)
     * int countSubscribers(String event)
     * boolean hasSubscribers(String event)


### 1.1.2 - July 16, 2017
  * Add a workaround for the Microsoft Edge browser where the polyfill no longer work correctly.
  The createSseEmitter method supports an additional parameter that tells the library to complete (close) the connection after sending a message.
  This way the system behaves like long polling instead of http streaming.
  ```
  boolean completeAfterMessage = true;
  eventBus.createSseEmitter("client1", 180_000L, true, completeAfterMessage, "event1", "event2");
  ```


### 1.1.1 - July 8, 2017
  * Add support for automatic unregister clients from events during registering.
  ```SseEventBus.createSseEmitter``` supports an additional boolean parameter. If true the method
  subscribes the client to the provided events and unsubscribes it from all other currently subscribed events.
  
    ```eventBus.createSseEmitter("client1", 180_000L, true, "event1", "event2");```    
    After this call the client is only subscribed to ```event1``` and ```event2```.
  
    *...later in the application...*   

    ```eventBus.createSseEmitter("client1", 180_000L, true, "event1");```    
    After this call the client is only subscribed to ```event1```. The method automatically unregistered the client from ```event2```.


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
