package com.example.javaanalyzer.generation;

/** One labeled value of a key-value block, such as the basic information of a class. */
public record KeyValue(String key, Cell value) {

  public static KeyValue of(String key, String value) {
    return new KeyValue(key, Cell.of(value));
  }
}
