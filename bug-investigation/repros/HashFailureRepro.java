import iped.engine.task.HashTask;
import iped.data.IItem;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Runs the unchanged production HashTask, with a deterministic injected I/O failure. */
public class HashFailureRepro {
    static final byte[] PREFIX = "readable prefix of damaged evidence|".getBytes(StandardCharsets.UTF_8);
    static final byte[] GOOD = "intact evidence B".getBytes(StandardCharsets.UTF_8);
    static int failures;

    static void check(String label, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        System.out.printf("%s %s%n  expected: %s%n  actual:   %s%n", ok ? "PASS" : "FAIL", label, expected, actual);
        if (!ok) failures++;
    }

    static class Evidence implements IItem {
        final String name;
        final InputStream stream;
        final Map<String, Object> attributes = new HashMap<>();
        String hash;
        Evidence(String name, InputStream stream) { this.name = name; this.stream = stream; }
        public boolean isQueueEnd() { return false; }
        public String getHash() { return hash; }
        public void setHash(String value) { hash = value; }
        public Long getLength() { return name.startsWith("A ") ? PREFIX.length + 4096L : (long) GOOD.length; }
        public Object getExtraAttribute(String name) { return attributes.get(name); }
        public void setExtraAttribute(String name, Object value) { attributes.put(name, value); }
        public InputStream getBufferedInputStream() { return stream; }
        public String getPath() { return name; }
    }

    static class ObservedDigest extends MessageDigest {
        final MessageDigest delegate;
        final CountDownLatch updated = new CountDownLatch(1);
        ObservedDigest() throws Exception { super("MD5"); delegate = MessageDigest.getInstance("MD5"); }
        protected void engineUpdate(byte b) { delegate.update(b); updated.countDown(); }
        protected void engineUpdate(byte[] b, int off, int len) { delegate.update(b, off, len); updated.countDown(); }
        protected byte[] engineDigest() { return delegate.digest(); }
        protected void engineReset() { delegate.reset(); }
    }

    static HashTask task(MessageDigest digest) throws Exception {
        HashTask task = new HashTask();
        Field field = HashTask.class.getDeclaredField("digestMap");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, MessageDigest> map = (Map<String, MessageDigest>) field.get(task);
        map.put("md5", digest);
        return task;
    }

    static String md5(byte[] data) throws Exception {
        return HashTask.getHashString(MessageDigest.getInstance("MD5").digest(data));
    }

    public static void main(String[] args) throws Exception {
        ObservedDigest digest = new ObservedDigest();
        HashTask shared = task(digest);
        HashTask clean = task(MessageDigest.getInstance("MD5"));
        try {
            Evidence control = new Evidence("B on fresh task", new ByteArrayInputStream(GOOD));
            clean.process(control);
            check("control: intact evidence on fresh task", md5(GOOD), control.hash);

            Evidence damaged = new Evidence("A with injected read error", new InputStream() {
                boolean first = true;
                public int read() throws IOException { throw new IOException("Use bulk read"); }
                public int read(byte[] b, int off, int len) throws IOException {
                    if (first) {
                        first = false;
                        System.arraycopy(PREFIX, 0, b, off, PREFIX.length);
                        return PREFIX.length;
                    }
                    try {
                        // Models the ordinary scheduling where hashing completes before the next I/O fails.
                        if (!digest.updated.await(5, TimeUnit.SECONDS)) throw new IOException("Digest did not run");
                    } catch (InterruptedException e) { throw new IOException(e); }
                    throw new IOException("Injected unreadable sector after readable prefix");
                }
            });
            shared.process(damaged);
            check("damaged item records I/O error", "true", damaged.attributes.get("ioError"));
            check("damaged item has no published hash", null, damaged.hash);

            Evidence next = new Evidence("B after A on same task", new ByteArrayInputStream(GOOD));
            shared.process(next);
            check("intact next item must have its own MD5", md5(GOOD), next.hash);
            byte[] mixed = new byte[PREFIX.length + GOOD.length];
            System.arraycopy(PREFIX, 0, mixed, 0, PREFIX.length);
            System.arraycopy(GOOD, 0, mixed, PREFIX.length, GOOD.length);
            System.out.println("MD5(prefix(A) || B): " + md5(mixed));
            check("next item is not marked as an I/O error", null, next.attributes.get("ioError"));

            Evidence later = new Evidence("B once more", new ByteArrayInputStream(GOOD));
            shared.process(later);
            check("control: state recovers after successful digest", md5(GOOD), later.hash);
        } finally {
            shared.finish();
        }
        if (failures != 0) throw new AssertionError(failures + " contract check(s) failed");
    }
}
