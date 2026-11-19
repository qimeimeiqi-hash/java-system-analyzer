package com.example.shop.web.dto;

import java.util.List;
import lombok.Data;

@Data
public class CreateOrderRequest {
  private Long customerId;
  private List<Long> productIds;
}
