package com.example.mail.config;

import com.example.mail.service.Notifier;
import com.example.mail.service.SmsNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lombok のコンストラクタ注入とフィールド注入の両方を持つ設定クラス。 */
@Configuration
@RequiredArgsConstructor
public class MailConfig {

  private final MailProperties mailProperties;

  @Autowired private AuditLogger auditLogger;

  @Bean
  public Notifier smsNotifier() {
    return new SmsNotifier(mailProperties.getSender());
  }
}
