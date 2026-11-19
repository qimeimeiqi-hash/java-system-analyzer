package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.project;
import static com.example.javaanalyzer.generation.IrFixtures.type;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.MapperStatementModel;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.StatementSource;
import com.example.javaanalyzer.ir.TypeKind;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MyBatisReportGeneratorTest {

  private static final String BOOK_MAPPER = "com.example.library.mapper.BookMapper";
  private static final String AUTHOR_MAPPER = "com.example.library.mapper.AuthorMapper";

  private static MethodModel method(String name, TypeRef returnType, ParameterModel... parameters) {
    return new MethodModel(
        name,
        returnType,
        List.of(parameters),
        List.of(),
        List.of(),
        null,
        null,
        List.of(),
        MemberOrigin.SOURCE,
        null);
  }

  private static TypeModel mapperType(String name, MethodModel... methods) {
    int dot = name.lastIndexOf('.');
    return new TypeModel(
        name,
        name.substring(dot + 1),
        name.substring(0, dot),
        TypeKind.INTERFACE,
        null,
        List.of(),
        List.of(),
        null,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(methods));
  }

  /** mybatis-demo: BookMapper with XML, AuthorMapper extending BaseMapper, an orphan namespace. */
  private static ProjectModel library() {
    TypeRef book = type("com.example.library.domain.Book");
    return project()
        .types(
            mapperType(
                BOOK_MAPPER,
                method(
                    "findById", book, new ParameterModel("id", type("java.lang.Long"), List.of())),
                method("countAll", type("java.lang.Long"))),
            mapperType(AUTHOR_MAPPER))
        .mappers(
            new MapperModel(
                BOOK_MAPPER,
                BOOK_MAPPER,
                List.of("src/main/resources/mapper/BookMapper.xml"),
                null,
                List.of(
                    new MapperStatementModel(
                        "sql", "columns", "id, title", true, StatementSource.XML, null),
                    new MapperStatementModel(
                        "select",
                        "findById",
                        "SELECT <include refid=\"columns\"/> FROM books WHERE id = #{id}",
                        true,
                        StatementSource.XML,
                        "findById"),
                    new MapperStatementModel(
                        "select",
                        "search",
                        "SqlTexts.SEARCH",
                        false,
                        StatementSource.ANNOTATION,
                        null))),
            new MapperModel(
                AUTHOR_MAPPER,
                null,
                List.of(),
                new TypeRef(
                    "BaseMapper<Author>",
                    "com.baomidou.mybatisplus.core.mapper.BaseMapper",
                    List.of()),
                List.of()),
            new MapperModel(
                null,
                "com.example.RemovedMapper",
                List.of("mapper/Removed.xml"),
                null,
                List.of(
                    new MapperStatementModel(
                        "delete", "purge", "DELETE FROM x", true, StatementSource.XML, null))))
        .build();
  }

  @Test
  @DisplayName("Mappers with an interface are listed by name with namespace, XML and base type")
  void mappersWithInterface() {
    List<MapperReport> mappers = MyBatisReportGenerator.mappers(library());

    assertThat(mappers)
        .extracting(MapperReport::title)
        .containsExactly("AuthorMapper", "BookMapper");
    assertThat(mappers.get(0).information())
        .extracting(kv -> kv.key() + "=" + kv.value().text())
        .containsExactly("namespace=-", "XML ファイル=-", "基底インタフェース=BaseMapper<Author>");
    assertThat(mappers.get(0).notes()).containsExactly("基底インタフェースの自動生成メソッドは表示しない。");
    assertThat(mappers.get(1).notes()).isEmpty();
  }

  @Test
  @DisplayName("Mapper methods show whether a statement exists; statements keep the SQL as written")
  void methodsAndStatements() {
    MapperReport book = MyBatisReportGenerator.mappers(library()).get(1);

    assertThat(book.methods().headers()).containsExactly("メソッド", "引数", "戻り値", "SQL");
    assertThat(book.methods().texts())
        .containsExactly(
            List.of("findById", "Long id", "Book", "select findById"),
            List.of("countAll", "-", "Long", "SQL なし"));
    assertThat(book.statements())
        .extracting(StatementReport::heading, StatementReport::sql)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("sql columns（XML）", "id, title"),
            org.assertj.core.groups.Tuple.tuple(
                "select findById（XML）",
                "SELECT <include refid=\"columns\"/> FROM books WHERE id = #{id}"),
            org.assertj.core.groups.Tuple.tuple("select search（注解）", "SqlTexts.SEARCH"));
    assertThat(book.statements().get(0).notes()).containsExactly("対応メソッドなし");
    assertThat(book.statements().get(1).notes()).isEmpty();
    assertThat(book.statements().get(2).notes()).containsExactly("対応メソッドなし", "未解決（式のまま表示）");
  }

  @Test
  @DisplayName("A namespace without an interface is listed separately and not dropped")
  void namespacesWithoutInterface() {
    List<MapperReport> orphans = MyBatisReportGenerator.namespacesWithoutInterface(library());

    assertThat(orphans)
        .extracting(MapperReport::title)
        .containsExactly("com.example.RemovedMapper");
    assertThat(orphans.get(0).methods()).isNull();
    assertThat(orphans.get(0).statements())
        .extracting(StatementReport::heading)
        .containsExactly("delete purge（XML）");
  }

  @Test
  @DisplayName("The statement table for the exports lists every statement of every mapper")
  void statementTable() {
    Table table = MyBatisReportGenerator.statementTable(library());

    assertThat(table.headers())
        .containsExactly("Mapper", "namespace", "タグ", "id", "種別", "対応メソッド", "SQL");
    assertThat(table.texts())
        .extracting(row -> row.get(0) + " " + row.get(3) + " " + row.get(5))
        .containsExactly(
            "BookMapper columns -",
            "BookMapper findById findById",
            "BookMapper search -",
            "- purge -");
  }
}
