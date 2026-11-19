// expect: R3:4 R3:5
package com.example.javaanalyzer.ir;

import org.springframework.lang.Nullable;
import com.github.javaparser.ast.Node;

/** Name as written, e.g. {@code jakarta.persistence.OneToMany} or org.springframework.stereotype.Service. */
record FrameworkTypes(String name) {}
