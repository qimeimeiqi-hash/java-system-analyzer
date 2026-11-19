package com.example.javaanalyzer.ir.io;

/** Saved IR could not be written or read, or has an unsupported format. */
public class IrStorageException extends RuntimeException {

  public IrStorageException(String message) {
    super(message);
  }

  public IrStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
