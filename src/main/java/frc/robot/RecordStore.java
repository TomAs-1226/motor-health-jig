package frc.robot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The jig's records on disk: one JSON file per test under {@code tests/}, and a one-line-per-test
 * {@code log.csv} for reading over SSH. {@code tools/pull_records.py} copies {@code tests/} into the
 * repo's {@code records/}.
 */
public final class RecordStore {
  static final String CSV_HEADER =
      "testedAt,motorNumber,serial,canId,motorType,firmware,label,verdict,score,kvRpsPerV,projectedFreeRpm12V,"
          + "freeCurrentA,coastHalfS,tempRiseC,summary";

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final Path dir;

  public RecordStore(Path dir) {
    this.dir = dir;
  }

  public Path dir() {
    return dir;
  }

  public Path testsDir() {
    return dir.resolve("tests");
  }

  /** Write a record as {@code tests/<stem>.json}, whole or not at all. */
  public Path save(Map<String, Object> record, String stem) throws IOException {
    Files.createDirectories(testsDir());
    Path file = testsDir().resolve(stem + ".json");
    Path tmp = testsDir().resolve(stem + ".json.tmp");
    Files.writeString(tmp, MAPPER.writeValueAsString(record) + "\n", StandardCharsets.UTF_8);
    try {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
    return file;
  }

  /** Append one row to {@code log.csv}, writing the header first if the file is new. */
  public void appendLog(List<String> fields) throws IOException {
    Files.createDirectories(dir);
    Path log = dir.resolve("log.csv");
    StringBuilder sb = new StringBuilder();
    if (!Files.exists(log)) {
      sb.append(CSV_HEADER).append('\n');
    }
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(csv(fields.get(i)));
    }
    sb.append('\n');
    Files.writeString(
        log,
        sb.toString(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  /** Every record in {@code tests/}. A file that does not parse is skipped, not fatal. */
  public List<Map<String, Object>> loadAll() {
    List<Map<String, Object>> out = new ArrayList<>();
    if (!Files.isDirectory(testsDir())) {
      return out;
    }
    try (DirectoryStream<Path> files = Files.newDirectoryStream(testsDir(), "*.json")) {
      for (Path f : files) {
        try {
          out.add(MAPPER.readValue(f.toFile(), new TypeReference<Map<String, Object>>() {}));
        } catch (IOException e) {
          System.err.println("[MotorHealth] Skipping unreadable record " + f + ": " + e);
        }
      }
    } catch (IOException e) {
      System.err.println("[MotorHealth] Could not list " + testsDir() + ": " + e);
    }
    return out;
  }

  static String csv(String s) {
    if (s == null) {
      return "";
    }
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
      return "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\"";
    }
    return s;
  }

  /** A string safe in a file name. */
  static String fileSafe(String s) {
    return s.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}
