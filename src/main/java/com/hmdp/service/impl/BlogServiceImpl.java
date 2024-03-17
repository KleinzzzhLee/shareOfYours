package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zzzhlee
 * @since 2021-12-22
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private IFollowService followService;

    private static ExecutorService threadPool = Executors.newSingleThreadExecutor();
    /**
     *     keypoint 增加一个异步线程， 从项目启动后开始运行
     *     initRedisBlog : 项目启动后， 初始化redis当中的点赞信息
     *     getNeedUpdateLikeBlog ： 获取到需要更新的博客id， 这是为了防止 大量无需更新的点赞信息造成redis和数据库额外的压力
     *     todo 下面这个可以进行修正， 采用事物的方式
     *     updateBlogLikesInRedis ： 在缓存中更新信息
     *     更新数据库的点赞数量
     */
    private void initRedisBlog() {
        // 1、查出所有的博客
        List<Blog> list = list();
        // 2、向缓存中存入三张表的信息
        list.forEach((blog)->{
            redisTemplate.opsForHash().put(RedisConstants.BLOG_LIKE_ISUPDATE, blog.getId().toString(), String.valueOf(0));
            redisTemplate.opsForHash().put(RedisConstants.BLOG_LIKE_TOTAL, blog.getId().toString(), blog.getLiked().toString());
        });
    }
    private List<Long> getNeedUpdateLikeBlog() {
        Map<Object, Object> updateMap = redisTemplate.opsForHash().entries(RedisConstants.BLOG_LIKE_ISUPDATE);
        List<Long> updateBolgList = new ArrayList<>();
        log.debug("进入blog的异步1");
        updateMap.forEach((key, value) -> {

            if ( !String.valueOf(value).equals("0")) {
                updateBolgList.add(Long.valueOf(String.valueOf(key)));
            }
        });
        return updateBolgList;
    }
    private String updateBlogLikesInRedis(List<Long> updateBolgList, int i) {
        String blogId = updateBolgList.get(i).toString();
        String count = (String) redisTemplate.opsForHash().get(RedisConstants.BLOG_LIKE_TOTAL, blogId);
        redisTemplate.opsForHash().put(RedisConstants.BLOG_LIKE_ISUPDATE, blogId, String.valueOf(0));
        return count;
    }
    @PostConstruct
    private void init() {
        // todo 创建三个表
        initRedisBlog();
        // 4、检验每次的需要更新的人数， 人数多，分批次执行
        threadPool.submit(new Runnable() {
            // 3、开启异步定时任务
            @Override
            public void run() {
                while (true) {
                    try {
                        // 1、从redis当中获取哪个博客点赞需要更新
                        List<Long> updateBolgList = getNeedUpdateLikeBlog();
                        log.debug("进入blog的异步2");
                        int total = updateBolgList.size();
                        boolean isSleep = false;
                        if (total > 10000) {
                            isSleep = true;
                        }
                        log.debug("开始blog的异步循环");
                        // 2、在BLOG_LIKE_TOTAL 和 对应的 BLOG_LIKE_CACHE 查询，
                        for (int i = 0; i < total; i++) {
                            if (isSleep && i % 10000 == 0) {
                                Thread.sleep(3000);
                            }
                            // 3、修改三个表的信息
                            String count = updateBlogLikesInRedis(updateBolgList, i);
                            UpdateWrapper<Blog> blogUpdateWrapper = new UpdateWrapper<>();
                            blogUpdateWrapper.setSql("liked =  " + count).eq("id", updateBolgList.get(i));
                            boolean update = update(blogUpdateWrapper);
                            if(!update) {
                                throw new Exception();
                            }
                        }
                        Thread.sleep(1000 * 20);
                    }catch(Exception e){
                        log.debug("出现了BUG");
                    }
                }
            }
        });
    }



    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long followUserId = follow.getFollowUserId();
            // 4.2.推送
            String key = RedisConstants.FEED_OUT + followUserId;
            redisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }


    @Override
    public List<Blog> queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach((record) -> {
            updateFromCache(record);
            fillBlogWithUserInf(record);
        });
        return records;
    }

    @Override
    public Blog queryBlogById(Long blogId) {
        // 1、根据ID区数据库查询
        Blog blog = getById(blogId);
        blog.setId(blogId);
        // 2、查询当前用户的部分信息
        fillBlogWithUserInf(blog);
        // 查询是否点赞
        updateFromCache(blog);
        // 3、返回
        return blog;
    }

    /**
     * 获取当前博客的点赞排行榜
     * todo 想实现点赞排行榜的化， 采用Zset的形式进行存储数据。 注意， 在进行数据库的查询时
     * @param blogId
     * @return
     */
    @Override
    public Result getLikes(Long blogId) {
        // 1、从redis中获取到点赞人数
        Set<Object> range = redisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKE_CACHE + blogId.toString(), 0, 4);
        List<String> ids = range.stream().map(Object::toString).collect(Collectors.toList());
        // 2、从数据库中查询对应信息
//        List<User> users = userService.listByIds(list);
        String join = StrUtil.join(",", ids);
        List<User> users = userService.query().in("id", ids).last("order by field(id,"+join + ")").list();
        // 上述方式查询， 会从
        // 3、将信息封装到UserDTO
        List<UserDTO> userDTOS = users.stream().map((user) -> {
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            return userDTO;
        }).collect(Collectors.toList());

        return Result.ok(userDTOS);
    }



    public void fillBlogWithUserInf(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 点赞
     * @param id
     * @return
     */
    @Override
    public Result updateLike(Long id) {
        UserDTO user = UserHolder.getUser();
        // 1、在缓存中查找当前用户是否点赞
        Long rank = redisTemplate.opsForZSet().rank(RedisConstants.BLOG_LIKE_CACHE + id.toString(), user.getId().toString());
        // 1.2 在需要更新表中设置
        // 2、如果点赞， 删除记录
        boolean loved = false;
        if(BeanUtil.isNotEmpty(rank)) {
            redisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKE_CACHE + id.toString(), user.getId().toString());
            redisTemplate.opsForHash().increment(RedisConstants.BLOG_LIKE_ISUPDATE, id.toString(), -1);
            redisTemplate.opsForHash().increment(RedisConstants.BLOG_LIKE_TOTAL, id.toString(), -1);
            loved = false;
        } else {
            // 3、没点赞， 增加缓存信息
            redisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKE_CACHE + id.toString(), user.getId().toString(), System.currentTimeMillis());
            redisTemplate.opsForHash().increment(RedisConstants.BLOG_LIKE_ISUPDATE, id.toString(), 1);
            redisTemplate.opsForHash().increment(RedisConstants.BLOG_LIKE_TOTAL, id.toString(), 1);
            loved = true;
        }
        // 4、向前端返回 点赞状态状态
        Blog blog = new Blog();
        blog.setId(id);
        blog.setIsLike(loved);
        return Result.ok();
    }

    /**
     *  从缓存中临时更新 ， 实现点赞可见功能
     * @param blog
     */
    private void updateFromCache(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            return;
        }
        Long userId = user.getId();
        // keypoint 查询当前用户是否点赞
        Long rank = redisTemplate.opsForZSet().rank(RedisConstants.BLOG_LIKE_CACHE + blog.getId().toString(), userId.toString());
        if(BeanUtil.isNotEmpty(rank)) {
            blog.setIsLike(true);
        } else {
            blog.setIsLike(false);
        }
        // 修改页面的点赞数量
        String count = (String) redisTemplate.opsForHash().get(RedisConstants.BLOG_LIKE_TOTAL, blog.getId().toString());
        blog.setLiked(Integer.parseInt(count));
    }

    /**
     * 关注推送功能的实现
     *          1、博主上传博文保存到数据库的时候， 自动向redis中保持一份
     *          2、采用推模式， 向所有粉丝的收件箱保存一份 就是缓存id
     *          3、用户点击关注，去收件箱查找id
     *          4、取出后，再从数据库取出，依照时间戳排序
     * @param lastId
     * @param offSet
     * @return
     */
    @Override
    public Result getBlogFromUps(Long lastId, Integer offSet) {
        // 1、获取当前用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 2、从当前用户的收件箱中拿出博客id
        Set<ZSetOperations.TypedTuple<Object>> typedTuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(
                        RedisConstants.FEED_OUT + user.getId().toString(),
                        0, lastId, offSet, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = typedTuples.stream()
                .map((t) -> {
                    return Long.valueOf(t.getValue().toString());
                }).collect(Collectors.toList());
        long minTime = 0; // 2
        int os = 1; // 2
            for (ZSetOperations.TypedTuple<Object> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf((String) tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }

        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            fillBlogWithUserInf(blog);
            // 5.2.查询blog是否被点赞
            updateFromCache(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }
}
