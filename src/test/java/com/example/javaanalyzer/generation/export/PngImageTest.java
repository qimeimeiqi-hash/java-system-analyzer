package com.example.javaanalyzer.generation.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PngImageTest {

  @Test
  @DisplayName("Reads the size of a real PNG")
  void readsSize() {
    PngImage image = PngImage.parse(TestImages.png(640, 200)).orElseThrow();

    assertThat(image.width()).isEqualTo(640);
    assertThat(image.height()).isEqualTo(200);
  }

  @Test
  @DisplayName("Rejects data that is not a PNG, truncated data and empty data")
  void rejectsOtherData() {
    assertThat(PngImage.parse("<svg/>".getBytes(StandardCharsets.UTF_8))).isEmpty();
    assertThat(PngImage.parse(Arrays.copyOf(TestImages.png(10, 10), 20))).isEmpty();
    assertThat(PngImage.parse(new byte[0])).isEmpty();
    assertThat(PngImage.parse(null)).isEmpty();
  }

  @Test
  @DisplayName("Fits a 2x image into the page width, keeping the aspect ratio")
  void fitsWidth() {
    PngImage wide = PngImage.parse(TestImages.png(2000, 500)).orElseThrow();
    PngImage small = PngImage.parse(TestImages.png(200, 100)).orElseThrow();

    // 2000 px at 2x is 1000 CSS px = 750 pt, wider than 450 pt, so it is scaled down
    assertThat(wide.pointSize(450)).containsExactly(450.0, 112.5);
    // 200 px at 2x is 100 CSS px = 75 pt, kept as is
    assertThat(small.pointSize(450)).containsExactly(75.0, 37.5);
  }
}
