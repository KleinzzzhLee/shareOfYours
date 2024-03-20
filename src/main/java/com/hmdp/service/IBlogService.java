package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.dto.Result;
import com.hmdp.entity.Blog;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author zzzhlee
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result updateLike(Long id);

    List<Blog> queryHotBlog(Integer current);


    Blog queryBlogById(Long blogId);

    Result getLikes(Long blogId);

    Result saveBlog(Blog blog);

    Result getBlogFromUps(Long lastId, Integer offSet);
}
