package com.example.clinic.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** JSON API; its return values are response bodies, not views. */
@RestController
public class PetApiController {

  @GetMapping("/api/pets")
  public List<String> names() {
    return List.of("Leo");
  }
}
