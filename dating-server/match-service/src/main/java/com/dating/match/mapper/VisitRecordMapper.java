package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.VisitRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for visit_record table. Single-table CRUD only.
 */
@Mapper
public interface VisitRecordMapper extends BaseMapper<VisitRecord> {
}
