package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.dto.FeedbackMessageDTO;
import com.hmdp.entity.dto.Result;
import com.hmdp.entity.dto.ScrollResult;
import com.hmdp.entity.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.entity.vo.BlogLikeFeedBackVO;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import com.hmdp.utils.enums.FeedbackType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
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

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private RabbitUtil rabbitUtil;

    /**
     *     kp 增加一个异步线程， 从项目启动后开始运行    在RedisSchedulerTask
     *     initRedisBlog : 项目启动后， 初始化redis当中的点赞信息
     *     getNeedUpdateLikeBlog ： 获取到需要更新的博客id， 这是为了防止 大量无需更新的点赞信息造成redis和数据库额外的压力
     *     todo 下面这个可以进行修正， 采用事物的方式
     *     updateBlogLikesInRedis ： 在缓存中更新信息
     *     更新数据库的点赞数量
     */
//    private void initRedisBlog() {
//
//    }


    /**
     *  kp 原来采用的是推模式，
     *  todo 改为推拉结合，  将博客id保存在博主的zset中，
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        blog.setTimestamp(System.currentTimeMillis());
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
            redisTemplate.opsForZSet().add(key, blog.getId().toString(), blog.getTimestamp());
        }
        String jsonStr = JSONUtil.toJsonStr(blog);
        // kp 将消息推送到消息队列
        rabbitTemplate.convertAndSend(RabbitConstants.BLOG_EXCHANGE + user.getId() % 10,
                RabbitConstants.PREFIX_ROUTING_KEY + user.getId(),
                jsonStr);

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
        // 0、现在缓存中查询， 未查询到再从数据库查询
        String blogJSON = (String) redisTemplate.opsForValue().get(RedisConstants.BLOG_CACHE + blogId);
        Blog blog = JSONUtil.toBean(blogJSON, Blog.class);
        if (StringUtils.isEmpty(blogJSON)) {
            // 1、根据ID区数据库查询
            blog = getById(blogId);
            // 2、 设置过期时间
            redisTemplate.opsForValue().set(RedisConstants.BLOG_CACHE + blogId, JSONUtil.toJsonStr(blog), RedisConstants.BLOG_CACHE_TIME + RandomUtil.randomLong(300), TimeUnit.SECONDS);
        }
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
        // todo 改为管道
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
            // todo 向消息队列发送消息

            // kp 对消息进行封装
            FeedbackMessageDTO messageDTO = new FeedbackMessageDTO();
            BlogLikeFeedBackVO blogLikeFeedBackVO = new BlogLikeFeedBackVO();
            blogLikeFeedBackVO.setBlogId(id);
            blogLikeFeedBackVO.setUserId(user.getId());
            blogLikeFeedBackVO.setTime(LocalDateTime.now());

            messageDTO.setType(FeedbackType.isBlogLike);
            messageDTO.setData(blogLikeFeedBackVO);
            Long authorId = getById(id).getUserId();
            // 发送消息
            rabbitUtil.sendFeedbackMessage(RabbitConstants.FEEDBACK_EXCHANGE_PREFIX + authorId,
                    RabbitConstants.FEEDBACK_QUEUE_PREFIX + authorId,
                    messageDTO,
                    RabbitConstants.FEEDBACK_ROUTING_KEY_PREFIX + id);

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
        // kp 查询当前用户是否点赞
        Long rank = redisTemplate.opsForZSet().rank(RedisConstants.BLOG_LIKE_CACHE + blog.getId().toString(), userId.toString());
        if(BeanUtil.isNotEmpty(rank)) {
            blog.setIsLike(true);
        } else {
            blog.setIsLike(false);
        }
        // 修改页面的点赞数量
        String count = (String) redisTemplate.opsForHash().get(RedisConstants.BLOG_LIKE_TOTAL, blog.getId().toString());
        if(count == null) {
            blog.setLiked(0);
        } else {
            blog.setLiked(Integer.parseInt(count));
        }

    }

    /**
     * 关注推送功能的实现
     *          1、博主上传博文保存到数据库的时候， 自动向redis中保持一份
     *          2、采用推模式， 向所有粉丝的收件箱保存一份 就是缓存id
     *          3、用户点击关注，去收件箱查找id
     *          4、取出后，再从数据库取出，依照时间戳排序
     *  todo 改为消息队列实现
     * @param lastTimeStamp 上一次的时间戳
     * @param offSet  下一次的偏移量
     * @return
     */
    @Override
    public Result getBlogFromUps(Long lastTimeStamp, Integer offSet) {
        // 1、获取当前用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
       // kp 从rabbitMQ中获取
        List<Blog> res = getBlogFromRabbit(lastTimeStamp);
       // kp  实时队列中不为空 直接返回
        if ( res != null && res.size() != 0) {
            // 封住返回数据
            Long minTime = Math.min(res.get(0).getTimestamp(), lastTimeStamp);
            Collections.reverse(res);
            return scorllResult(res, 1, minTime);
        }
        // kp 消息队列为空 从收件箱
        // 2、从当前用户的收件箱中拿出博客id
        Set<ZSetOperations.TypedTuple<Object>> typedTuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(
                        RedisConstants.FEED_OUT + user.getId().toString(),
                        0, lastTimeStamp, offSet, 3);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 4.解析数据：blo gId、minTime（时间戳）、offset
        List<Long> ids = typedTuples.stream()
                .map((t) -> {
                    return Long.valueOf(t.getValue().toString());
                }).collect(Collectors.toList());
        long minTime = 0; // 2
        int os = 1; // 2
        /**
         * kp 这里的目的： 假如出现了 时间戳为 5 4 4 2 2的情况， 即出现了时间戳相同的情况
         *              reverseRangeByScoreWithScores是从后向前查，查到第一个max，
         *              通过offset可以避免数据的重复读取， 表示从当前位置偏移
         *              假如 count = 3， 那么下次开始查询的时间戳为4，
         *                      如果不设置offset， 那么将从第二个开始查，
         *                      如果设置offset = 1， 那么将从第三个开始查询，
         *                      所以，从第二次查询开始，要附带参数offset，表示上一次结尾的重复次数
         */
        for (ZSetOperations.TypedTuple<Object> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
//            ids.add(Long.valueOf((String) tuple.getValue()));
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
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();

        return scorllResult(blogs, os, minTime);
    }
    //  kp 从消息队列中获取 全部的更新信息
    private List<Blog> getBlogFromRabbit(Long lastTiTimeStamp) {
        UserDTO user = UserHolder.getUser();
        // kp 监测是否为活跃用户
        Boolean isMember = redisTemplate.opsForSet().isMember(RedisConstants.USER_ACTIVE_LIST, user.getId().toString());
        if(BooleanUtil.isFalse(isMember)) {
            return null;
        }
        // todo 生产者确认 和 消费者确认 存在不会
        List<String> res = rabbitUtil.getMessageACK(RabbitConstants.CONCENTRATED_QUEUE + user.getId());
        // 转为博客
        return res.stream().map(value -> JSONUtil.toBean(value, Blog.class)).collect(Collectors.toList());
//        while(true) {
//            Message msg = rabbitTemplate.receive(RabbitConstants.CONCENTRATED_QUEUE + user.getId());
//            if(msg != null) {
//                Blog blog = (Blog) RabbitUtil.getInfoFromMessageBody(msg, Blog.class);
//                res.add(blog);
//            } else {
//                return res;
//            }
//        }

    }

    /**
     * 封装滚动查询的返回信息
     * @param blogs 返回的博客
     * @param offset  下一次的偏移量， 避免时间戳一致重复获取
     * @param minTime 下次获取的时间戳
     * @return
     */
    private Result scorllResult(List<Blog> blogs, int offset, Long minTime) {
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            fillBlogWithUserInf(blog);
            // 5.2.查询blog是否被点赞
            updateFromCache(blog);
        }
        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(offset);
        r.setMinTime(minTime);
        return Result.ok(r);
    }


}
