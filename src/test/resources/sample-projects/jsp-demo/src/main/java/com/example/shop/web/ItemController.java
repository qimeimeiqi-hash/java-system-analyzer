package com.example.shop.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Screens of items. */
@Controller
public class ItemController {

  @GetMapping("/")
  public String home() {
    return "forward:/items";
  }

  @GetMapping("/items")
  public String list() {
    return "items/list";
  }

  @GetMapping("/items/{id}")
  public String detail(@PathVariable long id) {
    return "items/detail";
  }

  @PostMapping("/items")
  public String create() {
    return "redirect:/items";
  }

  @GetMapping("/items/count")
  @ResponseBody
  public String count() {
    return "3";
  }
}
