package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.RelationKind;
import com.example.javaanalyzer.ir.TypeKind;
import java.util.Collection;

/** Japanese display names of IR values, shared by all outputs (screen-design 2.2, 2.3, SCR-07). */
public final class Labels {

  /** Shown for a value that does not exist, such as a missing request DTO. */
  public static final String DASH = "-";

  /** Shown for a name the source does not state; default names are never guessed (NFR-06). */
  public static final String UNSPECIFIED = "未指定";

  public static final String UNRESOLVED_SUFFIX = "（未解決）";

  private Labels() {}

  public static String orDash(Object value) {
    return value == null ? DASH : value.toString();
  }

  public static String orUnspecified(String value) {
    return value == null ? UNSPECIFIED : value;
  }

  public static String join(Collection<String> values) {
    return values.isEmpty() ? DASH : String.join(", ", values);
  }

  public static String yesNo(Boolean value) {
    return value == null ? DASH : value ? "○" : "×";
  }

  public static String simpleName(String qualifiedName) {
    return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
  }

  /** A package name for display: the default package (empty name) is named. */
  public static String packageName(String name) {
    return name.isEmpty() ? "デフォルトパッケージ" : name;
  }

  public static String packageOf(String qualifiedName) {
    int dot = qualifiedName.lastIndexOf('.');
    return dot < 0 ? "" : qualifiedName.substring(0, dot);
  }

  public static String role(ComponentRole role) {
    return switch (role) {
      case CONTROLLER -> "Controller";
      case SERVICE -> "Service";
      case REPOSITORY -> "Repository";
      case CONFIGURATION -> "Configuration";
      case COMPONENT -> "Component";
    };
  }

  public static String typeKind(TypeKind kind) {
    return switch (kind) {
      case CLASS -> "クラス";
      case INTERFACE -> "インタフェース";
      case ENUM -> "Enum";
      case RECORD -> "レコード";
      case ANNOTATION -> "注釈型";
    };
  }

  public static String entityKind(EntityKind kind) {
    return switch (kind) {
      case ENTITY -> "エンティティ";
      case MAPPED_SUPERCLASS -> "MappedSuperclass";
      case EMBEDDABLE -> "Embeddable";
    };
  }

  public static String relationKind(RelationKind kind) {
    return switch (kind) {
      case ONE_TO_ONE -> "OneToOne";
      case ONE_TO_MANY -> "OneToMany";
      case MANY_TO_ONE -> "ManyToOne";
      case MANY_TO_MANY -> "ManyToMany";
    };
  }

  public static String injectionKind(InjectionKind kind) {
    return switch (kind) {
      case FIELD -> "フィールド";
      case CONSTRUCTOR -> "コンストラクタ";
      case SETTER -> "setter";
    };
  }

  /** How a dependency was resolved (screen-design 2.3). */
  public static String resolution(DependencyResolution resolution) {
    return switch (resolution) {
      case PROJECT_TYPE -> "直接解決";
      case SINGLE_IMPLEMENTATION -> "単一実装から解決";
      case COLLECTION_ELEMENT -> "コレクションの要素";
      case AMBIGUOUS -> "実装が複数";
      case EXTERNAL -> "外部ライブラリ";
      case UNRESOLVED -> "型を特定できない";
    };
  }

  public static String severity(IssueSeverity severity) {
    return switch (severity) {
      case ERROR -> "エラー";
      case WARNING -> "警告";
    };
  }

  public static String issueKind(IssueKind kind) {
    return switch (kind) {
      case SYNTAX_ERROR -> "構文エラー";
      case UNRESOLVED_IMPORT -> "未解決 import";
      case ENCODING_FALLBACK -> "文字コード";
      case XML_PARSE_ERROR -> "XML 解析エラー";
      case VIEW_SETTING_UNRESOLVED -> "View 設定";
      case CIRCULAR_DEPENDENCY -> "循環依存";
      case DUPLICATE_TYPE -> "型の重複";
    };
  }

  /** Where a member comes from, e.g. "Lombok（@Data）" (screen-design 2.2). */
  public static String origin(MemberOrigin origin, String generatedBy) {
    if (origin == MemberOrigin.LOMBOK) {
      return generatedBy == null ? "Lombok" : "Lombok（@" + generatedBy + "）";
    }
    return "ソース";
  }
}
