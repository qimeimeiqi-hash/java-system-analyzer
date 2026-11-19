package com.example.shop.web;

import com.example.shop.service.OrderService;
import com.example.shop.web.dto.CreateOrderRequest;
import com.example.shop.web.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
  private final OrderService orderService;

  @GetMapping("/{id}")
  public ResponseEntity<OrderResponse> get(@PathVariable Long id) {
    return null;
  }

  @PostMapping
  public OrderResponse create(@RequestBody CreateOrderRequest request) {
    return null;
  }
}
