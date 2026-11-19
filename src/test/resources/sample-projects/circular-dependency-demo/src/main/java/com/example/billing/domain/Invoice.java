package com.example.billing.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 請求書。 */
@Entity
@Table(name = "invoices")
public class Invoice {

  @Id private Long id;
}
