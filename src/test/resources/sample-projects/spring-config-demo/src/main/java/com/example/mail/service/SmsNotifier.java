package com.example.mail.service;

/** コンポーネントではなく、MailConfig の Bean メソッド経由で登録される。 */
public class SmsNotifier implements Notifier {

  private final String sender;

  public SmsNotifier(String sender) {
    this.sender = sender;
  }

  @Override
  public void send(String message) {}
}
