package com.example.javaanalyzer.ir.io;

/**
 * Saved IR was written with another schema version. Old IR is not migrated; the project has to be
 * uploaded and analyzed again.
 */
public class IrSchemaVersionException extends IrStorageException {

  private final int foundVersion;

  public IrSchemaVersionException(int foundVersion, int supportedVersion) {
    super(
        "Unsupported IR schema version " + foundVersion + " (supported: " + supportedVersion + ")");
    this.foundVersion = foundVersion;
  }

  public int foundVersion() {
    return foundVersion;
  }
}
