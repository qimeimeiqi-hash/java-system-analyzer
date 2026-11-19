package com.example.mail;

import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MailApplication {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
