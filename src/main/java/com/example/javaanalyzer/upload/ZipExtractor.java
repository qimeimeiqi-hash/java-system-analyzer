package com.example.javaanalyzer.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * Extracts an uploaded zip safely: rejects entries outside the target directory (Zip Slip) and
 * enforces limits on the uncompressed size and entry count (zip bomb). Backslashes in entry names
 * are treated as separators on every OS, because zips created by Windows PowerShell 5.1 use them.
 */
@Component
public class ZipExtractor {

  /** Zips created by Japanese Windows store entry names in MS932 without the UTF-8 flag. */
  private static final Charset FALLBACK_ENTRY_NAME_CHARSET = Charset.forName("MS932");

  private static final int BUFFER_SIZE = 8192;

  private final long maxExtractedBytes;
  private final int maxEntryCount;

  public ZipExtractor(UploadProperties properties) {
    this.maxExtractedBytes = properties.maxExtractedSize().toBytes();
    this.maxEntryCount = properties.maxEntryCount();
  }

  public void extract(Path zipFile, Path targetDir) {
    Path root = targetDir.toAbsolutePath().normalize();
    try (ZipFile zip = openZip(zipFile)) {
      Files.createDirectories(root);
      extractEntries(zip, root);
    } catch (IOException e) {
      throw new UploadException("zipファイルの展開に失敗しました。", e);
    }
  }

  private static ZipFile openZip(Path zipFile) {
    try {
      return new ZipFile(zipFile.toFile(), StandardCharsets.UTF_8);
    } catch (IOException utf8Failure) {
      try {
        return new ZipFile(zipFile.toFile(), FALLBACK_ENTRY_NAME_CHARSET);
      } catch (IOException e) {
        throw new UploadException("zipファイルとして読み込めません。ファイルが壊れていないか確認してください。", e);
      }
    }
  }

  private void extractEntries(ZipFile zip, Path root) throws IOException {
    int entryCount = 0;
    long extractedBytes = 0;
    Enumeration<? extends ZipEntry> entries = zip.entries();
    while (entries.hasMoreElements()) {
      ZipEntry entry = entries.nextElement();
      if (++entryCount > maxEntryCount) {
        throw new UploadException("zip内のファイル数が上限（" + maxEntryCount + "件）を超えています。");
      }
      String entryName = entry.getName().replace('\\', '/');
      boolean directory = entryName.endsWith("/");
      Path destination = resolveInside(root, entryName, directory);
      if (directory) {
        Files.createDirectories(destination);
        continue;
      }
      Files.createDirectories(destination.getParent());
      try (InputStream in = zip.getInputStream(entry);
          OutputStream out = Files.newOutputStream(destination)) {
        extractedBytes = copyWithinLimit(in, out, extractedBytes);
      }
    }
    if (entryCount == 0) {
      throw new UploadException("zipファイルが空です。");
    }
  }

  /**
   * The destination of an entry, which must stay inside the root. A directory entry may name the
   * root itself, e.g. {@code ./} written by the {@code tar.exe} of Windows; a file entry may not.
   */
  private static Path resolveInside(Path root, String entryName, boolean directory) {
    try {
      Path destination = root.resolve(entryName).normalize();
      if (destination.startsWith(root) && (directory || !destination.equals(root))) {
        return destination;
      }
    } catch (InvalidPathException e) {
      // fall through to the rejection below
    }
    throw new UploadException("zip内に不正なパスのエントリが含まれています: " + entryName);
  }

  /** Copies the stream and returns the new running total, counting actual bytes written. */
  private long copyWithinLimit(InputStream in, OutputStream out, long extractedBytes)
      throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    int read;
    while ((read = in.read(buffer)) != -1) {
      extractedBytes += read;
      if (extractedBytes > maxExtractedBytes) {
        throw new UploadException("zipの展開後のサイズが上限（" + formatSize(maxExtractedBytes) + "）を超えています。");
      }
      out.write(buffer, 0, read);
    }
    return extractedBytes;
  }

  private static String formatSize(long bytes) {
    long megabyte = 1024L * 1024L;
    return bytes % megabyte == 0 ? bytes / megabyte + "MB" : bytes + "バイト";
  }
}
