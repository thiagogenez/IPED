#!/usr/bin/env python3
"""Compile unchanged production sources against minimal external collaborators.

No hashing, date parsing, seeking, or searching implementation is mocked. Stand-ins
only supply logging/configuration/item and UI surfaces needed for compilation.
Build output goes to a temporary directory, never to Maven/source directories.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
EVIDENCE = HERE.parent / "evidence"

STUBS = {
    "org/slf4j/Logger.java": """package org.slf4j; public interface Logger {
        default void warn(String text, Object... args) { System.out.println("LOG " + java.util.Arrays.toString(args)); }
    }""",
    "org/slf4j/LoggerFactory.java": """package org.slf4j; public class LoggerFactory {
        public static Logger getLogger(Class<?> c) { return new Logger() {}; }
    }""",
    "iped/configuration/Configurable.java": "package iped.configuration; public interface Configurable<T> {}",
    "iped/data/IItem.java": """package iped.data; public interface IItem {
        boolean isQueueEnd(); String getHash(); void setHash(String value); Long getLength();
        Object getExtraAttribute(String name); void setExtraAttribute(String name, Object value);
        java.io.InputStream getBufferedInputStream() throws java.io.IOException; String getPath();
    }""",
    "iped/engine/config/HashTaskConfig.java": """package iped.engine.config;
        public class HashTaskConfig implements iped.configuration.Configurable<Object> {
        public boolean isEnabled() { return true; }
        public java.util.List<String> getAlgorithms() { return java.util.List.of("md5"); }
    }""",
    "iped/engine/config/ConfigurationManager.java": """package iped.engine.config;
        public class ConfigurationManager { public <T> T findObject(Class<T> type) { return null; } }
    """,
    "iped/engine/task/AbstractTask.java": """package iped.engine.task;
        public abstract class AbstractTask {
        protected final Stats stats = new Stats();
        public static class Stats { public void incIoErrors() {} }
        public abstract boolean isEnabled();
        public abstract java.util.List<iped.configuration.Configurable<?>> getConfigurables();
        public abstract void init(iped.engine.config.ConfigurationManager config) throws Exception;
        public abstract void finish() throws Exception;
    }""",
    "iped/engine/task/IgnoreHardLinkTask.java": """package iped.engine.task;
        public class IgnoreHardLinkTask { public static final String IGNORE_HARDLINK_ATTR = "ignoredHardLink"; }
    """,
    "iped/parsers/whatsapp/WhatsAppParser.java": """package iped.parsers.whatsapp;
        public class WhatsAppParser {
        public static final String SHA256_ENABLED_SYSPROP = "sha256Enabled";
        public static final String HASH_TASK_ENABLED_SYSPROP = "hashTaskEnabled";
    }""",
    # DateUtils is only an unused import/Javadoc reference in the tested DateUtil.
    "org/apache/tika/utils/DateUtils.java": "package org.apache.tika.utils; public class DateUtils {}",
    "iped/app/ui/App.java": "package iped.app.ui; public class App { public static App get() { return new App(); } }",
    "iped/app/ui/Messages.java": "package iped.app.ui; public class Messages { public static String getString(String key) { return key; } }",
    "iped/viewers/api/CancelableWorker.java": """package iped.viewers.api;
        public abstract class CancelableWorker<T,V> extends javax.swing.SwingWorker<T,V> {}""",
    "iped/viewers/util/ProgressDialog.java": """package iped.viewers.util;
        public class ProgressDialog {
        public ProgressDialog(Object app, Object worker, int lines) {} public void close() {}
        public void setMaximum(long value) {} public void setProgress(long value) {}
        public void setNote(String value) {} public boolean isCanceled() { return false; }
    }""",
    "org/exbin/deltahex/swing/CodeArea.java": """package org.exbin.deltahex.swing;
        public class CodeArea { public void revealPosition(long pos, Object section) {}
        public Object getActiveSection() { return null; } public void setCaretPosition(long pos) {}
        public void repaint() {} }
    """,
    "org/exbin/deltahex/highlight/swing/HighlightCodeAreaPainter.java": """package org.exbin.deltahex.highlight.swing;
        public class HighlightCodeAreaPainter {
        private java.util.List<SearchMatch> matches = new java.util.ArrayList<>();
        private int current;
        public static class SearchMatch {
            private long position; private int length;
            public void setPosition(long value) { position = value; } public long getPosition() { return position; }
            public void setLength(int value) { length = value; } public int getLength() { return length; }
        }
        public void clearMatches() { matches.clear(); }
        public void setMatches(java.util.List<SearchMatch> value) { matches = value; }
        public java.util.List<SearchMatch> getMatches() { return matches; }
        public void setCurrentMatchIndex(int value) { current = value; }
        public SearchMatch getCurrentMatch() { return matches.get(current); }
    }""",
    "iped/viewers/HexViewerPlus.java": """package iped.viewers;
        public class HexViewerPlus {
        public static class Hits { public int totalHits; public int currentHit; }
        public interface HexSearcher {
            void doSearch(org.exbin.deltahex.swing.CodeArea codeArea,
                org.exbin.deltahex.highlight.swing.HighlightCodeAreaPainter painter, Hits hits,
                iped.io.SeekableInputStream data, java.nio.charset.Charset charset,
                java.util.Set<String> terms, long offset, boolean text, boolean ignoreCase,
                javax.swing.JLabel result, int maxHits) throws Exception;
        }
    }""",
}

PRODUCTION = [
    "iped-api/src/main/java/iped/io/SeekableInputStream.java",
    "iped-utils/src/main/java/iped/utils/SeekableFileInputStream.java",
    "iped-utils/src/main/java/iped/utils/LimitedSeekableInputStream.java",
    "iped-utils/src/main/java/iped/utils/DateUtil.java",
    "iped-engine/src/main/java/iped/engine/task/HashTask.java",
    "iped-app/src/main/java/iped/app/ui/viewers/HexSearcherImpl.java",
]

def main():
    EVIDENCE.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="iped-inspection-") as tmp:
        tmp = Path(tmp)
        stubs = []
        for name, body in STUBS.items():
            path = tmp / "stubs" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body)
            stubs.append(str(path))
        classes = tmp / "classes"
        classes.mkdir()
        cmd = ["rtk", "proxy", "javac", "-encoding", "UTF-8", "-d", str(classes)]
        cmd += stubs + [str(ROOT / p) for p in PRODUCTION] + [str(p) for p in sorted(HERE.glob("*.java"))]
        subprocess.run(cmd, check=True)
        failed = False
        for name in ["HashFailureRepro", "DateTimezoneRepro", "iped.app.ui.viewers.HexUtf8Repro", "SeekBoundaryRepro"]:
            result = subprocess.run(["rtk", "proxy", "java", "-cp", str(classes), name], text=True, capture_output=True, timeout=30)
            output = result.stdout + result.stderr
            failed |= result.returncode != 0
            (EVIDENCE / (name.rsplit(".", 1)[-1] + ".txt")).write_text(output)
            print(f"\n--- {name} (exit {result.returncode}) ---\n{output}", flush=True)
        print("Nonzero Java exits identify failed product expectations. Read each log; the runner continues to collect all results.")
        return int(failed)

if __name__ == "__main__":
    raise SystemExit(main())
