package com.example.shop.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;

/** Common columns of all entities. */
@MappedSuperclass
@Getter
public abstract class BaseEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}
