package com.example.billing.web;

import com.example.billing.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 請求書API。 */
@RestController
@RequiredArgsConstructor
public class InvoiceController {

  private final InvoiceService invoiceService;

  @GetMapping("/invoices/count")
  public long count() {
    return invoiceService.count();
  }
}
