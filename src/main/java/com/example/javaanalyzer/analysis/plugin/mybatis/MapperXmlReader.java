package com.example.javaanalyzer.analysis.plugin.mybatis;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the statements of a MyBatis mapper XML. The SQL is cut out of the source text by position,
 * because a parsed tree cannot give back dynamic tags, attribute order, CDATA and comments as they
 * were written.
 *
 * <p>DTD processing is turned off: mapper files only name a DTD for validation, so nothing is
 * fetched and no entity is ever expanded, which rules out XXE and entity expansion attacks. A file
 * using a custom entity therefore cannot be read and is reported by the caller.
 */
final class MapperXmlReader {

  private static final Set<String> STATEMENT_TAGS =
      Set.of("select", "insert", "update", "delete", "sql");
  private static final String ROOT_ELEMENT = "mapper";
  private static final String END_TAG_START = "</";
  private static final String BYTE_ORDER_MARK = "﻿";

  /**
   * @param id null when the element has no {@code id}
   */
  record Statement(String tag, String id, String sql) {}

  /**
   * @param namespace null when the {@code mapper} element has no namespace
   */
  record MapperXml(String namespace, List<Statement> statements) {}

  private MapperXmlReader() {}

  /**
   * @return empty when the root element is not {@code mapper}, as in {@code logback.xml}
   * @throws XMLStreamException when the content cannot be read as XML
   */
  static Optional<MapperXml> read(byte[] content) throws XMLStreamException {
    String text = decode(content);
    XMLStreamReader reader = newFactory().createXMLStreamReader(new StringReader(text));
    try {
      return readDocument(reader, text);
    } finally {
      reader.close();
    }
  }

  private static XMLInputFactory newFactory() {
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return factory;
  }

  /** Decodes with the encoding found by the parser, so that positions count characters. */
  private static String decode(byte[] content) throws XMLStreamException {
    XMLStreamReader reader = newFactory().createXMLStreamReader(new ByteArrayInputStream(content));
    try {
      String declared = reader.getCharacterEncodingScheme();
      String detected = declared != null ? declared : reader.getEncoding();
      Charset charset = detected != null ? charsetOf(detected) : StandardCharsets.UTF_8;
      String text = new String(content, charset);
      return text.startsWith(BYTE_ORDER_MARK) ? text.substring(1) : text;
    } finally {
      reader.close();
    }
  }

  private static Charset charsetOf(String name) throws XMLStreamException {
    try {
      return Charset.forName(name);
    } catch (IllegalArgumentException e) {
      throw new XMLStreamException("Unsupported encoding: " + name, e);
    }
  }

  private static Optional<MapperXml> readDocument(XMLStreamReader reader, String text)
      throws XMLStreamException {
    String namespace = null;
    List<Statement> statements = new ArrayList<>();
    int depth = 0;
    String tag = null;
    String id = null;
    int sqlStart = 0;
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
        if (depth == 1) {
          if (!ROOT_ELEMENT.equals(reader.getLocalName())) {
            return Optional.empty();
          }
          namespace = reader.getAttributeValue(null, "namespace");
        } else if (depth == 2 && STATEMENT_TAGS.contains(reader.getLocalName())) {
          tag = reader.getLocalName();
          id = reader.getAttributeValue(null, "id");
          sqlStart = reader.getLocation().getCharacterOffset();
        }
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        if (depth == 2 && tag != null) {
          statements.add(new Statement(tag, id, sqlBetween(text, sqlStart, reader.getLocation())));
          tag = null;
        }
        depth--;
      }
    }
    return Optional.of(new MapperXml(namespace, statements));
  }

  /** An empty element such as {@code <select/>} has no end tag after its start. */
  private static String sqlBetween(
      String text, int sqlStart, javax.xml.stream.Location elementEnd) {
    int endTag = text.lastIndexOf(END_TAG_START, elementEnd.getCharacterOffset() - 1);
    return endTag < sqlStart ? "" : text.substring(sqlStart, endTag).strip();
  }
}
