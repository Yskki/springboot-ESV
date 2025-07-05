package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.redisson.client.protocol.RedisCommands;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoLocation;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import java.security.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests
{
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private HttpServletRequest request;

    @Test
    void testHyperLogLog()
    {
        //通过这个可以获取到客户端的ip地址（没有经过反向代理的）
        System.out.println(request.getRemoteAddr());
    }

    @Test
    void testSaveShop(){
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1L, shop,10L, TimeUnit.SECONDS);
    }

    @Test
    void loadShopData()
    {
        //1.查询店铺信息
        List<Shop> list = shopService.list();
        //2.把店铺分组，按照typeId分组，typeId一致的放到一个集合中
        Map<Long,List<Shop>>map = list.stream().collect(Collectors.groupingBy(Shop :: getTypeId));
        //3.分批写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet())
        {
            //3.1获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //3.3写入redis中
            for (Shop shop : value)
            {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }
}
