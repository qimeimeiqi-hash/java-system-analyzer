package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.ProjectModel;
import java.util.Comparator;
import java.util.List;

/**
 * The simple call graph of the whole project (FR-18) for the basic design document. The same limits
 * as on the class pages apply (see {@link ClassOverviewGenerator#CALL_NOTES}).
 */
public final class CallGraphTables {

  private CallGraphTables() {}

  public static Table all(ProjectModel model) {
    return new Table(
        List.of("呼び出し元", "依存先フィールド", "呼び出し先", "実引数の数"),
        model.calls().stream()
            .sorted(Comparator.comparing(CallModel::fromType).thenComparingInt(CallModel::line))
            .map(
                call ->
                    List.of(
                        new Cell(
                            Labels.simpleName(call.fromType())
                                + "."
                                + call.fromMethod()
                                + "("
                                + String.join(", ", call.fromParameterTypes())
                                + ")",
                            call.fromType()),
                        new Cell(
                            call.fieldName() + ": " + call.declaredType().text(),
                            call.declaredType().qualifiedName()),
                        callee(call),
                        Cell.of(
                            call.methodReference()
                                ? "メソッド参照"
                                : Labels.orDash(call.argumentCount()))))
            .toList());
  }

  static Cell callee(CallModel call) {
    return call.targetType() != null
        ? new Cell(
            Labels.simpleName(call.targetType()) + "." + call.methodName(), call.targetType())
        : Cell.of(call.methodName() + "（" + Labels.resolution(call.resolution()) + "）");
  }
}
