package dev.minemcp.mcp;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Scheduling onto Minecraft's server executor without blocking the client tick. */
final class DeferredWork {
    static <T> CompletableFuture<T> submit(Consumer<Runnable> executor, Callable<T> body, long timeoutMs) {
        CompletableFuture<T> result = new CompletableFuture<>();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        AtomicBoolean started = new AtomicBoolean();
        executor.accept(() -> {
            if (result.isDone()) return;
            if (System.nanoTime() > deadline) {
                result.completeExceptionally(new TimeoutException("Expired before server execution; nothing was changed")); return;
            }
            if (!started.compareAndSet(false, true) || result.isCancelled()) return;
            try { result.complete(body.call()); } catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
}
