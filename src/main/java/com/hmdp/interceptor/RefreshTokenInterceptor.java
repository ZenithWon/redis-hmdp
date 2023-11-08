package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request , HttpServletResponse response , Object handler) throws Exception {
        String token=request.getHeader("authorization");

        if(StringUtils.isEmpty(token)){
            UserHolder.saveUser(null);
            return true;
        }

        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);

        if(userMap.isEmpty()){
            UserHolder.saveUser(null);
            return true;
        }

        UserDTO user = BeanUtil.fillBeanWithMap(userMap , new UserDTO() , false);
        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        UserHolder.saveUser(user);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request , HttpServletResponse response , Object handler , Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
