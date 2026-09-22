package com.doctool.mapper;

import com.doctool.model.AnalysisCache;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AnalysisCacheMapper {

    @Insert("INSERT INTO analysis_cache (fingerprint, analysis_type, result_data, create_time) " +
            "VALUES (#{fingerprint}, #{analysisType}, #{resultData}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnalysisCache cache);

    @Select("SELECT * FROM analysis_cache WHERE fingerprint=#{fingerprint} AND analysis_type=#{analysisType} " +
            "ORDER BY create_time DESC LIMIT 1")
    AnalysisCache findByFingerprintAndType(@Param("fingerprint") String fingerprint,
                                            @Param("analysisType") String analysisType);

    @Select("SELECT * FROM analysis_cache WHERE fingerprint=#{fingerprint} ORDER BY create_time DESC")
    List<AnalysisCache> findByFingerprint(@Param("fingerprint") String fingerprint);

    @Delete("DELETE FROM analysis_cache WHERE id=#{id}")
    int deleteById(Long id);

    @Delete("DELETE FROM analysis_cache WHERE fingerprint=#{fingerprint} AND analysis_type=#{analysisType}")
    int deleteByFingerprintAndType(@Param("fingerprint") String fingerprint,
                                   @Param("analysisType") String analysisType);

    @Update("UPDATE analysis_cache SET result_data=#{resultData} WHERE id=#{id}")
    int updateResultData(AnalysisCache cache);
}
