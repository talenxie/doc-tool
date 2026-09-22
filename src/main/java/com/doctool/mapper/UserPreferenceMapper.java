package com.doctool.mapper;

import com.doctool.model.UserPreference;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface UserPreferenceMapper {

    @Select("SELECT * FROM user_preference WHERE username=#{username} AND pref_key=#{prefKey}")
    UserPreference findByUsernameAndKey(@Param("username") String username, @Param("prefKey") String prefKey);

    @Select("SELECT * FROM user_preference WHERE username=#{username}")
    List<UserPreference> findByUsername(@Param("username") String username);

    @Insert("INSERT INTO user_preference (username, pref_key, pref_value) VALUES (#{username}, #{prefKey}, #{prefValue})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserPreference pref);

    @Update("UPDATE user_preference SET pref_value=#{prefValue} WHERE username=#{username} AND pref_key=#{prefKey}")
    int updateValue(@Param("username") String username, @Param("prefKey") String prefKey, @Param("prefValue") String prefValue);

    @Delete("DELETE FROM user_preference WHERE username=#{username} AND pref_key=#{prefKey}")
    int deleteByUsernameAndKey(@Param("username") String username, @Param("prefKey") String prefKey);
}
