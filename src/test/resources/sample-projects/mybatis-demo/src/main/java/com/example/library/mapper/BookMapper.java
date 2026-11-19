package com.example.library.mapper;

import com.example.library.domain.Book;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 書籍の Mapper。XML と注解の SQL が混在する。 */
@Mapper
public interface BookMapper {

  Book findById(@Param("id") long id);

  List<Book> search(@Param("title") String title, @Param("ids") List<Long> ids);

  @Delete("DELETE FROM books WHERE id = #{id}")
  int deleteById(long id);

  int countAll();
}
