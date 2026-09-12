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

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;

/**
 * Bounded per-client send buffer with a dedicated dispatcher thread.
 * <p>
 * When enabled through {@link SseEventBusConfigurer#clientSendBufferCapacity()} every
 * client gets its own bounded queue. A dedicated daemon thread takes events from the
 * queue and writes them to the client's {@code SseEmitter}. This prevents slow clients
 * from blocking the shared send workers (head-of-line blocking).
 * <p>
 * When the queue is full the configured {@link OverflowPolicy} is applied:
 * <ul>
 * <li>{@link OverflowPolicy#DROP}: the new event is dropped, the connection stays
 * open.</li>
 * <li>{@link OverflowPolicy#DISCONNECT}: the connection is closed and the client is
 * unregistered through the {@code onDisconnect} callback.</li>
 * </ul>
 * In both cases {@link SlowClientListener} is notified and Micrometer metrics are
 * recorded when configured.
 * <p>
 * Delivery accounting happens on the dispatcher thread: {@link DeliveryListener} is
 * invoked after the event was actually written to the {@code SseEmitter} (or after a send
 * failure), so an enqueued event is never reported as delivered before the sink has run.
 */
final class ClientSendBuffer implements AutoCloseable {

	/**
	 * Result of an {@link #offer(ClientEvent)} call.
	 */
	enum OfferResult {

		/**
		 * The event was enqueued; delivery is reported asynchronously.
		 */
		ACCEPTED,

		/**
		 * The queue was full and the event was dropped by the {@link OverflowPolicy#DROP}
		 * policy.
		 */
		DROPPED,

		/**
		 * The queue was full and the slow client was disconnected by the
		 * {@link OverflowPolicy#DISCONNECT} policy.
		 */
		DISCONNECTED,

		/**
		 * The buffer is already closed, the event was not enqueued.
		 */
		CLOSED

	}

	/**
	 * Writes an event to the underlying SSE connection.
	 */
	@FunctionalInterface
	interface EventSink {

		void send(SseEventBuilder event, boolean heartbeat) throws IOException;

	}

	/**
	 * Delivery accounting callback, invoked on the dispatcher thread.
	 */
	interface DeliveryListener {

		/**
		 * Called after the event was successfully written to the connection.
		 * @param event the delivered event
		 */
		void delivered(ClientEvent event);

		/**
		 * Called after writing the event failed.
		 * @param event the failed event
		 * @param exception the failure cause
		 */
		void failed(ClientEvent event, Exception exception);

	}

	private final String clientId;

	private final int capacity;

	private final OverflowPolicy overflowPolicy;

	private record BufferedEvent(ClientEvent event, boolean heartbeat) {
	}

	private final BlockingQueue<BufferedEvent> queue;

	private final EventSink sink;

	private final DeliveryListener deliveryListener;

	private final @Nullable Consumer<ClientSendBuffer> onDisconnect;

	private final @Nullable SlowClientListener listener;

	private final @Nullable SseBackpressureMetrics metrics;

	private final AtomicBoolean closed = new AtomicBoolean();

	private final AtomicBoolean draining = new AtomicBoolean();

	private @Nullable SlowClientEvent pendingNotification;

	private volatile @Nullable Thread dispatcherThread;

	ClientSendBuffer(String clientId, int capacity, OverflowPolicy overflowPolicy, EventSink sink,
			DeliveryListener deliveryListener, @Nullable Consumer<ClientSendBuffer> onDisconnect,
			@Nullable SlowClientListener listener, @Nullable SseBackpressureMetrics metrics) {
		this.clientId = Objects.requireNonNull(clientId, "clientId");
		if (capacity <= 0) {
			throw new IllegalArgumentException("clientSendBufferCapacity must be > 0");
		}
		this.capacity = capacity;
		this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
		this.queue = new LinkedBlockingQueue<>(capacity);
		this.sink = Objects.requireNonNull(sink, "sink");
		this.deliveryListener = Objects.requireNonNull(deliveryListener, "deliveryListener");
		this.onDisconnect = onDisconnect;
		this.listener = listener;
		this.metrics = metrics;
	}

	/**
	 * Starts the dedicated dispatcher thread. Idempotent.
	 */
	synchronized void start() {
		if (this.closed.get() || this.dispatcherThread != null) {
			return;
		}
		Thread thread = new Thread(this::dispatchLoop, "sse-client-send-" + this.clientId);
		thread.setDaemon(true);
		this.dispatcherThread = thread;
		thread.start();
	}

	/**
	 * Attempts to enqueue an event for this client.
	 * @param event the event to enqueue
	 * @return the result of the enqueue attempt
	 */
	OfferResult offer(ClientEvent event) {
		return offer(new BufferedEvent(event, false));
	}

	void offerHeartbeat(Client client, String comment) {
		offer(new BufferedEvent(new ClientEvent(client, SseEvent.builder().comment(comment).build(), null), true));
	}

	private OfferResult offer(BufferedEvent event) {
		synchronized (this) {
			if (this.closed.get() || this.draining.get()) {
				return OfferResult.CLOSED;
			}
			if (this.queue.offer(event)) {
				return OfferResult.ACCEPTED;
			}
			if (this.metrics != null) {
				this.metrics.recordOverflow(this.clientId);
			}
			// The failed offer observed a full queue, even if the dispatcher has since
			// removed an event.
			if (this.overflowPolicy == OverflowPolicy.DROP) {
				if (this.metrics != null) {
					this.metrics.recordDropped(this.clientId);
				}
				this.pendingNotification = SlowClientEvent.of(this.clientId, this.overflowPolicy, this.capacity,
						this.capacity, "send buffer full, new event dropped");
				return OfferResult.DROPPED;
			}
			if (this.metrics != null) {
				this.metrics.recordDisconnected(this.clientId);
			}
			this.pendingNotification = SlowClientEvent.of(this.clientId, this.overflowPolicy, this.capacity,
					this.capacity, "send buffer full, slow client disconnected");
			close();
		}
		// Completing an emitter and invoking application callbacks can both block.
		// Run this once per disconnected buffer, independently of its blocked sink.
		Thread notificationThread = new Thread(this::notifyDisconnectAndSlowClient,
				"sse-client-disconnect-" + this.clientId);
		notificationThread.setDaemon(true);
		notificationThread.start();
		return OfferResult.DISCONNECTED;
	}

	private @Nullable SlowClientEvent takePendingNotification() {
		synchronized (this) {
			SlowClientEvent pending = this.pendingNotification;
			this.pendingNotification = null;
			return pending;
		}
	}

	private void notifyListener(SlowClientEvent slowClientEvent) {
		SlowClientListener slowClientListener = this.listener;
		if (slowClientListener != null) {
			if (this.metrics != null) {
				this.metrics.recordNotification(this.clientId);
			}
			try {
				slowClientListener.onSlowClient(slowClientEvent);
			}
			catch (RuntimeException ex) {
				// listener failures must not affect event delivery
			}
		}
	}

	private void dispatchLoop() {
		try {
			while (!this.closed.get() && !Thread.currentThread().isInterrupted()) {
				SlowClientEvent pending = takePendingNotification();
				if (pending != null) {
					notifyListener(pending);
				}
				BufferedEvent event = this.queue.poll(200, TimeUnit.MILLISECONDS);
				if (event == null) {
					if (this.closed.get() || this.draining.get()) {
						break;
					}
					continue;
				}
				if (!this.closed.get()) {
					deliver(event);
				}
			}
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}

	private void deliver(BufferedEvent bufferedEvent) {
		ClientEvent event = bufferedEvent.event();
		try {
			SseEventBuilder builder = event.createSseEventBuilder();
			if (this.closed.get()) {
				return;
			}
			this.sink.send(builder, bufferedEvent.heartbeat());
		}
		catch (Exception ex) {
			try {
				if (!bufferedEvent.heartbeat()) {
					this.deliveryListener.failed(event, ex);
				}
			}
			finally {
				closeAndNotifyDisconnect();
			}
			return;
		}
		if (bufferedEvent.heartbeat()) {
			event.getClient().updateLastTransfer();
		}
		else {
			this.deliveryListener.delivered(event);
		}
	}

	/**
	 * Starts draining: the dispatcher thread keeps delivering already queued events and
	 * exits once the queue is empty. Idempotent. Call {@link #awaitDrained(long)} to wait
	 * for the dispatcher to finish.
	 */
	synchronized void startDraining() {
		this.draining.set(true);
	}

	/**
	 * Waits until the dispatcher thread has exited (queue drained), or until the deadline
	 * elapses. A timed-out dispatcher is interrupted so the buffer can be closed without
	 * losing control.
	 * @param deadlineNanos absolute deadline in nanoseconds
	 */
	void awaitDrained(long deadlineNanos) {
		Thread thread = this.dispatcherThread;
		if (thread == null || thread == Thread.currentThread()) {
			return;
		}
		try {
			while (thread.isAlive() && System.nanoTime() < deadlineNanos) {
				Thread.sleep(10);
			}
			if (thread.isAlive()) {
				thread.interrupt();
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Starts draining and waits until the dispatcher thread has delivered all currently
	 * queued events, or until the timeout elapses. Events still pending after a timeout
	 * are discarded when the buffer is closed.
	 * @param timeoutMillis maximum time to wait for the queue to drain
	 */
	void drain(long timeoutMillis) {
		startDraining();
		awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
	}

	/**
	 * Closes the buffer without firing the disconnect callback and without draining the
	 * queue. Pending events are discarded. Used when the client is re-registered or
	 * unregistered through the regular lifecycle.
	 */
	@Override
	public synchronized void close() {
		if (this.closed.compareAndSet(false, true)) {
			this.queue.clear();
			interruptDispatcher();
		}
	}

	private void closeAndNotifyDisconnect() {
		synchronized (this) {
			if (this.closed.get()) {
				return;
			}
			close();
		}
		notifyDisconnectAndSlowClient();
	}

	private void notifyDisconnectAndSlowClient() {
		Consumer<ClientSendBuffer> disconnect = this.onDisconnect;
		if (disconnect != null) {
			try {
				disconnect.accept(this);
			}
			catch (RuntimeException ex) {
				// ignore disconnect callback failures
			}
		}
		SlowClientEvent pending = takePendingNotification();
		if (pending != null) {
			notifyListener(pending);
		}
	}

	private void interruptDispatcher() {
		Thread thread = this.dispatcherThread;
		if (thread != null) {
			thread.interrupt();
		}
	}

	int queueSize() {
		return this.queue.size();
	}

	void removePendingReplayableEvents() {
		this.queue.removeIf(
				bufferedEvent -> bufferedEvent.event().getSseEvent().id().filter(id -> !id.isEmpty()).isPresent());
	}

	int capacity() {
		return this.capacity;
	}

	boolean isClosed() {
		return this.closed.get();
	}

	String clientId() {
		return this.clientId;
	}

}
