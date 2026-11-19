package com.example.billing.service.impl;

import com.example.billing.repository.InvoiceRepository;
import com.example.billing.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {

  private final InvoiceRepository invoiceRepository;
  private final AuditService auditService;

  @Override
  public long count() {
    auditService.record();
    return invoiceRepository.count();
  }
}
