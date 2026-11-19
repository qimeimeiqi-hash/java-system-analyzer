package com.example.library.service;

import com.example.library.domain.Book;
import com.example.library.mapper.BookMapper;
import org.springframework.stereotype.Service;

@Service
public class BookService {

  private final BookMapper bookMapper;

  public BookService(BookMapper bookMapper) {
    this.bookMapper = bookMapper;
  }

  public Book find(long id) {
    return bookMapper.findById(id);
  }
}
