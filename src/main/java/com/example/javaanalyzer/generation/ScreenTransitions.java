package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.EndpointRef;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TemplateLinkModel;
import com.example.javaanalyzer.ir.ViewReturnKind;
import com.example.javaanalyzer.ir.ViewReturnModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Screen-to-screen transitions (FR-17, SCR-10), derived from the IR links of No.22: a link of a
 * template reaches controller methods, whose returned views are the next screens. A {@code
 * redirect:} or {@code forward:} is followed one step only, to the views of its targets.
 */
public final class ScreenTransitions {

  private static final String GET = "GET";

  /**
   * @param viewScreens templates returned directly as views by the target methods
   * @param redirectScreens templates reached through a redirect or forward of the target methods
   */
  public record Transition(
      ViewTemplateModel from,
      TemplateLinkModel link,
      Set<String> viewScreens,
      Set<String> redirectScreens) {

    public Transition {
      viewScreens = Set.copyOf(viewScreens);
      redirectScreens = Set.copyOf(redirectScreens);
    }

    /** All next screens, sorted. */
    public List<String> nextScreens() {
      Set<String> all = new TreeSet<>(viewScreens);
      all.addAll(redirectScreens);
      return List.copyOf(all);
    }
  }

  private ScreenTransitions() {}

  /** One transition per link, templates sorted by path and links in source order. */
  public static List<Transition> of(ProjectModel model) {
    List<Transition> transitions = new ArrayList<>();
    for (ViewTemplateModel view : sortedViews(model)) {
      for (TemplateLinkModel link : view.links()) {
        Set<String> viewScreens = new TreeSet<>();
        Set<String> redirectScreens = new TreeSet<>();
        for (EndpointRef target : link.targets()) {
          for (ViewReturnModel viewReturn : returnsOf(model, target, link.httpMethod())) {
            if (viewReturn.kind() == ViewReturnKind.VIEW) {
              viewScreens.addAll(viewReturn.templatePaths());
            } else if (viewReturn.kind() != null) {
              // A redirect is followed by a GET; the method of a forward is the original one,
              // which is not known here, so a forward reaches every overload.
              String nextMethod = viewReturn.kind() == ViewReturnKind.REDIRECT ? GET : null;
              for (EndpointRef next : viewReturn.targets()) {
                for (ViewReturnModel nextReturn : returnsOf(model, next, nextMethod)) {
                  if (nextReturn.kind() == ViewReturnKind.VIEW) {
                    redirectScreens.addAll(nextReturn.templatePaths());
                  }
                }
              }
            }
          }
        }
        transitions.add(new Transition(view, link, viewScreens, redirectScreens));
      }
    }
    return transitions;
  }

  public static List<ViewTemplateModel> sortedViews(ProjectModel model) {
    return model.views().stream().sorted(Comparator.comparing(ViewTemplateModel::path)).toList();
  }

  /**
   * The view returns of the endpoints a reference names. Overloaded handlers share a reference, so
   * only the overloads accepting the HTTP method count; an endpoint without methods accepts all.
   *
   * @param httpMethod the method of the request, or null when it is not known
   */
  private static List<ViewReturnModel> returnsOf(
      ProjectModel model, EndpointRef ref, String httpMethod) {
    return model.endpoints().stream()
        .filter(
            e ->
                e.controllerType().equals(ref.controllerType())
                    && e.methodName().equals(ref.methodName()))
        .filter(
            e ->
                httpMethod == null
                    || e.httpMethods().isEmpty()
                    || e.httpMethods().contains(httpMethod))
        .flatMap(e -> e.viewReturns().stream())
        .toList();
  }
}
