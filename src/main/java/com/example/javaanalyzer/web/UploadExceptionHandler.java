package com.example.javaanalyzer.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Multipart size violations are raised before a handler is chosen, so they are handled globally.
 */
@ControllerAdvice
public class UploadExceptionHandler {

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ModelAndView handleMaxUploadSizeExceeded() {
    ModelAndView modelAndView = new ModelAndView("index");
    modelAndView.addObject("errorMessage", "アップロード可能なファイルサイズの上限を超えています。");
    modelAndView.setStatus(HttpStatus.PAYLOAD_TOO_LARGE);
    return modelAndView;
  }
}
