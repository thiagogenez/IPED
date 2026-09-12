package iped.engine.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.data.ICaseData;
import iped.engine.core.Statistics;
import iped.engine.data.Item;

public class HashTaskTest {

    private static final byte[] NEXT_ITEM = "intact evidence B".getBytes(StandardCharsets.UTF_8);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testMd5AfterReadError() throws Exception {
        assertHashAfterReadError("md5", 123);
    }

    @Test
    public void testSha256AfterReadError() throws Exception {
        assertHashAfterReadError("sha-256", 123);
    }

    @Test
    public void testEd2kAfterReadErrorWithCompletedChunk() throws Exception {
        // Exercise both a completed ED2K chunk and the partial next chunk.
        assertHashAfterReadError("edonkey", 9500 * 1024 + 17);
    }

    private void assertHashAfterReadError(String algorithm, int prefixLength) throws Exception {
        byte[] prefix = new byte[prefixLength];
        Arrays.fill(prefix, (byte) 'A');
        CountDownLatch hashed = new CountDownLatch(1);
        MessageDigest digest = new TrackingDigest(newDigest(algorithm)) {
            private long count;

            @Override
            protected void engineUpdate(byte[] bytes, int offset, int length) {
                super.engineUpdate(bytes, offset, length);
                count += length;
                if (count >= prefixLength) {
                    hashed.countDown();
                }
            }
        };
        HashTask task = newTask(algorithm, digest);
        InputStream failing = new InputStream() {
            private final InputStream input = new ByteArrayInputStream(prefix);

            @Override
            public int read() throws IOException {
                throw new IOException("Bulk reads expected");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = input.read(bytes, offset, length);
                if (count >= 0) {
                    return count;
                }
                await(hashed);
                throw new IOException("Injected read failure after a readable prefix");
            }
        };
        Item damaged = item(failing, prefixLength + 1);
        task.process(damaged);
        assertNull(damaged.getHash());
        assertEquals("true", damaged.getExtraAttribute("ioError"));
        assertNextItemHash(task, algorithm);
        assertNextItemHash(task, algorithm);
    }

    @Test
    public void testReadErrorWaitsForPendingDigestBeforeReset() throws Exception {
        assertPendingDigestCleanup(false);
    }

    @Test
    public void testInterruptedHashWaitsForPendingDigestAndPreservesInterrupt() throws Exception {
        assertPendingDigestCleanup(true);
    }

    @Test
    public void testFinalChunkDigestFailureDoesNotPublishHash() throws Exception {
        CountDownLatch eof = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        MessageDigest digest = new TrackingDigest(newDigest("md5")) {
            @Override
            protected void engineUpdate(byte[] bytes, int offset, int length) {
                if (first.getAndSet(false)) {
                    try {
                        await(eof);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    throw new IllegalStateException("Injected final-chunk digest failure");
                }
                super.engineUpdate(bytes, offset, length);
            }
        };
        HashTask task = newTask("md5", digest);
        Item damaged = item(new InputStream() {
            private boolean firstRead = true;

            @Override
            public int read() throws IOException {
                throw new IOException("Bulk reads expected");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                if (firstRead) {
                    firstRead = false;
                    bytes[offset] = 'A';
                    return 1;
                }
                eof.countDown();
                return -1;
            }
        }, 1);
        task.process(damaged);
        assertNull(damaged.getHash());
        assertNull(damaged.getExtraAttribute("md5"));
        assertNextItemHash(task, "md5");
    }

    @Test
    public void testErrorOnFirstSubmissionPropagates() throws Exception {
        assertSubmissionFailure(false, true);
    }

    @Test
    public void testErrorAfterAcceptedSubmissionWaitsAndPropagates() throws Exception {
        assertSubmissionFailure(true, true);
    }

    @Test
    public void testRejectedSubmissionWaitsAndResets() throws Exception {
        assertSubmissionFailure(true, false);
    }

    private void assertSubmissionFailure(boolean acceptFirst, boolean fatal) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch submissionFailed = new CountDownLatch(1);
        AtomicBoolean hashing = new AtomicBoolean();
        AtomicBoolean resetWhileHashing = new AtomicBoolean();
        OutOfMemoryError submissionError = new OutOfMemoryError("Injected submission failure");
        // Daemon threads ensure a broken cleanup cannot hang the test JVM.
        ExecutorService updates = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "hash-test-update");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService caller = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "hash-test-caller");
            thread.setDaemon(true);
            return thread;
        });
        HashTask task = new HashTask() {
            private int submissions;
            private boolean failed;

            @Override
            void submitDigestUpdate(Runnable update) {
                if (!failed && submissions++ == (acceptFirst ? 1 : 0)) {
                    if (acceptFirst) {
                        try {
                            await(started);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    failed = true;
                    submissionFailed.countDown();
                    if (fatal) {
                        throw submissionError;
                    }
                    throw new RejectedExecutionException("Injected rejection");
                }
                updates.execute(update);
            }
        };
        MessageDigest md5 = new TrackingDigest(newDigest("md5")) {
            @Override
            protected void engineUpdate(byte[] bytes, int offset, int length) {
                hashing.set(true);
                started.countDown();
                try {
                    await(release);
                    super.engineUpdate(bytes, offset, length);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    hashing.set(false);
                }
            }

            @Override
            protected void engineReset() {
                resetWhileHashing.set(resetWhileHashing.get() || hashing.get());
                super.engineReset();
            }
        };
        configureTask(task, "md5", md5);
        // Three algorithms cover the failed submission AND submissions not yet attempted.
        addDigest(task, "sha-256", newDigest("sha-256"));
        addDigest(task, "sha-1", newDigest("sha-1"));
        Item damaged = item(new ByteArrayInputStream(new byte[] { 'A' }), 1);
        Future<?> processing = caller.submit(() -> task.process(damaged));
        try {
            assertTrue(submissionFailed.await(5, TimeUnit.SECONDS));
            if (acceptFirst) {
                try {
                    processing.get(200, TimeUnit.MILLISECONDS);
                    fail("Submission failure returned before an accepted update finished");
                } catch (TimeoutException expected) {
                    // Cleanup must wait for the accepted task, even for fatal errors.
                }
            }
            release.countDown();
            try {
                processing.get(5, TimeUnit.SECONDS);
                assertFalse("Fatal submission error was swallowed", fatal);
            } catch (ExecutionException e) {
                assertTrue("Normal rejection must follow the existing exception handling", fatal);
                assertSame(submissionError, e.getCause());
            }
            assertFalse(resetWhileHashing.get());
            assertNull(damaged.getHash());
            for (String algorithm : new String[] { "md5", "sha-256", "sha-1" }) {
                assertNull(damaged.getExtraAttribute(algorithm));
            }
            Item next = item(new ByteArrayInputStream(NEXT_ITEM), NEXT_ITEM.length);
            task.process(next);
            assertEquals(HashTask.getHashString(newDigest("md5").digest(NEXT_ITEM)), next.getHash());
            for (String algorithm : new String[] { "md5", "sha-256", "sha-1" }) {
                assertEquals(HashTask.getHashString(newDigest(algorithm).digest(NEXT_ITEM)),
                        next.getExtraAttribute(algorithm));
            }
            assertNull(next.getExtraAttribute("ioError"));
        } finally {
            release.countDown();
            caller.shutdownNow();
            updates.shutdownNow();
        }
    }

    private void assertPendingDigestCleanup(boolean interrupt) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch readFinished = new CountDownLatch(1);
        AtomicBoolean hashing = new AtomicBoolean();
        AtomicBoolean resetWhileHashing = new AtomicBoolean();
        AtomicBoolean interruptedAtExit = new AtomicBoolean();
        MessageDigest digest = new TrackingDigest(newDigest("md5")) {
            @Override
            protected void engineUpdate(byte[] bytes, int offset, int length) {
                hashing.set(true);
                started.countDown();
                try {
                    await(release);
                    super.engineUpdate(bytes, offset, length);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    hashing.set(false);
                }
            }

            @Override
            protected void engineReset() {
                if (hashing.get()) {
                    resetWhileHashing.set(true);
                }
                super.engineReset();
            }
        };
        HashTask task = newTask("md5", digest);
        Item damaged = item(new InputStream() {
            private boolean first = true;

            @Override
            public int read() throws IOException {
                throw new IOException("Bulk reads expected");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (first) {
                    first = false;
                    bytes[offset] = 'A';
                    return 1;
                }
                await(started);
                if (interrupt) {
                    Thread.currentThread().interrupt();
                    readFinished.countDown();
                    return -1;
                }
                readFinished.countDown();
                throw new IOException("Injected read failure while digest is pending");
            }
        }, 2);

        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> processing = caller.submit(() -> {
            task.process(damaged);
            interruptedAtExit.set(Thread.currentThread().isInterrupted());
        });
        try {
            assertTrue(readFinished.await(5, TimeUnit.SECONDS));
            try {
                processing.get(200, TimeUnit.MILLISECONDS);
                fail("HashTask returned before the pending digest finished");
            } catch (TimeoutException expected) {
                // The digest must finish before its mutable state can be reused.
            }
        } finally {
            release.countDown();
            try {
                processing.get(5, TimeUnit.SECONDS);
            } finally {
                caller.shutdownNow();
            }
        }
        assertFalse(resetWhileHashing.get());
        assertEquals(interrupt, interruptedAtExit.get());
        assertNull(damaged.getHash());
        assertNextItemHash(task, "md5");
    }

    private void assertNextItemHash(HashTask task, String algorithm) throws Exception {
        Item next = item(new ByteArrayInputStream(NEXT_ITEM), NEXT_ITEM.length);
        task.process(next);
        // NEXT_ITEM is smaller than one ED2K chunk, so its ED2K hash is MD4(content).
        String expected = HashTask.getHashString(newDigest(algorithm).digest(NEXT_ITEM));
        assertEquals(expected, next.getHash());
        assertEquals(expected, next.getExtraAttribute(algorithm));
        assertNull(next.getExtraAttribute("ioError"));
    }

    private static Item item(InputStream stream, long length) {
        Item item = new Item() {
            @Override
            public BufferedInputStream getBufferedInputStream() {
                return new BufferedInputStream(stream);
            }
        };
        item.setLength(length);
        item.setPath("hash-regression-test");
        return item;
    }

    private HashTask newTask(String algorithm, MessageDigest digest) throws Exception {
        HashTask task = new HashTask();
        configureTask(task, algorithm, digest);
        return task;
    }

    private void configureTask(HashTask task, String algorithm, MessageDigest digest) throws Exception {
        addDigest(task, algorithm, digest);
        Constructor<Statistics> constructor = Statistics.class.getDeclaredConstructor(ICaseData.class, File.class);
        constructor.setAccessible(true);
        task.stats = constructor.newInstance(null, new File(temporaryFolder.newFolder(), "index"));
    }

    @SuppressWarnings("unchecked")
    private void addDigest(HashTask task, String algorithm, MessageDigest digest) throws Exception {
        Field field = HashTask.class.getDeclaredField("digestMap");
        field.setAccessible(true);
        ((Map<String, MessageDigest>) field.get(task)).put(algorithm, digest);
    }

    private static MessageDigest newDigest(String algorithm) throws Exception {
        return "edonkey".equals(algorithm)
                ? MessageDigest.getInstance("MD4", new BouncyCastleProvider())
                : MessageDigest.getInstance(algorithm);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for the test digest");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static class TrackingDigest extends MessageDigest {
        private final MessageDigest delegate;

        TrackingDigest(MessageDigest delegate) {
            super(delegate.getAlgorithm());
            this.delegate = delegate;
        }

        @Override
        protected void engineUpdate(byte value) {
            engineUpdate(new byte[] { value }, 0, 1);
        }

        @Override
        protected void engineUpdate(byte[] bytes, int offset, int length) {
            delegate.update(bytes, offset, length);
        }

        @Override
        protected byte[] engineDigest() {
            return delegate.digest();
        }

        @Override
        protected void engineReset() {
            delegate.reset();
        }

        @Override
        protected int engineGetDigestLength() {
            return delegate.getDigestLength();
        }
    }
}
