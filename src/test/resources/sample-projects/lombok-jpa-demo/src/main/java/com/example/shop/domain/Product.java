package com.example.shop.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Set;
import lombok.Getter;

@Entity
@Getter
public class Product extends BaseEntity {
  private String name;
  private BigDecimal price;

  @ManyToMany(mappedBy = "products")
  private Set<PurchaseOrder> orders;
}
