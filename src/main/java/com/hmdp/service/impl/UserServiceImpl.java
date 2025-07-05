package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService
{
    @Resource //这个和@Autowired功能差不多，在这里使用那个都可以
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session)
    {
        //1.校验手机号是否符合
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，直接返回错误信息
            return Result.fail("手机格式错误！");
        }

        //3.如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4.将验证码保存到redis中,并设置有效期为两分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码(这里是模拟发送，并没有真的发送)
        log.info("发送短信验证码成功，验证码为：{}",code);

        //返回
        return Result.ok();
    }
    //这个代码是加了手机黑名单功能的代码
//    @Override
//    public Result sendCode(String phone, HttpSession session)
//    {
//        // 1.校验手机号
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            // 2.如果不符合，返回错误信息
//            return Result.fail("手机号格式错误！");
//        }
//
//        // 4.如果存在锁，直接返回失败
//        String phoneGetCodeLock = stringRedisTemplate.opsForValue().get(GET_CODE_LOCK + phone);
//        if (phoneGetCodeLock != null) {
//            return Result.fail("获取验证码过快，请稍后重试！");
//        }
//
//        // 5.检查黑名单中的次数
//        String blacklistPhoneCount = stringRedisTemplate.opsForValue().get(GET_CODE_BLACKLIST_PHONE + phone);
//        int getCodePhoneCount = (blacklistPhoneCount != null) ? Integer.parseInt(blacklistPhoneCount) : 0;
//        if (getCodePhoneCount >= 400) {
//            return Result.fail("获取验证码次数过多，您的手机号已被限制！");
//        }
//
//        // 6.更新锁和黑名单
//        stringRedisTemplate.opsForValue().set(GET_CODE_BLACKLIST_PHONE + phone, String.valueOf(getCodePhoneCount + 1), REFRESH_BLACKLIST_TTL, TimeUnit.HOURS);
//        stringRedisTemplate.opsForValue().set(GET_CODE_LOCK + phone, "1", GET_CODE_LOCK_TTL, TimeUnit.MINUTES);
//
//        // 7.校验通过，生成验证码
//        // String code = RandomUtil.randomNumbers(6);
//        String code = "123123"; //为了方便测试，改成固定值
//
//        // 8.保存验证码到 redis，并上锁
//        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
//
//        // 9.发送验证码
//        log.debug("发送短信验证码成功，验证码：{}", code);
//        // 10.返回ok
//        return Result.ok();
//    }
    /**
     * 实现登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session)
    {
        String phone = loginForm.getPhone();
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，直接返回错误信息
            return Result.fail("手机格式错误！");
        }
        //2.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(code == null || !cacheCode.equals(code)){
            //3.不一致，返回错误信息
            return Result.fail("验证码错误！");
        }

        //4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        //这里使用的是mybatisplus，效果和上边sql语句一样
        //one()就是返回一条数据,list()就是返回多条数据
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if(user == null){
            //6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        //7.保存用户信息到redis中
        //7.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);

        //7.2 将user对象转为hashmap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //这里因为使用的是StringRedisTemplate，所以key和value都要求是string类型的
        //而userDTO中id设计的Long类型的，此时转化为出现错误
        //有两种解决方法：1.不使用BeanUtil工具类自己创建map转化
        //2.自定义类型
        //这里使用第二种方法
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fildName,fildValue) -> fildValue.toString()));

        //7.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        //7.4 设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //8. 返回token
        return Result.ok(token);
    }

    @Override
    public Result sign()
    {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + keySuffix + userId;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }

    @Override
    public Result signCount()
    {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + keySuffix + userId;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截止今天为止所有的签到记录，返回的是一个十进制的数据
        //就是从第0天开始到今天所有的bit位
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result == null || result.isEmpty()){
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        //6.循环遍历
        int count = 0;
        while(true)
        {
            //6.1.让这个数字与1做与运算，得到数字的最后一个bit位
            //6.2.判断这个bit位是否为0
            if((num & 1) == 0){
                //6.3如果为0，说明未签到,直接结束
                break;
            }
            else {
                //6.4如果不为0，计数器加1
                count ++ ;
            }
            //6.5数字(无符号)右移一位，判断下一位bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone)
    {
        User user = new User();
        user.setPhone(phone);
        //随机生成名称
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //使用mp(mybatisplus)保存用户
        save(user);
        return user;
    }
}
