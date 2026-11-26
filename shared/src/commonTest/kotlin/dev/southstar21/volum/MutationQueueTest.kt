package dev.southstar21.volum

import dev.southstar21.volum.data.MutationQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationQueueTest {
    @Test
    fun sendsInOrderOneAtATimeAndSurvivesAFailure() = runTest {
        val scope = CoroutineScope(Job() + coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!)
        val log = mutableListOf<String>()
        var running = 0
        val queue = MutationQueue<Int, Int>(scope) { n ->
            assertEquals(1, ++running)
            delay(10)
            running--
            log += "sent$n"
            if (n == 2) error("boom")
            n * 10
        }
        val results = mutableListOf<Result<Int>>()
        for (n in 1..4) queue.submit(n) { results += it }
        delay(100)
        assertEquals(listOf("sent1", "sent2", "sent3", "sent4"), log)
        assertEquals(listOf(10, 30, 40), results.mapNotNull { it.getOrNull() })
        assertTrue(results[1].isFailure)
        scope.coroutineContext[Job]!!.cancel()
    }
}
