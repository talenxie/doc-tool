package com.doctool.mapper;

import com.doctool.model.TaskRecord;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface TaskRecordMapper {

    @Insert("INSERT INTO task_record (task_type, original_filename, status, create_time) " +
            "VALUES (#{taskType}, #{originalFilename}, #{status}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TaskRecord record);

    @Update("UPDATE task_record SET status=#{status}, result_filename=#{resultFilename}, " +
            "error_message=#{errorMessage}, finish_time=#{finishTime} WHERE id=#{id}")
    int update(TaskRecord record);

    @Select("SELECT * FROM task_record ORDER BY create_time DESC")
    List<TaskRecord> findAll();

    @Select("SELECT * FROM task_record WHERE id=#{id}")
    TaskRecord findById(Long id);
}
