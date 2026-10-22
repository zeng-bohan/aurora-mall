package com.zengbohan.aurora.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.user.entity.UserAddress;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserAddressMapper extends BaseMapper<UserAddress> {

    @Select("SELECT * FROM user_address WHERE user_id = #{userId} ORDER BY is_default DESC, id DESC")
    List<UserAddress> findByUser(long userId);

    @Select("SELECT * FROM user_address WHERE user_id = #{userId} AND id = #{id}")
    UserAddress findOwned(long userId, long id);

    @Select("SELECT * FROM user_address WHERE user_id = #{userId} ORDER BY is_default DESC, id DESC LIMIT 1")
    UserAddress findDefault(long userId);
}
