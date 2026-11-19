package com.example.mail.service;

import org.springframework.stereotype.Component;

@Component
public class EmailNotifier implements Notifier {

  @Override
  public void send(String message) {}
}
