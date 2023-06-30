package org.team5183.beeapi.threading;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.team5183.beeapi.runnables.NamedRunnable;
import org.team5183.beeapi.runnables.NamedRunnable.RunnableStatus;
import org.team5183.beeapi.runnables.NamedRunnable.RunnableType;
import org.team5183.beeapi.runnables.RepeatedRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

public class ThreadingManager extends Thread {
    private static final Logger logger = LogManager.getLogger(ThreadingManager.class);

    private static final LinkedBlockingQueue<NamedRunnable> queue = new LinkedBlockingQueue<>();
    private static final HashMap<NamedRunnable, Thread> threads = new HashMap<>();

    private static final HashMap<NamedRunnable, Integer> endAttempts = new HashMap<>();

    private static final int maxThreads = (System.getenv("maxThreads") == null || System.getenv("maxThreads").isEmpty() || System.getenv("maxThreads").isBlank()) ? 20 : Integer.parseInt(System.getenv("maxThreads"));

    private static final int maxEndAttempts = (System.getenv("maxEndAttempts") == null || System.getenv("maxEndAttempts").isEmpty() || System.getenv("maxEndAttempts").isBlank()) ? 5 : Integer.parseInt(System.getenv("maxEndAttempts"));
    private static final int maxOneshotEndAttempts = (System.getenv("maxOneshotEndAttempts") == null || System.getenv("maxOneshotEndAttempts").isEmpty() || System.getenv("maxOneshotEndAttempts").isBlank()) ? 20 : Integer.parseInt(System.getenv("maxOneshotEndAttempts"));


    private static final HashMap<NamedRunnable, Long> lastDamagedMessage = new HashMap<>();

    private static RunnableStatus status;

    @Override
    public void run() {
        while (true) {
            if (status == RunnableStatus.ENDING) {
                List<NamedRunnable> finalQueue = new ArrayList<>(queue);
                queue.drainTo(finalQueue);
                finalQueue.forEach(this::startRunnable);

                for (NamedRunnable runnable : threads.keySet()) {
                    //todo only end repeated runnables if all oneshots are done
                    if (runnable.getType() == RunnableType.ONESHOT) {
                        endAttempts.putIfAbsent(runnable, 0);
                        if (endAttempts.get(runnable) >= maxOneshotEndAttempts) {
                            logger.error("Oneshot thread " + runnable.getClass().getName() + " has failed to complete after " + maxEndAttempts + " attempts, forcibly ending.");
                            threads.get(runnable).interrupt();
                        } else {
                            endAttempts.put(runnable, endAttempts.get(runnable) + 1);
                        }
                    }

                    if (runnable.getType() == RunnableType.REPEATED) {
                        RepeatedRunnable rRunnable = (RepeatedRunnable) runnable;
                        if (rRunnable.getStatus() != RunnableStatus.ENDED) {
                            endAttempts.putIfAbsent(runnable, 0);
                            if (endAttempts.get(runnable) >= maxEndAttempts) {
                                logger.error("Thread " + runnable.getClass().getName() + " has failed to end after " + maxEndAttempts + " attempts, forcibly ending.");
                                threads.get(runnable).interrupt();
                            }
                            endAttempts.put(runnable, endAttempts.get(runnable) + 1);
                            rRunnable.shutdown();
                        }
                        if (runnable.getStatus() == RunnableStatus.ENDED) threads.remove(runnable);
                    }
                }

                if (threads.size() == 0) break;
            }

            if (queue.size() > 0) {
                if (threads.size() >= maxThreads) continue;
                startRunnable(queue.poll());
            }

            for (NamedRunnable runnable : threads.keySet()) {
                if (runnable.getStatus() == RunnableStatus.ENDED) threads.remove(runnable);
                if (runnable.getStatus() == RunnableStatus.DAMAGED && System.currentTimeMillis() - lastDamagedMessage.get(runnable) < 60000) {
                    logger.warn("Thread " + runnable.getClass().getName() + " is marked as damaged.");
                    lastDamagedMessage.put(runnable, System.currentTimeMillis());
                }
                if (runnable.getStatus() != RunnableStatus.DAMAGED) lastDamagedMessage.remove(runnable);
            }
        }

        status = RunnableStatus.ENDED;
    }

    private synchronized void startRunnable(NamedRunnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setName(runnable.getName());
        threads.put(runnable, thread);
        thread.start();
    }


    public static synchronized void addTask(NamedRunnable runnable) {
        if (status == RunnableStatus.ENDING || status == RunnableStatus.ENDED) {
            logger.warn("Threading manager is ending, cannot add task " + runnable.getClass().getName());
            return;
        }
        queue.add(runnable);
    }

    public static synchronized CompletableFuture<String> shutdown() {
        CompletableFuture<String> future = new CompletableFuture<>();
        logger.info("Shutting down threading manager");
        status = RunnableStatus.ENDING;
        while (true) {
            if (status == RunnableStatus.ENDED || status == RunnableStatus.FAILED) {
                break;
            }
        }
        future.complete("Threading manager has been shut down");
        return future;
    }
}
