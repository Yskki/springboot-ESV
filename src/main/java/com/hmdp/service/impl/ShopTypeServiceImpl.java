package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService
{
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopList()
    {
        //1.从redis查询缓存
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        List<String> lists = stringRedisTemplate.opsForList().range(key, 0, -1);

        //存储ShopType对象
        List<ShopType> typeList = new ArrayList<>();
        //2.存在，直接返回
        if(!lists.isEmpty()){
            for (String list : lists) {
                //将里边的json对象转化成ShopType对象
                ShopType shopType = JSONUtil.toBean(list, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }

        //3.不存在，去数据库中查询
        //这里应该是mp的语法，还不会
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        //4.数据库中不存在，返回错误信息
        if(shopTypeList.isEmpty()){
            return Result.fail("该分类不存在！");
        }

        //5.数据库中存在，写入redis
        for (ShopType shopType : shopTypeList) {
            String jsonStr = JSONUtil.toJsonStr(shopType);
            lists.add(jsonStr);
        }
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY,lists);

        //6.返回商铺信息
        return Result.ok(shopTypeList);
    }
}
