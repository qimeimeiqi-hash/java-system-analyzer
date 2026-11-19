package com.example.javaanalyzer.analysis.plugin.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.MapperStatementModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.StatementSource;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MyBatisPluginTest {

  private static final String DOCTYPE =
      "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\""
          + " \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n";
  private static final String USER_MAPPER =
      """
      package com.example.mapper;
      import org.apache.ibatis.annotations.Mapper;
      @Mapper public interface UserMapper {
        Object findById(long id);
        int count();
      }
      """;

  @TempDir Path projectRoot;

  private void writeFile(String relativePath, String content) throws IOException {
    writeFile(relativePath, content, StandardCharsets.UTF_8);
  }

  private void writeFile(String relativePath, String content, Charset charset) throws IOException {
    Path file = projectRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.write(file, content.getBytes(charset));
  }

  private static String mapperXml(String namespace, String body) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + DOCTYPE
        + "<mapper namespace=\""
        + namespace
        + "\">\n"
        + body
        + "\n</mapper>\n";
  }

  private ProjectModel analyze(String... sources) {
    for (String source : sources) {
      AnalysisTestSupport.writeSource(projectRoot, source);
    }
    return new ProjectAnalyzer(List.of(new SpringBootPlugin(), new MyBatisPlugin()))
        .analyze(projectRoot);
  }

  private static MapperModel onlyMapper(ProjectModel model) {
    assertThat(model.mappers()).hasSize(1);
    return model.mappers().get(0);
  }

  private MapperStatementModel onlyStatement(String body) throws IOException {
    writeFile(
        "src/main/resources/mapper/UserMapper.xml",
        mapperXml("com.example.mapper.UserMapper", body));
    MapperModel mapper = onlyMapper(analyze(USER_MAPPER));
    assertThat(mapper.statements()).hasSize(1);
    return mapper.statements().get(0);
  }

  private static List<AnalysisIssue> xmlIssues(ProjectModel model) {
    return model.issues().stream().filter(i -> i.kind() == IssueKind.XML_PARSE_ERROR).toList();
  }

  @Test
  @DisplayName("An XML statement is linked to the interface by namespace and to the method by id")
  void linksXmlStatementToMethod() throws IOException {
    MapperStatementModel statement =
        onlyStatement("<select id=\"findById\">SELECT * FROM users WHERE id = #{id}</select>");

    assertThat(statement.tag()).isEqualTo("select");
    assertThat(statement.id()).isEqualTo("findById");
    assertThat(statement.sql()).isEqualTo("SELECT * FROM users WHERE id = #{id}");
    assertThat(statement.sqlResolved()).isTrue();
    assertThat(statement.source()).isEqualTo(StatementSource.XML);
    assertThat(statement.methodName()).isEqualTo("findById");
  }

  @Test
  @DisplayName("The mapper records its interface, namespace and XML file")
  void recordsMapperLocation() throws IOException {
    writeFile(
        "src/main/resources/mapper/UserMapper.xml",
        mapperXml("com.example.mapper.UserMapper", "<select id=\"count\">SELECT 1</select>"));

    MapperModel mapper = onlyMapper(analyze(USER_MAPPER));

    assertThat(mapper.interfaceType()).isEqualTo("com.example.mapper.UserMapper");
    assertThat(mapper.namespace()).isEqualTo("com.example.mapper.UserMapper");
    assertThat(mapper.xmlFiles()).containsExactly("src/main/resources/mapper/UserMapper.xml");
    assertThat(mapper.baseType()).isNull();
  }

  @Test
  @DisplayName("All statement tags are read, in order; other elements such as resultMap are not")
  void readsStatementTags() throws IOException {
    writeFile(
        "src/main/resources/mapper/UserMapper.xml",
        mapperXml(
            "com.example.mapper.UserMapper",
            """
            <resultMap id="userMap" type="User"><id column="id" property="id"/></resultMap>
            <sql id="columns">id, name</sql>
            <select id="s">a</select>
            <insert id="i">b</insert>
            <update id="u">c</update>
            <delete id="d">e</delete>
            """));

    assertThat(onlyMapper(analyze(USER_MAPPER)).statements())
        .extracting(MapperStatementModel::tag)
        .containsExactly("sql", "select", "insert", "update", "delete");
  }

  @Test
  @DisplayName("Dynamic tags stay in the SQL exactly as written, with attribute order and quotes")
  void keepsDynamicTagsAsWritten() throws IOException {
    String sql =
        """
        SELECT <include refid="columns"/> FROM users
            <where>
              <if test='name != null and name != ""'>AND name = #{name}</if>
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </where>""";

    assertThat(onlyStatement("<select id=\"findById\">" + sql + "</select>").sql()).isEqualTo(sql);
  }

  @Test
  @DisplayName("CDATA, comments and escapes stay in the SQL exactly as written")
  void keepsCdataCommentsAndEscapes() throws IOException {
    String sql =
        """
        <!-- 件数を制限する -->
            SELECT * FROM users WHERE age &gt; 1 AND a <![CDATA[ < ]]> b""";

    assertThat(onlyStatement("<select id=\"findById\">" + sql + "</select>").sql()).isEqualTo(sql);
  }

  @Test
  @DisplayName("Japanese text before and inside a statement does not shift the extracted SQL")
  void keepsPositionWithJapaneseText() throws IOException {
    String sql = "SELECT '日本語' FROM ユーザー /* 注釈 */";

    MapperStatementModel statement =
        onlyStatement("<!-- 日本語のコメント -->\n<select id=\"findById\">\n  " + sql + "\n</select>");

    assertThat(statement.sql()).isEqualTo(sql);
  }

  @Test
  @DisplayName("Windows line endings do not shift the extracted SQL and stay in it")
  void keepsPositionWithWindowsLineEndings() throws IOException {
    String sql = "SELECT a,\r\n  b\r\nFROM users";

    MapperStatementModel statement =
        onlyStatement("<!-- 前置き -->\r\n<select id=\"findById\">\r\n  " + sql + "\r\n</select>\r\n");

    assertThat(statement.sql()).isEqualTo(sql);
  }

  @Test
  @DisplayName("An XML encoded in Shift_JIS is read through its declared encoding")
  void readsDeclaredXmlEncoding() throws IOException {
    writeFile(
        "src/main/resources/mapper/UserMapper.xml",
        "<?xml version=\"1.0\" encoding=\"Shift_JIS\"?>\n"
            + "<mapper namespace=\"com.example.mapper.UserMapper\">\n"
            + "<select id=\"count\">SELECT '日本語'</select>\n"
            + "</mapper>\n",
        Charset.forName("Shift_JIS"));

    assertThat(onlyMapper(analyze(USER_MAPPER)).statements().get(0).sql())
        .isEqualTo("SELECT '日本語'");
  }

  @Test
  @DisplayName("A byte order mark at the start of the file does not break reading")
  void readsXmlWithByteOrderMark() throws IOException {
    writeFile(
        "src/main/resources/mapper/UserMapper.xml",
        "﻿" + mapperXml("com.example.mapper.UserMapper", "<select id=\"count\">x</select>"));

    assertThat(onlyMapper(analyze(USER_MAPPER)).statements().get(0).sql()).isEqualTo("x");
  }

  @Test
  @DisplayName("An empty element has empty SQL")
  void readsEmptyElement() throws IOException {
    assertThat(onlyStatement("<select id=\"findById\"/>").sql()).isEmpty();
  }

  @Test
  @DisplayName("A statement without a matching method is kept with no method name")
  void keepsStatementWithoutMethod() throws IOException {
    assertThat(onlyStatement("<select id=\"noSuchMethod\">x</select>").methodName()).isNull();
  }

  @Test
  @DisplayName("A namespace matching no interface is kept as a mapper without interface")
  void keepsNamespaceWithoutInterface() throws IOException {
    writeFile(
        "src/main/resources/mapper/Orphan.xml",
        mapperXml("com.example.mapper.Missing", "<select id=\"a\">x</select>"));

    MapperModel mapper = onlyMapper(analyze("package com.example; public class Plain {}"));

    assertThat(mapper.interfaceType()).isNull();
    assertThat(mapper.namespace()).isEqualTo("com.example.mapper.Missing");
    assertThat(mapper.statements()).extracting(MapperStatementModel::id).containsExactly("a");
  }

  @Test
  @DisplayName("A namespace that names a class rather than an interface has no interface")
  void namespaceOfClassIsNotAnInterface() throws IOException {
    writeFile(
        "src/main/resources/mapper/Plain.xml",
        mapperXml("com.example.Plain", "<select id=\"a\">x</select>"));

    assertThat(onlyMapper(analyze("package com.example; public class Plain {}")).interfaceType())
        .isNull();
  }

  @Test
  @DisplayName("An interface without @Mapper becomes a mapper through the namespace of its XML")
  void namespaceFindsInterfaceWithoutAnnotation() throws IOException {
    writeFile(
        "src/main/resources/mapper/Scanned.xml",
        mapperXml("com.example.Scanned", "<select id=\"load\">x</select>"));

    MapperModel mapper =
        onlyMapper(analyze("package com.example; public interface Scanned { Object load(); }"));

    assertThat(mapper.interfaceType()).isEqualTo("com.example.Scanned");
    assertThat(mapper.statements().get(0).methodName()).isEqualTo("load");
  }

  @Test
  @DisplayName("Several XML files of one namespace are merged in path order")
  void mergesXmlFilesOfOneNamespace() throws IOException {
    writeFile(
        "src/main/resources/mapper/b.xml",
        mapperXml("com.example.mapper.UserMapper", "<select id=\"second\">2</select>"));
    writeFile(
        "src/main/resources/mapper/a.xml",
        mapperXml("com.example.mapper.UserMapper", "<select id=\"first\">1</select>"));

    MapperModel mapper = onlyMapper(analyze(USER_MAPPER));

    assertThat(mapper.xmlFiles())
        .containsExactly("src/main/resources/mapper/a.xml", "src/main/resources/mapper/b.xml");
    assertThat(mapper.statements())
        .extracting(MapperStatementModel::id)
        .containsExactly("first", "second");
  }

  @Test
  @DisplayName("Mapper XML next to the Java sources is found")
  void findsXmlUnderSourceRoot() throws IOException {
    writeFile(
        "src/main/java/com/example/mapper/UserMapper.xml",
        mapperXml("com.example.mapper.UserMapper", "<select id=\"count\">x</select>"));

    assertThat(onlyMapper(analyze(USER_MAPPER)).xmlFiles())
        .containsExactly("src/main/java/com/example/mapper/UserMapper.xml");
  }

  @Test
  @DisplayName("XML under src/test is not read")
  void ignoresXmlOutsideMainSources() throws IOException {
    writeFile(
        "src/test/resources/mapper/UserMapper.xml",
        mapperXml("com.example.mapper.Other", "<select id=\"a\">x</select>"));

    assertThat(analyze("package com.example; public class Plain {}").mappers()).isEmpty();
  }

  @Test
  @DisplayName("Other XML files such as logback.xml are skipped without an issue")
  void skipsOtherXmlFiles() throws IOException {
    writeFile(
        "src/main/resources/logback.xml", "<configuration><root level=\"INFO\"/></configuration>");

    ProjectModel model = analyze("package com.example; public class Plain {}");

    assertThat(model.mappers()).isEmpty();
    assertThat(xmlIssues(model)).isEmpty();
  }

  @Test
  @DisplayName("A malformed XML is reported as a warning and the other files are still read")
  void reportsMalformedXml() throws IOException {
    writeFile("src/main/resources/mapper/Broken.xml", "<mapper namespace=\"a\"><select id=\"x\">");
    writeFile(
        "src/main/resources/mapper/UserMapper.xml",
        mapperXml("com.example.mapper.UserMapper", "<select id=\"count\">x</select>"));

    ProjectModel model = analyze(USER_MAPPER);

    assertThat(xmlIssues(model)).hasSize(1);
    AnalysisIssue issue = xmlIssues(model).get(0);
    assertThat(issue.severity()).isEqualTo(IssueSeverity.WARNING);
    assertThat(issue.file()).isEqualTo("src/main/resources/mapper/Broken.xml");
    assertThat(onlyMapper(model).namespace()).isEqualTo("com.example.mapper.UserMapper");
  }

  @Test
  @DisplayName("A mapper element without namespace is reported, since it can link to nothing")
  void reportsMapperWithoutNamespace() throws IOException {
    writeFile("src/main/resources/mapper/NoNs.xml", "<mapper><select id=\"x\">y</select></mapper>");

    ProjectModel model = analyze("package com.example; public class Plain {}");

    assertThat(model.mappers()).isEmpty();
    assertThat(xmlIssues(model)).hasSize(1);
  }

  @Test
  @DisplayName("An external entity is never read into the IR (XXE)")
  void neverResolvesExternalEntity() throws IOException {
    Path secret = Files.writeString(projectRoot.resolve("secret.txt"), "TOP-SECRET");
    writeFile(
        "src/main/resources/mapper/Evil.xml",
        "<!DOCTYPE mapper [ <!ENTITY xxe SYSTEM \""
            + secret.toUri()
            + "\"> ]>\n"
            + "<mapper namespace=\"com.example.mapper.UserMapper\">"
            + "<select id=\"count\">&xxe;</select></mapper>");

    ProjectModel model = analyze(USER_MAPPER);

    assertThat(model.mappers().toString()).doesNotContain("TOP-SECRET");
    assertThat(model.issues().toString()).doesNotContain("TOP-SECRET");
  }

  @Test
  @DisplayName("Nested entity expansion cannot exhaust the analysis (billion laughs)")
  void survivesEntityExpansionBomb() throws IOException {
    StringBuilder entities = new StringBuilder("<!ENTITY a0 \"aaaaaaaaaa\">");
    for (int i = 1; i < 12; i++) {
      entities.append(
          "<!ENTITY a%d \"&a%d;&a%d;&a%d;&a%d;&a%d;&a%d;&a%d;&a%d;\">"
              .formatted(i, i - 1, i - 1, i - 1, i - 1, i - 1, i - 1, i - 1, i - 1));
    }
    writeFile(
        "src/main/resources/mapper/Bomb.xml",
        "<!DOCTYPE mapper ["
            + entities
            + "]>\n"
            + "<mapper namespace=\"com.example.mapper.UserMapper\">"
            + "<select id=\"count\">&a11;</select></mapper>");

    ProjectModel model =
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> analyze(USER_MAPPER));

    assertThat(model.mappers().toString().length()).isLessThan(100_000);
  }

  @Test
  @DisplayName("A DOCTYPE with an unreachable external DTD does not stop reading")
  void doesNotNeedTheExternalDtd() throws IOException {
    writeFile(
        "src/main/resources/mapper/UserMapper.xml",
        "<!DOCTYPE mapper PUBLIC \"-//x//y\" \"http://localhost:1/never-reachable.dtd\">\n"
            + "<mapper namespace=\"com.example.mapper.UserMapper\">"
            + "<select id=\"count\">x</select></mapper>");

    ProjectModel model =
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> analyze(USER_MAPPER));

    assertThat(onlyMapper(model).statements()).hasSize(1);
    assertThat(xmlIssues(model)).isEmpty();
  }

  @Test
  @DisplayName("An annotation statement records its SQL, tag and method")
  void readsAnnotationStatement() {
    MapperModel mapper =
        onlyMapper(
            analyze(
                """
                package com.example.mapper;
                import org.apache.ibatis.annotations.Mapper;
                import org.apache.ibatis.annotations.Select;
                @Mapper public interface UserMapper {
                  @Select("SELECT * FROM users") Object all();
                }
                """));

    MapperStatementModel statement = mapper.statements().get(0);
    assertThat(statement.tag()).isEqualTo("select");
    assertThat(statement.id()).isEqualTo("all");
    assertThat(statement.sql()).isEqualTo("SELECT * FROM users");
    assertThat(statement.sqlResolved()).isTrue();
    assertThat(statement.source()).isEqualTo(StatementSource.ANNOTATION);
    assertThat(statement.methodName()).isEqualTo("all");
    assertThat(mapper.namespace()).isNull();
    assertThat(mapper.xmlFiles()).isEmpty();
  }

  @Test
  @DisplayName("Insert, update and delete annotations and on-demand imports are read")
  void readsOtherAnnotationStatements() {
    MapperModel mapper =
        onlyMapper(
            analyze(
                """
                package com.example.mapper;
                import org.apache.ibatis.annotations.*;
                public interface UserMapper {
                  @Insert("a") int add();
                  @Update("b") int change();
                  @Delete("c") int remove();
                }
                """));

    assertThat(mapper.statements())
        .extracting(MapperStatementModel::tag)
        .containsExactly("insert", "update", "delete");
  }

  @Test
  @DisplayName("Several annotation values are joined by line breaks")
  void joinsSeveralAnnotationValues() {
    MapperModel mapper =
        onlyMapper(
            analyze(
                """
                package com.example.mapper;
                import org.apache.ibatis.annotations.Select;
                public interface UserMapper {
                  @Select({"SELECT *", "FROM users"}) Object all();
                }
                """));

    assertThat(mapper.statements().get(0).sql()).isEqualTo("SELECT *\nFROM users");
  }

  @Test
  @DisplayName("A constant SQL is kept as its raw expression and marked unresolved")
  void keepsConstantSqlRaw() {
    MapperModel mapper =
        onlyMapper(
            analyze(
                """
                package com.example.mapper;
                import org.apache.ibatis.annotations.Select;
                public interface UserMapper {
                  @Select(Queries.ALL) Object all();
                }
                """));

    assertThat(mapper.statements().get(0).sql()).isEqualTo("Queries.ALL");
    assertThat(mapper.statements().get(0).sqlResolved()).isFalse();
  }

  @Test
  @DisplayName("A provider annotation is kept as written and marked unresolved")
  void keepsProviderAnnotationRaw() {
    MapperModel mapper =
        onlyMapper(
            analyze(
                """
                package com.example.mapper;
                import org.apache.ibatis.annotations.SelectProvider;
                public interface UserMapper {
                  @SelectProvider(type = Sql.class, method = "all") Object all();
                }
                """));

    MapperStatementModel statement = mapper.statements().get(0);
    assertThat(statement.tag()).isEqualTo("select");
    assertThat(statement.sql()).contains("Sql.class", "\"all\"");
    assertThat(statement.sqlResolved()).isFalse();
  }

  @Test
  @DisplayName("XML and annotation statements of one mapper are both kept")
  void mergesXmlAndAnnotationStatements() throws IOException {
    writeFile(
        "src/main/resources/mapper/UserMapper.xml",
        mapperXml("com.example.mapper.UserMapper", "<select id=\"fromXml\">x</select>"));

    MapperModel mapper =
        onlyMapper(
            analyze(
                """
                package com.example.mapper;
                import org.apache.ibatis.annotations.Mapper;
                import org.apache.ibatis.annotations.Select;
                @Mapper public interface UserMapper {
                  Object fromXml();
                  @Select("y") Object fromAnnotation();
                }
                """));

    assertThat(mapper.statements())
        .extracting(MapperStatementModel::id, MapperStatementModel::source)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("fromXml", StatementSource.XML),
            org.assertj.core.groups.Tuple.tuple("fromAnnotation", StatementSource.ANNOTATION));
  }

  @Test
  @DisplayName("BaseMapper is recorded as the base type, and its methods are not inferred")
  void recordsBaseMapperWithoutInferringMethods() {
    ProjectModel model =
        analyze(
            """
            package com.example.mapper;
            import com.baomidou.mybatisplus.core.mapper.BaseMapper;
            public interface ItemMapper extends BaseMapper<Item> {}
            """,
            "package com.example.mapper; public class Item {}");

    MapperModel mapper = onlyMapper(model);
    assertThat(mapper.interfaceType()).isEqualTo("com.example.mapper.ItemMapper");
    assertThat(mapper.baseType().text()).isEqualTo("BaseMapper<Item>");
    assertThat(mapper.baseType().qualifiedName())
        .isEqualTo("com.baomidou.mybatisplus.core.mapper.BaseMapper");
    assertThat(mapper.statements()).isEmpty();
    assertThat(model.types().stream().filter(t -> t.simpleName().equals("ItemMapper")).findFirst())
        .hasValueSatisfying(type -> assertThat(type.methods()).isEmpty());
  }

  @Test
  @DisplayName("BaseMapper imported on demand is recognized")
  void recordsOnDemandImportedBaseMapper() {
    MapperModel mapper =
        onlyMapper(
            analyze(
                """
                package com.example.mapper;
                import com.baomidou.mybatisplus.core.mapper.*;
                public interface ItemMapper extends BaseMapper<Object> {}
                """));

    assertThat(mapper.baseType().qualifiedName())
        .isEqualTo("com.baomidou.mybatisplus.core.mapper.BaseMapper");
  }

  @Test
  @DisplayName("An interface reaching BaseMapper through a project base interface is a mapper")
  void findsBaseMapperThroughProjectInterface() {
    ProjectModel model =
        analyze(
            """
            package com.example.mapper;
            import com.baomidou.mybatisplus.core.mapper.BaseMapper;
            public interface MyBaseMapper<T> extends BaseMapper<T> {}
            """,
            "package com.example.mapper; public interface ItemMapper extends MyBaseMapper<Item> {}",
            "package com.example.mapper; public class Item {}");

    assertThat(model.mappers())
        .extracting(MapperModel::interfaceType, m -> m.baseType().text())
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("com.example.mapper.ItemMapper", "BaseMapper<T>"),
            org.assertj.core.groups.Tuple.tuple(
                "com.example.mapper.MyBaseMapper", "BaseMapper<T>"));
  }

  @Test
  @DisplayName("Interfaces extending each other cannot loop when looking for BaseMapper")
  void survivesCyclicInterfaces() {
    assertThat(
            analyze(
                    "package com.example; public interface First extends Second {}",
                    "package com.example; public interface Second extends First {}")
                .mappers())
        .isEmpty();
  }

  @Test
  @DisplayName("An interface unrelated to MyBatis is not a mapper")
  void ignoresUnrelatedInterfaces() {
    assertThat(analyze("package com.example; public interface Plain { void run(); }").mappers())
        .isEmpty();
  }

  @Test
  @DisplayName("An annotation also named Select from another library is not a statement")
  void ignoresForeignSelectAnnotation() {
    assertThat(
            analyze(
                    """
                    package com.example;
                    import com.other.Select;
                    public interface Plain { @Select("x") void run(); }
                    """)
                .mappers())
        .isEmpty();
  }

  @Test
  @DisplayName("A mapper injected into a service joins the dependency and call graphs")
  void mapperJoinsDependencyAndCallGraphs() {
    ProjectModel model =
        analyze(
            USER_MAPPER,
            """
            package com.example.service;
            import com.example.mapper.UserMapper;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class UserService {
              @Autowired UserMapper userMapper;
              int count() { return userMapper.count(); }
            }
            """);

    CallModel call = model.calls().get(0);
    assertThat(call.targetType()).isEqualTo("com.example.mapper.UserMapper");
    assertThat(call.resolution()).isEqualTo(DependencyResolution.PROJECT_TYPE);
  }

  @Test
  @DisplayName("A project without MyBatis has no mappers and no issues")
  void projectWithoutMyBatisIsUntouched() {
    ProjectModel model = analyze("package com.example; public class Plain {}");

    assertThat(model.mappers()).isEmpty();
    assertThat(model.issues()).isEmpty();
  }
}
