package com.example.shop.service;

import com.example.shop.domain.PurchaseOrder;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.repository.PurchaseOrderRepository;
import com.example.shop.web.dto.CreateOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Places and looks up orders. */
@Service
@RequiredArgsConstructor
public class OrderService {
  private final PurchaseOrderRepository purchaseOrderRepository;
  private final CustomerRepository customerRepository;

  public PurchaseOrder placeOrder(CreateOrderRequest request) {
    return null;
  }

  public PurchaseOrder find(Long id) {
    return purchaseOrderRepository.findById(id).orElseThrow();
  }
}
