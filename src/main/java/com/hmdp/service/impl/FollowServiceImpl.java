package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;

    @Override
    @Transactional
    public Result follow(Long id , Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();

        if(isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            if(followMapper.insert(follow)>0){
                stringRedisTemplate.opsForSet().add(RedisConstants.USER_FOLLOW_KEY+userId,id.toString());
                stringRedisTemplate.opsForZSet().unionAndStore(
                        RedisConstants.FEED_RECEIVE_KEY+userId,
                        RedisConstants.FEED_PUBLISH_KEY+id,
                        RedisConstants.FEED_RECEIVE_KEY+userId
                );
            }else{
                return Result.fail("关注失败");
            }
        }else{
            int delete = followMapper.delete(
                    new LambdaQueryWrapper<Follow>().eq(Follow::getUserId , userId).eq(Follow::getFollowUserId , id)
            );
            if(delete>0){
                stringRedisTemplate.opsForSet().remove(RedisConstants.USER_FOLLOW_KEY+userId,id.toString());
                Set<String> allPublish = stringRedisTemplate.opsForZSet().range(RedisConstants.FEED_PUBLISH_KEY + id , 0 , -1);
                if(allPublish!=null&&!allPublish.isEmpty()){
                    for(String blogId:allPublish){
                        stringRedisTemplate.opsForZSet().remove(RedisConstants.FEED_RECEIVE_KEY+userId,blogId);
                    }
                }
            }else{
                return Result.fail("取关失败");
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        Boolean res = stringRedisTemplate.opsForSet().isMember(RedisConstants.USER_FOLLOW_KEY + userId , id.toString());

        return Result.ok(res);
    }

    @Override
    public Result commonFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1=RedisConstants.USER_FOLLOW_KEY+id;
        String key2=RedisConstants.USER_FOLLOW_KEY+userId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1 , key2);
        if(intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> userIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        List<UserDTO> userDTOList = userService.listByIds(userIds).stream().map(user -> BeanUtil.copyProperties(user , UserDTO.class))
                .collect(Collectors.toList());


        return Result.ok(userDTOList);
    }
}
