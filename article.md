This article gives a quick look into Project Loom - one of the current buzzwords in the Java world.

### [What is Project Loom about?](#project-loom)

[Project Loom](http://cr.openjdk.java.net/~rpressler/loom/loom/sol1_part1.html) started in late 2017. The main goal of the project is to reduce the complexity of creating and maintaining the high-throughput concurrent applications. It introduces the concept of a lightweight concurrency model based on virtual threads. What is the virtual thread? Virtual thread instead of being managed by the operating system as the standard one is scheduled by a Java virtual machine. It results in that such threads can be efficiently scheduled allowing synchronous code to be executed as well as asynchronous code in terms of performance. The implementation is based on an idea such as continuation and operations related to them defined as parking and unparking. 

If you would like to get more information about that idea, I highly recommend getting familiar with [project wiki site](https://wiki.openjdk.java.net/display/loom/Main). 

### [Demo time](#demo)

***Note:*** *The article is based on [JDK from Project Loom Early Access Build - Build 16-loom+7-285 (2020/11/4)](https://jdk.java.net/loom/)*

Assume we got some time-consuming tasks, which we want to run in the background of our application.

```java
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
```

As you can see the task definition is **ridiculously** simple: log start, sleep for 5 seconds, log end of the task.

Let's start with the "classic" daemon thread. We will use for that the `ExecutorService` which is known since JDK 1.5.

```java
private static ExecutorService standardSingleExecutorService() {
    var factory = Thread.builder().name("standard-thread").daemon(true).factory();
    return Executors.newSingleThreadExecutor(factory);
}
```

First of all, we create a thread factory with setting daemon option to true, and then an instance of a single thread executor which will use that factory. Next, our main goal is to execute four "time-consuming" tasks using the created executor.

```java
public static void main(String[] args) throws InterruptedException {
    var ex = standardSingleExecutorService();
    long startTime = System.nanoTime();
    IntStream.range(0, 4)
            .forEach(id -> ex.execute(timeConsumingTask(id)));
    ex.shutdown();
    ex.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    long stopTime = System.nanoTime();
    out.println(format("[%s][%s] Processing took = %s ms", now(), Thread.currentThread(), TimeUnit.MILLISECONDS.convert(stopTime - startTime, TimeUnit.NANOSECONDS)));
}
```

The output looks like:

```shell
[2020-11-22T17:15:54.303382][Thread[standard-thread,5,main]] Starting time consuming task [id=0]
[2020-11-22T17:15:59.323550][Thread[standard-thread,5,main]] Ended time consuming task [id=0]
[2020-11-22T17:15:59.324083][Thread[standard-thread,5,main]] Starting time consuming task [id=1]
[2020-11-22T17:16:04.324808][Thread[standard-thread,5,main]] Ended time consuming task [id=1]
[2020-11-22T17:16:04.325580][Thread[standard-thread,5,main]] Starting time consuming task [id=2]
[2020-11-22T17:16:09.330546][Thread[standard-thread,5,main]] Ended time consuming task [id=2]
[2020-11-22T17:16:09.331408][Thread[standard-thread,5,main]] Starting time consuming task [id=3]
[2020-11-22T17:16:14.335599][Thread[standard-thread,5,main]] Ended time consuming task [id=3]
[2020-11-22T17:16:14.336839][Thread[main,5,main]] Processing took = 20064 ns
```

Everything works as you probably expected. Our four tasks were executed one by one on the single thread pool. It results in the total time needed for execution to be about 20 seconds (4 tasks each with 5 seconds `Thread.sleep()`) *Yeah! The math still works, 4 times 5 equals 20.*

Now let's change our executor service to use the virtual threads. This change is quite simple. To be sure that our virtual thread is scheduled by a single carrier thread we can assign a specific one by specifying it in `virtual()` method. Carrier thread is a name for a scheduler worker thread that is responsible for executing a virtual thread. See the example below:

```java
private static ExecutorService virtualThreadExecutorService() {
    var factory = Thread.builder().name("carrier").daemon(true).factory();
    var executor = Executors.newSingleThreadExecutor(factory);
    var virtualThreadFactory = Thread.builder().name("virtual-thread", 0).virtual(executor).factory();
    return Executors.newThreadExecutor(virtualThreadFactory);
}
```

Now it is time to change the code of the main method to use the ExecutorService based on virtual threads:

```java
var executorService = virtualThreadExecutorService();
```

and the output should be similiar to:

```shell
[2020-11-22T17:16:49.114727][VirtualThread[virtual-thread0,carrier,main]] Starting time consuming task [id=0]
[2020-11-22T17:16:49.161798][VirtualThread[virtual-thread1,carrier,main]] Starting time consuming task [id=1]
[2020-11-22T17:16:49.162620][VirtualThread[virtual-thread2,carrier,main]] Starting time consuming task [id=2]
[2020-11-22T17:16:49.163415][VirtualThread[virtual-thread3,carrier,main]] Starting time consuming task [id=3]
[2020-11-22T17:16:54.163348][VirtualThread[virtual-thread0,carrier,main]] Ended time consuming task [id=0]
[2020-11-22T17:16:54.164141][VirtualThread[virtual-thread1,carrier,main]] Ended time consuming task [id=1]
[2020-11-22T17:16:54.165216][VirtualThread[virtual-thread2,carrier,main]] Ended time consuming task [id=2]
[2020-11-22T17:16:54.166347][VirtualThread[virtual-thread3,carrier,main]] Ended time consuming task [id=3]
[2020-11-22T17:16:54.166858][Thread[main,5,main]] Processing took = 5106 ns
```

As you can see now the execution of the same four tasks takes about 5 seconds. All tasks run in parallel without blocking each other, using the given carrier thread. We achieved it just by changing the type of thread from standard one a virtual. **Magic!**

### [Ok, cool... but how?](#how)

To understand how it is possible we need to dive into `Thread.sleep()` method. 

```java
public static void sleep(Duration duration) throws InterruptedException {
    long nanos = duration.toNanos();
    if (nanos < 0)
        return;

    Thread thread = currentThread();
    if (thread.isVirtual()) {
        if (ThreadSleepEvent.isTurnedOn()) {
            ThreadSleepEvent event = new ThreadSleepEvent();
            try {
                event.time = nanos;
                event.begin();
                ((VirtualThread) thread).sleepNanos(nanos);
            } finally {
                event.commit();
            }
        } else {
            ((VirtualThread) thread).sleepNanos(nanos);
        }
    } else {
        // convert to milliseconds, ceiling rounding mode
        long millis = MILLISECONDS.convert(nanos, NANOSECONDS);
        if (nanos > NANOSECONDS.convert(millis, MILLISECONDS)) {
            millis += 1L;
        }
        sleep(millis);
    }
}
```

As you can see there is a conditional statement where the implementation of sleep behaves differently when is performed on a virtual thread. The `sleepNanos(long nanos)` method from `VirtualThread` class gives us a clue. 

```java
void sleepNanos(long nanos) throws InterruptedException {
...
                    while (remainingNanos > 0) {
                        parkNanos(remainingNanos);
                        if (getAndClearInterrupt()) {
                            throw new InterruptedException();
                        }
                        remainingNanos = nanos - (System.nanoTime() - startNanos);
                    }
...
    }
```
There is a line that says that our virtual thread will be **parked** (`parkNanos(remainingNanos)`). Parking a virtual thread means yielding its continuation. Virtual thread parked for some time? Let's use that time for other virtual threads!

Finally, after going deeper and deeper into the implementation of park operation we reached the part of the code which is responsible for scheduling the **unparking** of the given virtual thread. Unparking the virtual thread results in that its continuation is being resubmitted to the scheduler. In our case means that after 5 seconds of sleep our virtual thread can be continued and able to print the ending log.

```java
/**
 * Schedules this thread to be unparked after the given delay.
 */
@ChangesCurrentThread
private Future<?> scheduleUnpark(long nanos) {
    //assert Thread.currentThread() == this;
    Thread carrier = this.carrierThread;
    // need to switch to carrier thread to avoid nested parking
    carrier.setCurrentThread(carrier);
    try {
        return UNPARKER.schedule(this::unpark, nanos, NANOSECONDS);
    } finally {
        carrier.setCurrentThread(this);
    }
}
```

The `UNPARKER` is the default `ScheduledExecutorService` which is created for virtual threads purposes.

```java
private static final ScheduledExecutorService UNPARKER = createDelayedTaskScheduler();
```

This example was based on analyzing the `Thread.sleep()` method, but also other blocking methods from different libraries were optimized for usage by virtual threads. The list of "virtual threads friendly" methods can be found [here](https://wiki.openjdk.java.net/display/loom/Blocking+Operations). 

### [Summary](#summary)

In my opinion Project Loom and the benefits it provides can be a game-changer in Java world. Providing the lightweight concurrency built-in standard libraries and performance of asynchronous code in synchronous implementations can be the fast and easy way to increase the efficiency of existing systems. I'm curious how the project will change the approach to concurrency in Java and its impact on popular libraries and frameworks.

Code samples can be found on [my github](https://github.com/PiotrekSt/project-loom-demo).

Follow [me on Twitter](https://twitter.com/PiotrekSta).





















