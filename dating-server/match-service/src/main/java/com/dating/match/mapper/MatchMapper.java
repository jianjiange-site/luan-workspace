package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.Match;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for match table. Single-table CRUD only.
 */
@Mapper
public interface MatchMapper extends BaseMapper<Match> {
}
