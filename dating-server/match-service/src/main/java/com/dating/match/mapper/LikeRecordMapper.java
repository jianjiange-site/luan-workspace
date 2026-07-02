package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.LikeRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for like_record table. Single-table CRUD only.
 */
@Mapper
public interface LikeRecordMapper extends BaseMapper<LikeRecord> {
}
