
[![Build Status](https://api.travis-ci.org/ralscha/sse-eventbus.png)](https://travis-ci.org/ralscha/sse-eventbus)


## Maven

```
	<dependency>
		<groupId>ch.rasc</groupId>
		<artifactId>sse-eventbus</artifactId>
		<version>1.0.0-SNAPSHOT</version>
	</dependency>
	
	...
	<!-- Needed for a snapshot release -->
 	<repositories>
 		<repository>
			<id>sonatype</id>
			<name>sonatype</name>
			<url>https://oss.sonatype.org/content/groups/public</url>
			<snapshots>
				<enabled>true</enabled>
			</snapshots>
			<releases>
				<enabled>true</enabled>
			</releases>
		</repository>
	</repositories>	
	
```

## Demo
Simple demo application:    
https://github.com/ralscha/sse-eventbus-demo


## More information
Articles about Server-Sent Events    
https://hpbn.co/server-sent-events-sse/   
https://www.html5rocks.com/en/tutorials/eventsource/basics/


## Browser Support
SSE is supported in most browsers. The notable exceptions are the browsers from Microsoft IE and Edge. 
http://caniuse.com/#feat=eventsource

Fortunately it is possible to polyfill the SSE support where it's missing. 

* **[EventSource](https://github.com/remy/polyfills/blob/master/EventSource.js)** by Remy Sharp
* **[jQuery.EventSource](http://github.com/rwldrn/jquery.eventsource)** by Rick Waldron
* **[EventSource](https://github.com/Yaffle/EventSource)** by Yaffle
* **[EventSource](https://github.com/amvtek/EventSource)** by AmvTek


## Changelog

### 1.0.0 - tbd
  * Initial release


## License
Code released under [the Apache license](http://www.apache.org/licenses/).
