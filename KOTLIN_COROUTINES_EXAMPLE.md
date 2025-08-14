# Example of using sse-eventbus with Kotlin and CoroutineScope

This example demonstrates how to use sse-eventbus with Kotlin and CoroutineScope for building reactive applications.

## Background

This example is based on a vegetable growing activities tracking application modeled after [Square Foot Gardening](https://squarefootgardening.org/) where:
- A bed is modeled as individual cells of 1 sq ft
- A 4 by 8 bed has four rows, eight columns and thus 32 cells
- Multiple clients can observe activities against a given Bed
- When a gardener waters specific cells (e.g., Cells 1-4), it produces "BedWatered" events
- Using sse-eventbus, these events are published to each client observing the bed, updating the UI with visual indicators

## Key Concepts

Before diving into the code, it's recommended to read [this issue about sending to specific clients only](https://github.com/ralscha/sse-eventbus/issues/4) which provides essential information about client-specific event handling.

## Implementation

### Controller

The main controller handles both command execution and SSE event streaming:

```kotlin
@RestController
class BedCommandHandler(
    private val bedCommandMapper: BedCommandMapper,
    private val sseEventBus: SseEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PostMapping("/api/beds/{bedId}/{action}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun handle(
        @PathVariable bedId: UUID,
        @PathVariable action: String,
        @RequestBody commandPayload: Any
    ): ResponseEntity<BedResourceWithCurrentState> {
        try {
            val bed = BedRepository.getBed(bedId)
            scope.launch {
                val command = bedCommandMapper.convertCommand(action, commandPayload)
                bed?.execute(command, sseEventBus) // Execute the command directly
            }
            val resource = BedResourceWithCurrentState.from(bed!!)
            val response = ResponseEntity.ok(resource)
            return response
        } catch(e: Exception) {
            println("Here is the exception: " + e.stackTraceToString())
            throw e
        }
    }

    @GetMapping("/api/beds/{bedId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@PathVariable bedId: UUID,
               @RequestParam clientId: UUID
               ): SseEmitter =
        sseEventBus.createSseEmitter(clientId.toString(), 60_000L, bedId.toString())
}
```

#### Key Implementation Details

- **CoroutineScope**: Declared with `SupervisorJob()` and `Dispatchers.IO` configuration
  - *Recommended reading: Chapters 14 and 15 of [Kotlin in Action 2nd Edition](https://www.manning.com/books/kotlin-in-action-second-edition) for comprehensive coverage of these concepts*
- **Suspend Function**: The `handle` function is declared as `suspend` to work with coroutines
  - *See [Urs Peter's lectures](https://www.youtube.com/watch?v=ahTXElHrV0c&list=RDCMUCP7uiEZIqci43m22KDl0sNw&index=2) for reactive programming with Spring Boot and Kotlin Coroutines*
- **Asynchronous Execution**: The `bed.execute` function runs within the CoroutineScope using `launch`
- **Event Streaming**: The `events` function provides SSE streams to clients using distinct `clientId`s for the same bed

### BedAggregate

The bed aggregate handles command dispatching:

```kotlin
// Generic command handler dispatcher
suspend fun <T : BedCommand> execute(command: T, sseEventBus: SseEventBus) {
    when (command) {
        is PlantSeedlingCommand -> execute(command, sseEventBus)
        else -> {
            dispatchCommandToAllCells(command, sseEventBus)
        }
    }
}

private suspend fun dispatchCommandToAllCells(command: BedCommand, sseEventBus: SseEventBus) {
    coroutineScope {
        rows.forEach { row ->
            row.cells.forEach { cellId ->
                launch {
                    val cell = BedCellRepository.getBedCell(cellId)
                    cell.execute(command, sseEventBus)
                }
            }
        }
    }
}
```

### BedCellAggregate

The individual cell aggregate handles specific commands and publishes events:

```kotlin
// Generic command handler dispatcher
suspend fun <T : BedCommand> execute(command: T, sseEventBus: SseEventBus) {
    // Simulate latency
    delay(10.milliseconds)
    when (command) {
        is PlantSeedlingCommand -> execute(command)
        is WaterCommand -> execute(command, sseEventBus)
        is FertilizeCommand -> execute(command)
        is HarvestCommand -> execute(command)
        else -> throw IllegalArgumentException("Unsupported command type")
    }
}

private fun execute(command: WaterCommand, sseEventBus: SseEventBus) {
    val wateredEvent = BedWatered(command.started, command.volume)
    events.add(wateredEvent)
    sseEventBus.handleEvent(SseEvent.of(command.bedId.toString(), wateredEvent))
}
```

## CLI Demo

### Starting the Service

When the service is up and running, you can test it with multiple concurrent clients.

### Setting up Listeners

Start two separate event listeners with different client IDs:

**Client 1:**
```bash
#!/bin/sh
clientId=0AF7867B-CFC0-48C4-A7A9-0BAFBCDC5569
curl -N http://localhost:8080/api/beds/2fbda883-d49d-4067-8e16-2b04cc523111/events?clientId=$clientId
```

**Client 2:**
```bash
#!/bin/sh
clientId=B59D7469-6989-49EF-AA1D-2D646EF8B06B
curl -N http://localhost:8080/api/beds/2fbda883-d49d-4067-8e16-2b04cc523111/events?clientId=$clientId
```

### Executing Commands

Send water commands to the bed, and observe how events flow to both connected clients in real-time.

Note: The "lastWatered" value for the bed initially returns `null` due to the simulated latency (`delay(10)` milliseconds) in the processing code.

### Observing Events

Both listeners will receive the same events simultaneously, demonstrating the multi-client broadcasting capability of sse-eventbus.

## Benefits of This Approach

1. **Coroutine Integration**: Seamlessly integrates with Kotlin coroutines for non-blocking operations
2. **Client Isolation**: Each client gets its own event stream while sharing the same underlying data
3. **Scalability**: The asynchronous nature allows handling multiple concurrent operations efficiently
4. **Real-time Updates**: Immediate event propagation to all connected clients
5. **Resource Management**: Proper handling of coroutine scopes and lifecycle

## Additional Resources

- [Headache-Free Reactive Programming With Spring Boot and Kotlin Coroutines](https://www.youtube.com/watch?v=ahTXElHrV0c&list=RDCMUCP7uiEZIqci43m22KDl0sNw&index=2)
- [Reactive Spring Boot With Kotlin Coroutines: Adding Virtual Threads](https://www.youtube.com/watch?v=szl3eWA0VRw)
- [Kotlin in Action 2nd Edition](https://www.manning.com/books/kotlin-in-action-second-edition)

---

*This example was contributed by [@JogoShugh](https://github.com/JogoShugh) and is a work in progress. Feedback and suggestions for improvement are welcome, especially from those with more coroutine expertise.*
