package com.example.javaanalyzer.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class ZipExtractorTest {

  @TempDir Path tempDir;

  private ZipExtractor extractorWithLimits(DataSize maxExtractedSize, int maxEntryCount) {
    return new ZipExtractor(
        new UploadProperties(
            tempDir.resolve("workspace").toString(), maxExtractedSize, maxEntryCount));
  }

  private ZipExtractor defaultExtractor() {
    return extractorWithLimits(DataSize.ofMegabytes(1), 100);
  }

  @Test
  @DisplayName("Extracts files and directories into the target directory keeping their contents")
  void extractsFilesAndDirectories() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("demo/", "");
    entries.put("demo/pom.xml", "<project/>");
    entries.put("demo/src/main/java/App.java", "class App {}");
    Path zip = ZipTestSupport.writeZip(tempDir.resolve("in.zip"), entries);
    Path target = tempDir.resolve("out");

    defaultExtractor().extract(zip, target);

    assertThat(target.resolve("demo/pom.xml")).hasContent("<project/>");
    assertThat(target.resolve("demo/src/main/java/App.java")).hasContent("class App {}");
  }

  @Test
  @DisplayName("Rejects an entry that escapes the target directory with ../ (Zip Slip)")
  void rejectsZipSlipEntry() throws Exception {
    Path zip =
        ZipTestSupport.writeZip(tempDir.resolve("evil.zip"), Map.of("../escaped.txt", "pwned"));
    Path target = tempDir.resolve("out");

    assertThatThrownBy(() -> defaultExtractor().extract(zip, target))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("不正なパス");
    assertThat(tempDir.resolve("escaped.txt")).doesNotExist();
  }

  @Test
  @DisplayName(
      "Skips directory entries that name the target itself (./ written by Windows tar.exe)")
  void skipsDirectoryEntriesNamingTheRoot() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("./", "");
    entries.put("./pom.xml", "<project/>");
    entries.put("./src/", "");
    entries.put("src/../", "");
    entries.put("./src/main/java/App.java", "class App {}");
    Path zip = ZipTestSupport.writeZip(tempDir.resolve("tar.zip"), entries);
    Path target = tempDir.resolve("out");

    defaultExtractor().extract(zip, target);

    assertThat(target.resolve("pom.xml")).hasContent("<project/>");
    assertThat(target.resolve("src/main/java/App.java")).hasContent("class App {}");
  }

  @Test
  @DisplayName("A file entry that names the target itself is still rejected")
  void rejectsFileEntryNamingTheRoot() throws Exception {
    Path zip = ZipTestSupport.writeZip(tempDir.resolve("dot.zip"), Map.of("a/..", "x"));

    assertThatThrownBy(() -> defaultExtractor().extract(zip, tempDir.resolve("out")))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("不正なパス");
  }

  @Test
  @DisplayName("Rejects an entry with an absolute path")
  void rejectsAbsolutePathEntry() throws Exception {
    String absoluteName = tempDir.resolve("absolute.txt").toAbsolutePath().toString();
    Path zip = ZipTestSupport.writeZip(tempDir.resolve("abs.zip"), Map.of(absoluteName, "x"));

    assertThatThrownBy(() -> defaultExtractor().extract(zip, tempDir.resolve("out")))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("不正なパス");
    assertThat(tempDir.resolve("absolute.txt")).doesNotExist();
  }

  @Test
  @DisplayName("Extracts backslash-separated entries (zip created by PowerShell 5.1)")
  void extractsBackslashSeparatedEntries() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("demo\\src\\", "");
    entries.put("demo\\pom.xml", "<project/>");
    entries.put("demo\\src\\main\\java\\App.java", "class App {}");
    entries.put("demo\\empty\\", "");
    Path zip = ZipTestSupport.writeZip(tempDir.resolve("in.zip"), entries);
    Path target = tempDir.resolve("out");

    defaultExtractor().extract(zip, target);

    assertThat(target.resolve("demo/pom.xml")).hasContent("<project/>");
    assertThat(target.resolve("demo/src/main/java/App.java")).hasContent("class App {}");
    assertThat(target.resolve("demo/src")).isDirectory();
    assertThat(target.resolve("demo/empty")).isDirectory();
  }

  @Test
  @DisplayName("Extracts entries mixing backslash and forward slash separators")
  void extractsMixedSeparatorEntries() throws Exception {
    Path zip =
        ZipTestSupport.writeZip(
            tempDir.resolve("mixed.zip"), Map.of("demo\\src/main\\App.java", "class App {}"));
    Path target = tempDir.resolve("out");

    defaultExtractor().extract(zip, target);

    assertThat(target.resolve("demo/src/main/App.java")).hasContent("class App {}");
  }

  @Test
  @DisplayName("Rejects an entry that escapes the target directory with ..\\ (Zip Slip)")
  void rejectsBackslashZipSlipEntry() throws Exception {
    Path zip =
        ZipTestSupport.writeZip(
            tempDir.resolve("evil.zip"), Map.of("demo\\..\\..\\escaped.txt", "pwned"));
    Path target = tempDir.resolve("out");

    assertThatThrownBy(() -> defaultExtractor().extract(zip, target))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("不正なパス");
    assertThat(tempDir.resolve("escaped.txt")).doesNotExist();
  }

  @Test
  @DisplayName("Keeps MS932 names whose second byte is 0x5C intact when separators are normalized")
  void keepsMs932CharactersContainingBackslashByte() throws Exception {
    Path zip =
        ZipTestSupport.writeZip(
            tempDir.resolve("sjis.zip"), Map.of("表\\能力.txt", "memo"), Charset.forName("MS932"));
    Path target = tempDir.resolve("out");

    defaultExtractor().extract(zip, target);

    assertThat(target.resolve("表/能力.txt")).hasContent("memo");
  }

  @Test
  @DisplayName("Fails when the total extracted size exceeds the limit")
  void failsWhenExtractedSizeExceedsLimit() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("a.txt", "x".repeat(600));
    entries.put("b.txt", "x".repeat(600));
    Path zip = ZipTestSupport.writeZip(tempDir.resolve("big.zip"), entries);

    assertThatThrownBy(
            () ->
                extractorWithLimits(DataSize.ofBytes(1000), 100)
                    .extract(zip, tempDir.resolve("out")))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("展開後のサイズ");
  }

  @Test
  @DisplayName("Succeeds when the total extracted size is exactly the limit")
  void succeedsWhenExtractedSizeEqualsLimit() throws Exception {
    Path zip =
        ZipTestSupport.writeZip(tempDir.resolve("edge.zip"), Map.of("a.txt", "x".repeat(1000)));
    Path target = tempDir.resolve("out");

    extractorWithLimits(DataSize.ofBytes(1000), 100).extract(zip, target);

    assertThat(Files.size(target.resolve("a.txt"))).isEqualTo(1000);
  }

  @Test
  @DisplayName("Fails when the number of entries exceeds the limit")
  void failsWhenEntryCountExceedsLimit() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("a.txt", "a");
    entries.put("b.txt", "b");
    entries.put("c.txt", "c");
    Path zip = ZipTestSupport.writeZip(tempDir.resolve("many.zip"), entries);

    assertThatThrownBy(
            () ->
                extractorWithLimits(DataSize.ofMegabytes(1), 2)
                    .extract(zip, tempDir.resolve("out")))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("ファイル数");
  }

  @Test
  @DisplayName("Fails with a readable message when the file is not a valid zip")
  void failsOnCorruptZip() throws Exception {
    Path notZip = Files.writeString(tempDir.resolve("broken.zip"), "this is not a zip archive");

    assertThatThrownBy(() -> defaultExtractor().extract(notZip, tempDir.resolve("out")))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("zip");
  }

  @Test
  @DisplayName("Fails when the zip contains no entries")
  void failsOnEmptyZip() throws Exception {
    Path zip = ZipTestSupport.writeZip(tempDir.resolve("empty.zip"), Map.of());

    assertThatThrownBy(() -> defaultExtractor().extract(zip, tempDir.resolve("out")))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("空");
  }

  @Test
  @DisplayName("Extracts entry names encoded in MS932 (zip created by Japanese Windows)")
  void extractsMs932EncodedEntryNames() throws Exception {
    Path zip =
        ZipTestSupport.writeZip(
            tempDir.resolve("sjis.zip"), Map.of("設計/メモ.txt", "memo"), Charset.forName("MS932"));
    Path target = tempDir.resolve("out");

    defaultExtractor().extract(zip, target);

    assertThat(target.resolve("設計/メモ.txt")).hasContent("memo");
  }
}
