package com.piotrekst;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static java.lang.System.out;
import static java.time.LocalDateTime.now;

public class ProjectLoomDemo {

    public static void main(String[] args) throws InterruptedException {
        var executorService = virtualThreadExecutorService();
        long startTime = System.nanoTime();
        IntStream.range(0, 4)
                .forEach(id -> executorService.execute(timeConsumingTask(id)));
        executorService.shutdown();
        executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        long stopTime = System.nanoTime();
        out.println(format("[%s][%s] Processing took = %s ns", now(), Thread.currentThread(), TimeUnit.MILLISECONDS.convert(stopTime - startTime, TimeUnit.NANOSECONDS)));
    }

    private static ExecutorService standardSingleExecutorService() {
        var factory = Thread.builder().name("standard-thread").daemon(true).factory();
        return Executors.newSingleThreadExecutor(factory);
    }

    private static ExecutorService virtualThreadExecutorService() {
        var factory = Thread.builder().name("carrier").daemon(true).factory();
        var executor = Executors.newSingleThreadExecutor(factory);
        var virtualThreadFactory = Thread.builder().name("virtual-thread", 0).virtual(executor).factory();
        return Executors.newThreadExecutor(virtualThreadFactory);
    }

    private static Runnable timeConsumingTask(int id) {
        return () -> {
            out.println(format("[%s][%s] Starting time consuming task [id=%s]", now(), Thread.currentThread(), id));
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                out.println(format("[%s][%s] Oops interruption occurred [id=%s]!", now(), Thread.currentThread(), id));
            }
            out.println(format("[%s][%s] Ended time consuming task [id=%s]", now(), Thread.currentThread(), id));
        };
    }
}
