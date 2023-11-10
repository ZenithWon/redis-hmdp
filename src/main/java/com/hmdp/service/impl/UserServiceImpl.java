package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private UserMapper userMapper;

    @Override
    public Result sendCode(String phone , HttpSession session) {

        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }

        String validateCode = RandomUtil.randomNumbers(6);
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,validateCode,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.debug("Send validate code successfully, code=>{}",validateCode);
        return Result.ok("发送验证码："+validateCode);
    }

    @Override
    public Result login(LoginFormDTO loginForm , HttpSession session) {
        String phone=loginForm.getPhone();
        String inputCode = loginForm.getCode();

        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }

        if(StringUtils.isEmpty(inputCode)){
            if(!StringUtils.isEmpty(loginForm.getPassword())) {
                return Result.fail("暂不支持密码登录");
            }else{
                return Result.fail("验证码为空");
            }
        }

        String code = (String) redisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+phone);
        if(StringUtils.isEmpty(code)){
            return Result.fail("验证码已经过期");
        }
        if(!code.equals(inputCode)){
            return Result.fail("验证码错误");
        }

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone , phone)
        );
        if(user==null){
            user=new User();
            user.setPhone(phone);
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            userMapper.insert(user);
            log.debug("User not exist, register auto=>{}",user.toString());
        }

        String token= UUID.randomUUID().toString(true);
        UserDTO userDTO=BeanUtil.copyProperties(user,UserDTO.class);
        Map<String ,Object> userMap=BeanUtil.beanToMap(userDTO);

        redisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,userMap);
        //NOTE:这里关掉TTL，方便测试防止每次都要改变token
//        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);


        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token=request.getHeader("authorization");
        redisTemplate.delete(RedisConstants.LOGIN_USER_KEY+token);
        UserHolder.removeUser();
        return Result.ok();
    }
}
