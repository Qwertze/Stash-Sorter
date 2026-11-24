package com.porter.qwertestash.manager;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The job queue and the apply worker of the dashboard link: a bounded FIFO and one thread (ideas from PR 1). */
class DashboardLinkQueueTest {
    private DashboardLink link;

    private static final class FakeHost implements DashboardLink.Host {
        @Override public @Nullable URI base() { return null; }
        @Override public String key() { return ""; }
        @Override public String botId() { return "bot"; }
        @Override public String player() { return ""; }
        @Override public List<DashboardLink.Scope> scopes() { return List.of(); }
        @Override public String pluginId() { return "qwerte-stash"; }
        @Override public String pluginVersion() { return "test"; }
        @Override public String proxyVersion() { return "test"; }
        @Override public String mcVersion() { return "1.21.4"; }
        @Override public boolean consoleAllowed() { return false; }
        @Override public boolean proxyAllowed() { return false; }
        @Override public boolean wanted() { return true; }
    }

    @AfterEach
    void stop() {
        if (link != null) link.stop();
    }

    private static String jobs(int from, int to) {
        StringBuilder sb = new StringBuilder("{\"jobs\":[");
        for (int i = from; i < to; i++) sb.append(i > from ? "," : "").append("{\"id\":\"").append(i).append("\"}");
        return sb.append("]}").toString();
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
        return condition.getAsBoolean();
    }

    @Test
    void jobsRunOneAfterAnotherInTheOrderTheyCame() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        Thread[] threads = new Thread[1];
        link = new DashboardLink(new FakeHost());
        link.jobRunner = job -> {
            ran.add(job.get("id").getAsString());
            threads[0] = Thread.currentThread();
        };
        link.start();
        link.take(jobs(0, 20));
        link.take(jobs(20, 25));
        assertTrue(await(() -> ran.size() == 25));
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) expected.add(String.valueOf(i));
        assertEquals(expected, ran);
        assertEquals("dashboard-apply", threads[0].getName());
    }

    @Test
    void aFullQueueHoldsTheReaderBackUntilThereIsRoom() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        link = new DashboardLink(new FakeHost());
        link.jobRunner = job -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.add(job.get("id").getAsString());
        };
        link.start();
        link.take(jobs(0, 1));
        Thread.sleep(200); // the worker has job 0 in its hands
        int total = 1 + DashboardLink.MAX_PENDING_JOBS + 3; // 3 more than fit
        AtomicBoolean done = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try {
                link.take(jobs(1, total));
                done.set(true);
            } catch (InterruptedException ignored) {
                // test over
            }
        });
        reader.start();
        Thread.sleep(600);
        assertFalse(done.get(), "the reader waits while the queue is full");
        assertTrue(reader.isAlive());
        release.countDown();
        assertTrue(await(done::get));
        assertTrue(await(() -> ran.size() == total));
        for (int i = 0; i < total; i++) assertEquals(String.valueOf(i), ran.get(i));
    }

    @Test
    void stopWhileTheReaderWaitsReleasesItAndDropsTheQueuedJobs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        link = new DashboardLink(new FakeHost());
        link.jobRunner = job -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.add(job.get("id").getAsString());
        };
        link.start();
        link.take(jobs(0, 1));
        Thread.sleep(200);
        AtomicBoolean done = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try {
                link.take(jobs(1, 1 + DashboardLink.MAX_PENDING_JOBS + 5));
                done.set(true);
            } catch (InterruptedException ignored) {
                done.set(true);
            }
        });
        reader.start();
        Thread.sleep(400);
        assertFalse(done.get());
        link.stop();
        assertTrue(await(done::get), "stop frees the waiting reader");
        release.countDown();
        Thread.sleep(500);
        // at most the job the worker already held; none of the queued ones
        assertTrue(ran.size() <= 1, "ran " + ran);
    }

    @Test
    void aJobThatStopsTheLinkFinishesAndItsWorkerIsNotInterrupted() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(true);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        link = new DashboardLink(new FakeHost());
        link.jobRunner = job -> {
            ran.add(job.get("id").getAsString());
            link.stop();
            interrupted.set(Thread.currentThread().isInterrupted());
            finished.countDown();
        };
        link.start();
        link.take(jobs(0, 5));
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertFalse(interrupted.get());
        Thread.sleep(300);
        assertEquals(List.of("0"), ran, "the jobs queued behind it were dropped");
    }

    @Test
    void aJobObjectKeepsItsFieldsThroughTheQueue() throws Exception {
        List<JsonObject> seen = Collections.synchronizedList(new ArrayList<>());
        link = new DashboardLink(new FakeHost());
        link.jobRunner = seen::add;
        link.start();
        link.take("{\"jobs\":[{\"id\":\"a\",\"type\":\"set\",\"path\":\"x\"}, 5, \"s\"]}");
        assertTrue(await(() -> seen.size() == 1));
        assertEquals("set", seen.get(0).get("type").getAsString());
    }
}
