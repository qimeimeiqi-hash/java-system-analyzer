package com.example.shop.domain;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class Address {
  private String street;
  private String city;
}
