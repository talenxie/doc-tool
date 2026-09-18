package com.doctool.mapper;

import com.doctool.model.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM users WHERE username = #{username}")
    User findByUsername(String username);

    @Insert("INSERT INTO users (username, password, role) VALUES (#{username}, #{password}, #{role})")
    int insert(User user);

    @Update("UPDATE users SET password = #{password} WHERE id = #{id}")
    int updatePassword(User user);
}
