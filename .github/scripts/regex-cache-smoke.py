from pathlib import Path
import json, os, shutil, subprocess, tempfile

repo = Path.cwd()
release = repo / "target/release/iped-4.4.0-SNAPSHOT"
work = Path(tempfile.mkdtemp(prefix="iped-regex-smoke-"))
evidence = work / "evidence"
evidence.mkdir()
(evidence / "match.txt").write_text("A synthetic record: RX2940-12345.\n")
(evidence / "control.txt").write_text("No matching identifier here.\n")
logs = repo / "smoke-results"
logs.mkdir(exist_ok=True)
profile = release / "profiles/fastmode/IPEDConfig.txt"
s = profile.read_text(encoding="utf-8-sig")
for key in ["enableRegexSearch", "enableFileParsing", "indexFileContents"]:
    s = s.replace(key + " = false", key + " = true")
profile.write_text(s, encoding="utf-8")
(release / "conf/RegexConfig.txt").write_text("REGRESSION2940, false = RX2940-[0-9]{5}\n")
local = release / "LocalConfig.txt"
local.write_text(local.read_text(encoding="utf-8-sig").replace("numThreads = default", "numThreads = 2"), encoding="utf-8")
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe")
javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac.exe")
classes = work / "classes"
classes.mkdir()
subprocess.run([javac, "-cp", str(release / "lib/*"), "-d", str(classes), str(repo / ".github/scripts/CheckIndex.java")], check=True)
results = []
for mode in ["fresh", "valid", "empty", "blocked"]:
    home = work / mode
    cache = home / ".iped/regexAutomata.cache"
    cache.parent.mkdir(parents=True)
    if mode == "valid":
        shutil.copyfile(work / "fresh/.iped/regexAutomata.cache", cache)
    elif mode == "empty":
        cache.touch()
    elif mode == "blocked":
        cache.mkdir()
        (cache / "blocker").write_text("Prevent replacing this directory with a cache file")
    case = work / ("case-" + mode)
    command = [java, "-Djava.awt.headless=true", "-Duser.home=" + str(home), "-jar", str(release / "iped.jar"), "-Xmx1G", "-d", str(evidence), "-o", str(case), "-profile", "fastmode", "--nogui", "-log", str(logs / (mode + "-processing.log"))]
    (logs / (mode + "-command.json")).write_text(json.dumps(command))
    with (logs / (mode + "-console.log")).open("w") as log:
        run = subprocess.run(command, cwd=release, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    if run.returncode:
        print((logs / (mode + "-console.log")).read_text(errors="replace"))
        raise RuntimeError(mode + " processing failed: " + str(run.returncode))
    indexes = {p.parent for p in case.rglob("segments_*")}
    assert len(indexes) == 1, indexes
    check = subprocess.run([java, "-cp", str(classes) + os.pathsep + str(release / "lib/*"), "CheckIndex", str(next(iter(indexes)))], capture_output=True, text=True)
    (logs / (mode + "-index.txt")).write_text(check.stdout + check.stderr)
    if check.returncode:
        raise RuntimeError(mode + " index verification failed: " + check.stdout + check.stderr)
    if mode != "blocked":
        assert cache.stat().st_size > 16
    else:
        assert cache.is_dir()
    result = {"scenario": mode, "exit": run.returncode, "index": check.stdout.strip()}
    results.append(result)
    print(json.dumps(result), flush=True)
(logs / "summary.json").write_text(json.dumps(results, indent=2))
