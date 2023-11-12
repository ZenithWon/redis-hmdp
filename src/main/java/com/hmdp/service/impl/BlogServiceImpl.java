package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;
    @Autowired
    private BlogMapper blogMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    private boolean isBlogLiked(Long blogId){
        UserDTO user = UserHolder.getUser();
        if(user==null){
            return false;
        }
        Long userId= user.getId();
        String key=RedisConstants.BLOG_LIKED_KEY+blogId;
        Double score= stringRedisTemplate.opsForZSet().score(key , userId.toString());
        return (score!=null);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = blogMapper.selectById(id);

        if(blog==null){
            return Result.fail("博客不存在");
        }

        Long userId = blog.getUserId();
        User user = userService.getById(userId);

        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
        blog.setIsLike(isBlogLiked(blog.getId()));

        return Result.ok(blog);

    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            blog.setIsLike(isBlogLiked(blog.getId()));
        });
        return Result.ok(records);
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key=RedisConstants.BLOG_LIKED_KEY+id;

        Double score = stringRedisTemplate.opsForZSet().score(key , userId.toString());

        if(score==null){
            boolean update = update().setSql("liked=liked+1").eq("id" , id).update();
            if(update){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }else{
                return Result.fail("操作失败");
            }
        }else{
            boolean update = update().setSql("liked=liked-1").eq("id" , id).update();
            if(update){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }else{
                return Result.fail("操作失败");
            }
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key=RedisConstants.BLOG_LIKED_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key , 0 , 4);
        if(CollectionUtil.isEmpty(top5)){
            return Result.ok(Collections.emptyList());
        }

        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        List<UserDTO> userDTOS=new ArrayList<>();
        for(Long userId:userIds){
            User user= userService.getById(userId);
            userDTOS.add(BeanUtil.copyProperties(user,UserDTO.class));
        }

        return Result.ok(userDTOS);
    }

}
