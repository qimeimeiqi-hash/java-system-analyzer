package com.example.javaanalyzer.upload;

/** Upload / extraction failure whose message is shown to the user as-is. */
public class UploadException extends RuntimeException {

  public UploadException(String message) {
    super(message);
  }

  public UploadException(String message, Throwable cause) {
    super(message, cause);
  }
}
