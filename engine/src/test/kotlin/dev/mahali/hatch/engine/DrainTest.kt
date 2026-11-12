package dev.mahali.hatch.engine

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class DrainTest {
    @Test fun `producers keep their order and nothing is stranded`() {
        val lane = Executors.newSingleThreadExecutor()
        val seen = ArrayList<Pair<Int, Int>>()
        val drain = Drain<Pair<Int, Int>>({ run -> lane.execute(run) }, { seen += it })
        val producers = (0 until 4).map { p -> Thread { repeat(5_000) { drain.offer(p to it) } } }
        producers.forEach { it.start() }
        producers.forEach { it.join() }
        lane.shutdown()
        lane.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(20_000, seen.size)
        for (p in 0 until 4) assertEquals((0 until 5_000).toList(), seen.filter { it.first == p }.map { it.second })
    }

    @Test fun `item offered during a drain is handled`() {
        val tasks = ConcurrentLinkedQueue<() -> Unit>()
        val seen = ArrayList<Int>()
        lateinit var drain: Drain<Int>
        drain = Drain({ tasks.add(it) }, { seen += it; if (it == 1) drain.offer(2) })
        drain.offer(1)
        while (true) (tasks.poll() ?: break)()
        assertEquals(listOf(1, 2), seen)
    }

    @Test fun `a drain stops at its budget and reschedules`() {
        val tasks = ConcurrentLinkedQueue<() -> Unit>()
        val seen = ArrayList<Int>()
        val drain = Drain<Int>({ tasks.add(it) }, { seen += it }, budget = 3)
        repeat(7) { drain.offer(it) }
        assertEquals(1, tasks.size)
        tasks.poll()!!()
        assertEquals(listOf(0, 1, 2), seen)
        assertEquals(1, tasks.size)
        while (true) (tasks.poll() ?: break)()
        assertEquals((0 until 7).toList(), seen)
        drain.offer(7)
        assertEquals(1, tasks.size)
    }
}
