package com.example.clinic.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/** Screens of pets. */
@Controller
@RequestMapping("/pets")
public class PetController {

  private static final String VIEWS_PET_FORM = "pets/createOrUpdatePetForm";

  @GetMapping
  public String list() {
    return "pets/petList";
  }

  @GetMapping("/new")
  public String initCreationForm() {
    return VIEWS_PET_FORM;
  }

  @PostMapping("/new")
  public String processCreationForm(boolean hasErrors) {
    return hasErrors ? VIEWS_PET_FORM : "redirect:/pets/{petId}";
  }

  @GetMapping("/{petId}")
  public ModelAndView showPet(@PathVariable int petId) {
    ModelAndView mav = new ModelAndView("pets/petDetails");
    mav.addObject("petId", petId);
    return mav;
  }
}
