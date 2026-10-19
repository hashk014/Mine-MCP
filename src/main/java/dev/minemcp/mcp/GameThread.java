package dev.minemcp.mcp;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, expiring work queue. A timed-out queued mutation must never run later. */
public final class GameThread {
    private final ArrayBlockingQueue<Job<?>> queue = new ArrayBlockingQueue<>(128);
    private static final class Job<T> {
        final Callable<T> task;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final long deadline;
        // 0 queued, 1 executing, 2 completed, 3 cancelled before execution.
        final AtomicInteger state = new AtomicInteger();
        Job(Callable<T> task, long timeoutMs) { this.task = task; deadline = System.nanoTime() + timeoutMs * 1_000_000L; }
        void run() {
            if (System.nanoTime() > deadline) { cancel(); return; }
            if (!state.compareAndSet(0, 1)) return;
            try { result.complete(task.call()); } catch (Throwable e) { result.completeExceptionally(e); }
            finally { state.set(2); }
        }
        void cancel() {
            if (state.compareAndSet(0, 3)) result.completeExceptionally(new TimeoutException("Expired before execution; nothing was sent"));
        }
    }
    public <T> T call(Callable<T> task, long timeoutMs) throws Exception {
        Job<T> job = new Job<>(task, timeoutMs);
        if (!queue.offer(job)) throw new RejectedExecutionException("Game request queue is full");
        try { return job.result.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) {
            job.cancel(); queue.remove(job);
            if (job.state.get() == 1 || job.state.get() == 2)
                throw new TimeoutException("Request began executing; outcome may be unknown. Inspect state before retrying an action.");
            throw new TimeoutException("Client is not ticking; request cancelled before execution");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) { job.cancel(); queue.remove(job); Thread.currentThread().interrupt(); throw e; }
    }
    public void tick() {
        long start = System.nanoTime();
        for (int i = 0; i < 4 && System.nanoTime() - start < 8_000_000L; i++) {
            Job<?> job = queue.poll(); if (job == null) break; job.run();
        }
    }
    public void close() { Job<?> job; while ((job = queue.poll()) != null) job.cancel(); }
}
