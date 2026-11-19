package com.example.billing.service.impl;

import com.example.billing.service.InvoiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** InvoiceServiceImpl と相互に依存する（循環依存の検証用）。 */
@Service
public class AuditService {

  @Autowired private InvoiceService invoiceService;

  public void record() {
    invoiceService.count();
  }
}
