package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.UserSwipeHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for user_swipe_history table. Single-table CRUD only (no JOINs per redline #1).
 */
@Mapper
public interface UserSwipeHistoryMapper extends BaseMapper<UserSwipeHistory> {
}
