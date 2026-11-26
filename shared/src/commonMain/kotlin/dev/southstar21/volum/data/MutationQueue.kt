package dev.southstar21.volum.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Sends edits one at a time, in the order they were made: sent concurrently, two nudges (or an
 * AddSet and the Set after it) could land out of order and revert each other. It drains on [scope]
 * rather than on a screen, so edits still queued when the screen is left are sent all the same.
 */
class MutationQueue<M, R>(scope: CoroutineScope, private val send: suspend (M) -> R) {
    private class Item<M, R>(val mutation: M, val onDone: suspend (Result<R>) -> Unit)

    private val items = Channel<Item<M, R>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in items) {
                val result = try {
                    Result.success(send(item.mutation))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                item.onDone(result)
            }
        }
    }

    /** Queues [mutation]; [onDone] runs after its send ends, in the queue's coroutine, however it ended. */
    fun submit(mutation: M, onDone: suspend (Result<R>) -> Unit) {
        items.trySend(Item(mutation, onDone))
    }
}
