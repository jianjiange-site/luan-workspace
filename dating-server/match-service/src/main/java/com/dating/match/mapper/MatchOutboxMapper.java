package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.MatchOutbox;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for match_outbox table. Single-table CRUD only.
 */
@Mapper
public interface MatchOutboxMapper extends BaseMapper<MatchOutbox> {
}
