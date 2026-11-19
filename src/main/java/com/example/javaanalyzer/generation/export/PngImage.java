package com.example.javaanalyzer.generation.export;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

/**
 * A PNG sent by the browser for a diagram. Only the signature and the IHDR size are read; the image
 * itself is not interpreted (SCR-11). The browser draws at twice the screen size for printing.
 */
public record PngImage(byte[] data, int width, int height) {

  /** The browser renders diagrams at this scale (SCR-11 "印刷用に 2 倍の解像度"). */
  static final double RENDER_SCALE = 2.0;

  private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  private static final int HEADER_LENGTH = 24;

  /** Empty unless the data starts with a PNG signature and an IHDR chunk with a positive size. */
  public static Optional<PngImage> parse(byte[] data) {
    if (data == null
        || data.length < HEADER_LENGTH + 4
        || !Arrays.equals(Arrays.copyOf(data, SIGNATURE.length), SIGNATURE)
        || data[12] != 'I'
        || data[13] != 'H'
        || data[14] != 'D'
        || data[15] != 'R') {
      return Optional.empty();
    }
    ByteBuffer buffer = ByteBuffer.wrap(data);
    int width = buffer.getInt(16);
    int height = buffer.getInt(20);
    if (width <= 0 || height <= 0) {
      return Optional.empty();
    }
    return Optional.of(new PngImage(data, width, height));
  }

  /**
   * The size in points at the screen scale (CSS px x 0.75), reduced to {@code maxWidth} if wider.
   *
   * @return width and height in points
   */
  public double[] pointSize(double maxWidth) {
    double pointWidth = width / RENDER_SCALE * 0.75;
    double pointHeight = height / RENDER_SCALE * 0.75;
    if (pointWidth > maxWidth) {
      pointHeight = pointHeight * maxWidth / pointWidth;
      pointWidth = maxWidth;
    }
    return new double[] {pointWidth, pointHeight};
  }
}
