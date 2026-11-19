package com.example.shop.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A customer who places orders. */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
public class Customer extends BaseEntity {
  @Column(nullable = false, length = 100)
  private String name;

  @Column(unique = true)
  private String email;

  private boolean active;

  @Embedded private Address address;

  @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL)
  private List<PurchaseOrder> orders = new ArrayList<>();

  @Transient private String displayName;
}
