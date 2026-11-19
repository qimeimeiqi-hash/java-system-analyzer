package com.example.javaanalyzer.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the top page of the tool. */
@Controller
public class HomeController {

  @GetMapping("/")
  public String showIndex() {
    return "index";
  }
}
