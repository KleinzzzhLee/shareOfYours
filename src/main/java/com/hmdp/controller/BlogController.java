package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.dto.Result;
import com.hmdp.entity.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author zzzhlee
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
@Slf4j
public class BlogController {

    @Resource
    private IBlogService blogService;



    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {

        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 修改点赞数量
        return blogService.updateLike(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        log.debug("进入/of/me控制层");
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        List<Blog> records = blogService.queryHotBlog(current);
        return Result.ok(records);
    }

    @GetMapping("/{blogId}")
    public Result queryBlogById(@PathVariable("blogId") Long blogId) {
        log.debug("进入/{blogId}控制层");
        Blog blog = blogService.queryBlogById(blogId);
        return Result.ok(blog);
    }


    @GetMapping("/of/user")
    public Result queryBlogByUserId(@RequestParam(value = "current", defaultValue = "1") Integer current,
                                    @RequestParam(value = "id") Long id) {
        Page<Blog> page = blogService.query()
                .eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @GetMapping("/likes/{blogId}")
    public Result getLikes(@PathVariable("blogId") Long blogId){
        return blogService.getLikes(blogId);
    }

    @GetMapping("/of/follow")
    public Result getBlogsFromUps(@RequestParam("lastId") long lastId,
                                  @RequestParam(value = "offSet", defaultValue = "0") Integer offSet) {
        return blogService.getBlogFromUps(lastId,offSet);
    }
}
