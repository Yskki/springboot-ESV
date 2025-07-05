package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

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

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current)
    {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id)
    {
        //1.查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在！");
        }
        //2.查询blog有关的用户
        queryBlogUser(blog);

        //3.查询blog是否被点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog)
    {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            //用户未登录,无需查询是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        //2.判断用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id)
    {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.判断用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //3.如果未点赞，可以点赞
            //3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2.将点赞用户写入redis的set集合
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }
        else{
            //4.如果已经点赞，取消点赞
            //4.1数据库点赞数 - 1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2将点赞用户从redis中的set集合移出
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogByLikes(Long id)
    {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //1.查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //3.根据用户id查询用户
        List<UserDTO> userDTOS = userService.query()
                 //该句相当于sql中的WHERE ID IN (5,1) ORDER BY FIELD(id,5,1),可以按照field中的顺序显示查询结果，否则默认按照从小到大顺序
                .in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog)
    {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败！");
        }
        //查询粉丝作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId().toString()).list();
        //推送笔记id给所有粉丝
        for (Follow follow : follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset)
    {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析数据：bolgId、minTime（时间戳）、offset
        List<Long>ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;//最小时间戳
        int os = 1;//offset
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples)
        {
            //获取id
            ids.add(Long.valueOf(tuple.getValue()));//返回的id是泛型的，将其转化为long类型
            //获取分数（时间戳）
            long time = tuple.getScore().longValue();//返回的分数是double类型的，将其转化为long类型
            if(time == minTime) {
                os ++ ;
            }
            else {
                minTime = time;
                os = 1;
            }
        }

        //4.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs)
        {
            //查询blog有关的用户
            queryBlogUser(blog);
            //查询blog是否被点赞
            isBlogLiked(blog);
        }

        //5.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
